package com.amfalmeida.mailhawk.service;

import com.amfalmeida.mailhawk.config.MailConfig;
import com.amfalmeida.mailhawk.email.model.Invoice;
import com.amfalmeida.mailhawk.email.model.InvoiceContent;
import com.amfalmeida.mailhawk.email.model.InvoiceResult;
import com.amfalmeida.mailhawk.service.InvoiceRequestException.Reason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiInvoiceServiceTest {

    private static final String RAW = "A:123456789*B:999999990*C:PT*D:FT*E:N*F:20261001*G:FT 2026/123"
        + "*H:ABCD1234-123*I1:PT*I7:10.00*I8:2.30*N:2.30*O:12.30*Q:abcd*R:1234";
    private static final LocalDate DATE = LocalDate.of(2026, 10, 2);

    private static final String PDF = "src/test/resources/fixtures/qr_code_example_one_page.pdf";
    private static final String IMAGE = "src/test/resources/fixtures/qr_code_example_image.png";

    private InvoiceProcessor invoiceProcessor;
    private QrCodeParser qrCodeParser;
    private ApiInvoiceService service;

    @BeforeEach
    void setUp() {
        invoiceProcessor = mock(InvoiceProcessor.class);
        qrCodeParser = spy(new QrCodeParser());
        final MailConfig mailConfig = mock(MailConfig.class);
        when(mailConfig.pdfPasswords()).thenReturn(List.of());
        service = new ApiInvoiceService(invoiceProcessor, qrCodeParser, mailConfig);
    }

    @Test
    void processQrCodeRejectsMissingFieldsAndInvalidRaw() {
        assertReason(Reason.INVALID_INPUT, () -> service.processQrCode(null, DATE, "Water Co", "Fatura"));
        assertReason(Reason.INVALID_INPUT, () -> service.processQrCode(" ", DATE, "Water Co", "Fatura"));
        assertReason(Reason.INVALID_INPUT, () -> service.processQrCode(RAW, null, "Water Co", "Fatura"));
        assertReason(Reason.INVALID_INPUT, () -> service.processQrCode("not a qr code", DATE, "Water Co", "Fatura"));
        assertReason(Reason.INVALID_INPUT, () -> service.processQrCode("A:123456789*O:12.30", DATE, "Water Co", null));
        verify(invoiceProcessor, never()).storeInvoice(any(), any());
    }

    @Test
    void processQrCodeParsesInvoiceDataFromRaw() {
        when(invoiceProcessor.storeInvoice(any(), anyString())).thenReturn(result(InvoiceResult.Status.APPENDED));

        final InvoiceResult result = service.processQrCode(RAW, DATE, "Water Co", "Fatura FT 2026/123");

        assertEquals(InvoiceResult.Status.APPENDED, result.status());
        final Invoice invoice = captureInvoice();
        final InvoiceContent content = invoice.getInvoiceContent();
        assertEquals("123456789", content.getIssuerTin());
        assertEquals("999999990", content.getCustomerTin());
        assertEquals("FT 2026/123", content.getInvoiceId());
        assertEquals("ABCD1234-123", content.getAtcud());
        assertEquals("2026-10-01", content.getInvoiceDate());
        assertEquals(new BigDecimal("12.30"), content.getTotal());
        assertEquals(new BigDecimal("10.00"), content.getFirstTaxable().basicsStandardRate());
        assertEquals(RAW, content.getRaw());
        assertEquals(DATE, invoice.getDate());
        assertEquals("Water Co", invoice.getFromName());
        assertEquals("Fatura FT 2026/123", invoice.getSubject());
        assertTrue(invoice.getId().startsWith("api-"));
    }

    @Test
    void processQrCodeUsesRequestDateWhenRawHasNoInvoiceDate() {
        when(invoiceProcessor.storeInvoice(any(), anyString())).thenReturn(result(InvoiceResult.Status.APPENDED));

        service.processQrCode("A:123456789*G:FT 1/1*O:5.00", DATE, "Water Co", null);

        assertEquals("2026-10-02", captureInvoice().getInvoiceContent().getInvoiceDate());
    }

    @Test
    void processQrCodeMakesDescriptionOptional() {
        when(invoiceProcessor.storeInvoice(any(), anyString())).thenReturn(result(InvoiceResult.Status.APPENDED));

        service.processQrCode(RAW, DATE, "Water Co", null);

        assertEquals("", captureInvoice().getSubject());
    }

    @Test
    void processFileRejectsMissingFieldsInvalidBase64AndUnsupportedTypes() throws IOException {
        assertReason(Reason.INVALID_INPUT, () -> service.processFile(" ", DATE, "Water Co", null));
        assertReason(Reason.INVALID_INPUT, () -> service.processFile(fileBase64(PDF), null, "Water Co", null));
        assertReason(Reason.INVALID_INPUT, () -> service.processFile("%%% not base64 %%%", DATE, "Water Co", null));
        assertReason(Reason.INVALID_INPUT, () -> service.processFile(base64("not a pdf"), DATE, "Water Co", null));
        verify(invoiceProcessor, never()).storeInvoice(any(), any());
    }

    @Test
    void processFileStoresInvoiceAndDeletesTempFile() throws IOException {
        doReturn(List.of(RAW)).when(qrCodeParser).getQrCodes(anyString(), anyList());
        when(invoiceProcessor.storeInvoice(any(), eq("Water Co"))).thenReturn(result(InvoiceResult.Status.APPENDED));

        final List<InvoiceResult> results = service.processFile(fileBase64(PDF), DATE, "Water Co", "Fatura");

        assertEquals(1, results.size());
        final ArgumentCaptor<Invoice> captor = ArgumentCaptor.forClass(Invoice.class);
        verify(invoiceProcessor).storeInvoice(captor.capture(), eq("Water Co"));
        final Invoice invoice = captor.getValue();
        assertEquals("FT 2026/123", invoice.getInvoiceContent().getInvoiceId());
        assertEquals("2026-10-01", invoice.getInvoiceContent().getInvoiceDate());
        assertEquals("Fatura", invoice.getSubject());
        assertEquals("Water Co", invoice.getFromName());
        assertEquals("invoice.pdf", invoice.getFilename());
        assertEquals(DATE, invoice.getDate());
        assertTrue(invoice.getId().startsWith("api-"));
        assertTempFileRemoved();
    }

    @Test
    void processFileStoresEveryQrCodeFound() throws IOException {
        doReturn(List.of(RAW, RAW)).when(qrCodeParser).getQrCodes(anyString(), anyList());
        when(invoiceProcessor.storeInvoice(any(), any()))
            .thenReturn(result(InvoiceResult.Status.APPENDED), result(InvoiceResult.Status.ALREADY_EXISTS));

        final List<InvoiceResult> results = service.processFile(fileBase64(PDF), DATE, null, null);

        assertEquals(2, results.size());
        verify(invoiceProcessor, times(2)).storeInvoice(any(), any());
    }

    @Test
    void processFileWithoutValidQrCodeThrowsNoQrCode() throws IOException {
        doReturn(List.of()).when(qrCodeParser).getQrCodes(anyString(), anyList());
        assertReason(Reason.NO_QR_CODE, () -> service.processFile(fileBase64(PDF), DATE, null, null));

        doReturn(List.of("not a qr code")).when(qrCodeParser).getQrCodes(anyString(), anyList());
        assertReason(Reason.NO_QR_CODE, () -> service.processFile(fileBase64(PDF), DATE, null, null));
        verify(invoiceProcessor, never()).storeInvoice(any(), any());
    }

    @Test
    void processFileAcceptsImages() throws IOException {
        doReturn(List.of(RAW)).when(qrCodeParser).getQrCodes(anyString(), anyList());
        when(invoiceProcessor.storeInvoice(any(), any())).thenReturn(result(InvoiceResult.Status.APPENDED));

        service.processFile(fileBase64(IMAGE), DATE, null, null);

        final ArgumentCaptor<Invoice> captor = ArgumentCaptor.forClass(Invoice.class);
        verify(invoiceProcessor).storeInvoice(captor.capture(), any());
        assertEquals("invoice.png", captor.getValue().getFilename());
        assertTempFileRemoved();
    }

    private void assertTempFileRemoved() throws IOException {
        final ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(qrCodeParser).getQrCodes(pathCaptor.capture(), anyList());
        assertFalse(new File(pathCaptor.getValue()).exists());
    }

    private static void assertReason(final Reason reason, final Executable call) {
        assertEquals(reason, assertThrows(InvoiceRequestException.class, call).getReason());
    }

    private static String fileBase64(final String path) throws IOException {
        return Base64.getEncoder().encodeToString(Files.readAllBytes(Path.of(path)));
    }

    private static String base64(final String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private Invoice captureInvoice() {
        final ArgumentCaptor<Invoice> captor = ArgumentCaptor.forClass(Invoice.class);
        verify(invoiceProcessor).storeInvoice(captor.capture(), eq("Water Co"));
        return captor.getValue();
    }

    private static InvoiceResult result(final InvoiceResult.Status status) {
        return new InvoiceResult("FT 2026/123", "123456789", new BigDecimal("12.30"), "other", status, "");
    }
}
