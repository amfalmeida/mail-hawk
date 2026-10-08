package com.amfalmeida.mailhawk.email;

import com.amfalmeida.mailhawk.config.MailConfig;
import com.amfalmeida.mailhawk.model.Invoice;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@DisplayName("EmailClient Tests")
@ExtendWith(MockitoExtension.class)
class EmailClientTest {

    @Mock
    MailConfig mailConfig;

    EmailClient emailClient;

    @BeforeEach
    void setUp() {
        lenient().when(mailConfig.subjectTerms()).thenReturn(null);
        emailClient = new EmailClient(mailConfig);
    }

    private static final String MESSAGE_ID = "<invoice-2026-0001@example.com>";

    /**
     * Faithful replica of a typical vendor invoice email: the PDF part has a
     * {@code Content-Type} {@code name=} parameter but NO {@code Content-Disposition}
     * header at all. Also contains an inline logo image.
     */
    private static final String REPLICA_EMAIL = """
        MIME-Version: 1.0
        Date: Sat, 26 Sep 2026 13:06:48 -0700
        Message-ID: <invoice-2026-0001@example.com>
        From: Invoice Store <billing@example.com>
        To: customer@example.com
        Subject: Fatura - Example Store
        Content-Type: multipart/related; boundary="boundary_1127"

        --boundary_1127
        Content-Type: text/html; charset=utf-8
        Content-Transfer-Encoding: base64

        PGh0bWw+
        --boundary_1127
        Content-Type: image/png; name=logo.png
        Content-Transfer-Encoding: base64
        Content-ID: <LOGO>

        iVBORw0KGgo=
        --boundary_1127
        Content-Type: application/pdf; name="FT 0000001.pdf"
        Content-Transfer-Encoding: base64

        JVBERi0xLjM=
        --boundary_1127--
        """;

    /** Same shape as the replica but without the inline image: exactly one PDF. */
    private static final String EMAIL_PDF_ONLY_NO_DISPOSITION = """
        MIME-Version: 1.0
        Date: Sat, 26 Sep 2026 13:06:48 -0700
        Message-ID: <invoice-2026-0001@example.com>
        From: Invoice Store <billing@example.com>
        To: customer@example.com
        Subject: Fatura - Example Store
        Content-Type: multipart/related; boundary="boundary_1127"

        --boundary_1127
        Content-Type: text/html; charset=utf-8
        Content-Transfer-Encoding: base64

        PGh0bWw+
        --boundary_1127
        Content-Type: application/pdf; name="FT 0000001.pdf"
        Content-Transfer-Encoding: base64

        JVBERi0xLjM=
        --boundary_1127--
        """;

    private static final String EMAIL_WITH_PDF_AND_DISPOSITION = """
        MIME-Version: 1.0
        Date: Fri, 02 Oct 2026 10:00:00 +0100
        Message-ID: <msg-with-disposition@example.com>
        From: sender@example.com
        To: recipient@example.com
        Subject: Fatura with attachment
        Content-Type: multipart/mixed; boundary="mixed_01"

        --mixed_01
        Content-Type: text/plain; charset=utf-8
        Content-Transfer-Encoding: 7bit

        Hello

        --mixed_01
        Content-Type: application/pdf; name="invoice.pdf"
        Content-Transfer-Encoding: base64
        Content-Disposition: attachment; filename="invoice.pdf"

        JVBERi0xLjM=
        --mixed_01--
        """;

    private static final String EMAIL_PLAIN_TEXT = """
        MIME-Version: 1.0
        Date: Fri, 02 Oct 2026 10:00:00 +0100
        Message-ID: <plain@example.com>
        From: sender@example.com
        To: recipient@example.com
        Subject: Just a note
        Content-Type: text/plain; charset=utf-8

        Hello, no attachments here.
        """;

    private static final String EMAIL_WITH_UNSUPPORTED_ATTACHMENT = """
        MIME-Version: 1.0
        Date: Fri, 02 Oct 2026 10:00:00 +0100
        Message-ID: <txt-attachment@example.com>
        From: sender@example.com
        To: recipient@example.com
        Subject: Fatura - notes
        Content-Type: multipart/mixed; boundary="mixed_02"

        --mixed_02
        Content-Type: text/plain; charset=utf-8
        Content-Transfer-Encoding: 7bit

        Hello

        --mixed_02
        Content-Type: text/plain; name="notes.txt"
        Content-Transfer-Encoding: 7bit
        Content-Disposition: attachment; filename="notes.txt"

        Buy milk

        --mixed_02--
        """;

    private static MimeMessage parseMessage(final String raw) throws Exception {
        final Session session = Session.getInstance(new Properties());
        final String normalized = raw.replace("\r\n", "\n").replace("\n", "\r\n");
        return new MimeMessage(session, new ByteArrayInputStream(normalized.getBytes(StandardCharsets.UTF_8)));
    }

    private static void assertExtractedPdfInvoice(final Invoice invoice) throws Exception {
        assertEquals(MESSAGE_ID, invoice.getId());
        assertEquals("Fatura - Example Store", invoice.getSubject());
        assertEquals("billing@example.com", invoice.getFromAddress());
        assertEquals("Invoice Store", invoice.getFromName());
        assertEquals("customer@example.com", invoice.getToAddress());
        assertEquals(LocalDate.parse("2026-09-26"), invoice.getDate());
        assertEquals("FT 0000001.pdf", invoice.getFilename());

        assertNotNull(invoice.getFilePath());
        final File file = new File(invoice.getFilePath());
        assertTrue(file.exists(), "Attachment should be saved to a temp file");
        assertTrue(file.length() > 0, "Saved attachment should not be empty");
        assertEquals("%PDF-1.3", Files.readString(file.toPath()), "PDF content should be preserved");
    }

    @Nested
    @DisplayName("Attachment detection")
    class AttachmentDetectionTests {

        @Test
        @DisplayName("Should detect PDF with no Content-Disposition header (name parameter only)")
        void shouldDetectPdfWithoutContentDisposition() throws Exception {
            final Message msg = parseMessage(REPLICA_EMAIL);
            assertTrue(emailClient.hasAttachments(msg),
                "PDF part with only Content-Type name= must be detected as an attachment");
        }

        @Test
        @DisplayName("Should detect PDF with explicit Content-Disposition attachment")
        void shouldDetectPdfWithDisposition() throws Exception {
            final Message msg = parseMessage(EMAIL_WITH_PDF_AND_DISPOSITION);
            assertTrue(emailClient.hasAttachments(msg));
        }

        @Test
        @DisplayName("Should return false for plain text message without attachments")
        void shouldReturnFalseForPlainTextMessage() throws Exception {
            final Message msg = parseMessage(EMAIL_PLAIN_TEXT);
            assertFalse(emailClient.hasAttachments(msg));
        }

        @Test
        @DisplayName("Should return false when attachment type is not supported")
        void shouldReturnFalseForUnsupportedAttachmentType() throws Exception {
            final Message msg = parseMessage(EMAIL_WITH_UNSUPPORTED_ATTACHMENT);
            assertFalse(emailClient.hasAttachments(msg), "'.txt' attachments are not supported");
        }
    }

    @Nested
    @DisplayName("Invoice extraction")
    class InvoiceExtractionTests {

        @Test
        @DisplayName("Should extract invoice from PDF without Content-Disposition")
        void shouldExtractInvoiceFromPdfWithoutDisposition() throws Exception {
            final Message msg = parseMessage(EMAIL_PDF_ONLY_NO_DISPOSITION);

            final List<Invoice> invoices = emailClient.extractInvoices(msg);

            assertEquals(1, invoices.size(), "Expected exactly one invoice for the PDF attachment");
            assertExtractedPdfInvoice(invoices.get(0));
        }

        @Test
        @DisplayName("Should extract PDF and inline image from the vendor replica email")
        void shouldExtractPdfFromReplicaEmail() throws Exception {
            final Message msg = parseMessage(REPLICA_EMAIL);

            final List<Invoice> invoices = emailClient.extractInvoices(msg);

            assertEquals(2, invoices.size(),
                "logo.png and FT 0000001.pdf are both saved as supported attachments");
            final Invoice pdfInvoice = invoices.stream()
                .filter(inv -> "FT 0000001.pdf".equals(inv.getFilename()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected invoice for FT 0000001.pdf"));
            assertExtractedPdfInvoice(pdfInvoice);
        }

        @Test
        @DisplayName("Should extract invoice from PDF with explicit disposition")
        void shouldExtractInvoiceFromPdfWithDisposition() throws Exception {
            final Message msg = parseMessage(EMAIL_WITH_PDF_AND_DISPOSITION);

            final List<Invoice> invoices = emailClient.extractInvoices(msg);

            assertEquals(1, invoices.size());
            assertEquals("invoice.pdf", invoices.get(0).getFilename());
        }

        @Test
        @DisplayName("Should return empty list for plain text message")
        void shouldReturnEmptyForPlainTextMessage() throws Exception {
            final Message msg = parseMessage(EMAIL_PLAIN_TEXT);

            final List<Invoice> invoices = emailClient.extractInvoices(msg);

            assertTrue(invoices.isEmpty());
        }

        @Test
        @DisplayName("Should skip unsupported attachment types")
        void shouldSkipUnsupportedAttachmentTypes() throws Exception {
            final Message msg = parseMessage(EMAIL_WITH_UNSUPPORTED_ATTACHMENT);

            final List<Invoice> invoices = emailClient.extractInvoices(msg);

            assertTrue(invoices.isEmpty(), "'.txt' attachments must not generate invoices");
        }
    }

    @Nested
    @DisplayName("Subject filtering")
    class SubjectFilteringTests {

        @Test
        @DisplayName("Should keep only messages whose subject matches configured terms")
        void shouldKeepOnlyMatchingSubjects() throws Exception {
            when(mailConfig.subjectTerms()).thenReturn(List.of("fatura", "factura"));

            final Message matching1 = newMessageWithSubject("Fatura - Example Store");
            final Message notMatching = newMessageWithSubject("Weekly newsletter");
            final Message matching2 = newMessageWithSubject("factura eletrica");

            final Message[] result = emailClient.filterBySubject(
                new Message[]{matching1, notMatching, matching2});

            assertEquals(2, result.length);
        }

        @Test
        @DisplayName("Should keep all messages when no subject terms are configured")
        void shouldKeepAllMessagesWithoutSubjectTerms() throws Exception {
            final Message m1 = newMessageWithSubject("Fatura - Example Store");
            final Message m2 = newMessageWithSubject("Anything at all");

            final Message[] result = emailClient.filterBySubject(new Message[]{m1, m2});

            assertEquals(2, result.length);
        }

        @Test
        @DisplayName("Should filter case-insensitively")
        void shouldFilterCaseInsensitively() throws Exception {
            when(mailConfig.subjectTerms()).thenReturn(List.of("Fatura"));

            final Message matching = newMessageWithSubject("FATURA electricity");
            final Message notMatching = newMessageWithSubject("newsletter");

            final Message[] result = emailClient.filterBySubject(new Message[]{matching, notMatching});

            assertEquals(1, result.length);
            assertEquals(matching, result[0]);
        }

        private Message newMessageWithSubject(final String subject) throws Exception {
            final MimeMessage msg = new MimeMessage(Session.getInstance(new Properties()));
            msg.setSubject(subject);
            return msg;
        }
    }

    @Nested
    @DisplayName("Message id resolution")
    class MessageIdTests {

        @Test
        @DisplayName("Should return the Message-ID header when present")
        void shouldReturnMessageIdHeader() throws Exception {
            final Message msg = parseMessage(REPLICA_EMAIL);

            assertEquals(MESSAGE_ID, emailClient.getMessageId(msg));
        }

        @Test
        @DisplayName("Should generate a UUID when Message-ID header is missing")
        void shouldGenerateUuidWhenHeaderMissing() throws Exception {
            final Message msg = parseMessage(EMAIL_PLAIN_TEXT.replaceAll("(?m)^Message-ID:.*\\n", ""));

            assertNotNull(emailClient.getMessageId(msg));
            assertNotEquals("", emailClient.getMessageId(msg));
        }
    }
}