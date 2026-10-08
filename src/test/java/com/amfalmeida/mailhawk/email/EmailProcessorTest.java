package com.amfalmeida.mailhawk.email;

import com.amfalmeida.mailhawk.config.MailConfig;
import com.amfalmeida.mailhawk.model.Invoice;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Store;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("EmailProcessor Tests")
@ExtendWith(MockitoExtension.class)
class EmailProcessorTest {

    @Mock
    MailConfig mailConfig;

    @Mock
    EmailClient emailClient;

    @Mock
    Store store;

    @Mock
    Folder folder;

    @Mock
    Message message;

    EmailProcessor emailProcessor;

    @BeforeEach
    void setUp() {
        when(mailConfig.messageCacheSize()).thenReturn(10_000L);
        when(mailConfig.messageCacheExpireSeconds()).thenReturn(3600);
        emailProcessor = new EmailProcessor(mailConfig, emailClient);
        emailProcessor.initCache();
    }

    private void stubConnectedFolder() throws Exception {
        when(emailClient.isConnected()).thenReturn(true);
        when(emailClient.getStore()).thenReturn(store);
        when(store.getFolder("INBOX")).thenReturn(folder);
        when(mailConfig.folder()).thenReturn("INBOX");
    }

    private Invoice newTestInvoice() {
        return new Invoice(
            "id-1",
            "Fatura - Example Store",
            "from@example.com",
            "Sender",
            "to@example.com",
            LocalDate.now(),
            "invoice.pdf",
            "/tmp/invoice.pdf",
            null,
            null
        );
    }

    @Nested
    @DisplayName("Email check pipeline")
    class EmailCheckPipelineTests {

        @Test
        @DisplayName("Should search, extract and return invoices while firing callbacks")
        void shouldProcessMessagesAndInvokeCallbacks() throws Exception {
            stubConnectedFolder();
            final Invoice invoice = newTestInvoice();
            when(emailClient.searchMessages(eq(folder), any())).thenReturn(new Message[]{message});
            when(emailClient.filterBySubject(any())).thenReturn(new Message[]{message});
            when(emailClient.getMessageId(message)).thenReturn("msg-1");
            when(mailConfig.onlyAttachments()).thenReturn(true);
            when(emailClient.hasAttachments(message)).thenReturn(true);
            when(emailClient.extractInvoices(message)).thenReturn(List.of(invoice));

            final AtomicInteger invoiceCalls = new AtomicInteger();
            final AtomicInteger processCalls = new AtomicInteger();

            final List<Invoice> result = emailProcessor.checkAndProcessEmails(
                inv -> invoiceCalls.incrementAndGet(),
                inv -> processCalls.incrementAndGet());

            assertEquals(1, result.size());
            assertEquals(invoice, result.get(0));
            assertEquals(1, invoiceCalls.get(), "invoiceCallback should fire once");
            assertEquals(1, processCalls.get(), "processCallback should fire once");
        }

        @Test
        @DisplayName("Should return empty when connection cannot be established")
        void shouldReturnEmptyWhenNotConnected() {
            when(emailClient.isConnected()).thenReturn(false);
            when(emailClient.connect()).thenReturn(false);

            final List<Invoice> result = emailProcessor.checkAndProcessEmails(inv -> {}, inv -> {});

            assertTrue(result.isEmpty());
            verify(emailClient, never()).getStore();
        }

        @Test
        @DisplayName("Should close the opened folder after processing")
        void shouldCloseFolderAfterProcessing() throws Exception {
            stubConnectedFolder();
            when(folder.isOpen()).thenReturn(true);
            when(emailClient.searchMessages(eq(folder), any())).thenReturn(new Message[0]);
            when(emailClient.filterBySubject(any())).thenReturn(new Message[0]);

            emailProcessor.checkAndProcessEmails(inv -> {}, inv -> {});

            verify(folder).close(false);
        }

        @Test
        @DisplayName("Should return empty and swallow errors when the search fails")
        void shouldHandleSearchFailure() throws Exception {
            stubConnectedFolder();
            when(emailClient.searchMessages(eq(folder), any()))
                .thenThrow(new MessagingException("search failed"));

            final List<Invoice> result = emailProcessor.checkAndProcessEmails(inv -> {}, inv -> {});

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("Duplicate prevention")
    class DuplicatePreventionTests {

        @Test
        @DisplayName("Should not process the same message twice across runs")
        void shouldSkipAlreadyProcessedMessages() throws Exception {
            stubConnectedFolder();
            final Invoice invoice = newTestInvoice();
            when(emailClient.searchMessages(eq(folder), any())).thenReturn(new Message[]{message});
            when(emailClient.filterBySubject(any())).thenReturn(new Message[]{message});
            when(emailClient.getMessageId(message)).thenReturn("msg-1");
            when(mailConfig.onlyAttachments()).thenReturn(true);
            when(emailClient.hasAttachments(message)).thenReturn(true);
            when(emailClient.extractInvoices(message)).thenReturn(List.of(invoice));

            final List<Invoice> first = emailProcessor.checkAndProcessEmails(inv -> {}, inv -> {});
            final List<Invoice> second = emailProcessor.checkAndProcessEmails(inv -> {}, inv -> {});

            assertEquals(1, first.size(), "First run should process the message");
            assertTrue(second.isEmpty(), "Second run should skip the already processed message");
            verify(emailClient, times(1)).extractInvoices(message);
        }
    }

    @Nested
    @DisplayName("Attachment gate")
    class AttachmentGateTests {

        @Test
        @DisplayName("Should skip messages without attachments when onlyAttachments is enabled")
        void shouldSkipMessageWithoutAttachments() throws Exception {
            stubConnectedFolder();
            when(mailConfig.onlyAttachments()).thenReturn(true);
            when(emailClient.searchMessages(eq(folder), any())).thenReturn(new Message[]{message});
            when(emailClient.filterBySubject(any())).thenReturn(new Message[]{message});
            when(emailClient.getMessageId(message)).thenReturn("msg-1");
            when(emailClient.hasAttachments(message)).thenReturn(false);

            final AtomicInteger processCalls = new AtomicInteger();
            final List<Invoice> result = emailProcessor.checkAndProcessEmails(
                inv -> {},
                inv -> processCalls.incrementAndGet());

            assertTrue(result.isEmpty());
            assertEquals(0, processCalls.get(), "processCallback should not fire");
            verify(emailClient, never()).extractInvoices(message);
        }

        @Test
        @DisplayName("Should process messages without attachments when onlyAttachments is disabled")
        void shouldProcessMessageWithoutAttachmentsWhenGateDisabled() throws Exception {
            stubConnectedFolder();
            when(mailConfig.onlyAttachments()).thenReturn(false);
            final Invoice invoice = newTestInvoice();
            when(emailClient.searchMessages(eq(folder), any())).thenReturn(new Message[]{message});
            when(emailClient.filterBySubject(any())).thenReturn(new Message[]{message});
            when(emailClient.getMessageId(message)).thenReturn("msg-1");
            when(emailClient.extractInvoices(message)).thenReturn(List.of(invoice));

            final List<Invoice> result = emailProcessor.checkAndProcessEmails(inv -> {}, inv -> {});

            assertEquals(1, result.size(), "Message should be processed even without attachments");
        }
    }
}