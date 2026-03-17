package com.amfalmeida.mailhawk.service;

import com.amfalmeida.mailhawk.model.Invoice;
import com.amfalmeida.mailhawk.model.InvoiceType;
import com.amfalmeida.mailhawk.model.QrCodeContent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Invoice Processing Tests")
class InvoiceProcessorTest {

    @TempDir
    Path tempDir;

    private QrCodeParser qrCodeParser;

    @BeforeEach
    void setUp() {
        qrCodeParser = new QrCodeParser();
    }

    @Nested
    @DisplayName("QR Code Extraction and Invoice Creation")
    class QrCodeExtractionTests {

        @Test
        @DisplayName("Should extract QR code from PDF and create invoice")
        void shouldExtractQrCodeFromPdfAndCreateInvoice() throws Exception {
            final File pdfFile = copyResourceToTemp("fixtures/qr_code_example.pdf", "test_invoice.pdf");

            final List<String> qrCodes = qrCodeParser.getQrCodes(pdfFile.getAbsolutePath(), List.of());
            assertFalse(qrCodes.isEmpty(), "PDF should contain at least one QR code");

            final QrCodeContent qrContent = qrCodeParser.parseQrCodeString(qrCodes.get(0));

            assertNotNull(qrContent, "QR code should be parseable");
            assertNotNull(qrContent.getIssuerTin(), "Should have issuer TIN");
            assertNotNull(qrContent.getInvoiceId(), "Should have invoice ID");

            final Invoice invoice = new Invoice(
                "test-msg-id",
                "Test Invoice Email",
                "sender@company.com",
                "Sender Company",
                "recipient@company.com",
                LocalDate.now(),
                pdfFile.getName(),
                pdfFile.getAbsolutePath(),
                qrContent,
                new InvoiceType("invoice", "sender@company.com", "Sender", qrContent.getIssuerTin())
            );

            assertEquals(qrContent, invoice.getQrCode());
            assertEquals(pdfFile.getName(), invoice.getFilename());
        }

        @Test
        @DisplayName("Should process PDF with QR code and extract invoice data")
        void shouldProcessPdfWithQrCode() throws Exception {
            final File pdfFile = copyResourceToTemp("fixtures/qr_code_example_one_page.pdf", "single_page.pdf");

            final List<String> qrCodes = qrCodeParser.getQrCodes(pdfFile.getAbsolutePath(), List.of());
            assertFalse(qrCodes.isEmpty(), "Should find QR code in single page PDF");

            final QrCodeContent content = qrCodeParser.parseQrCodeString(qrCodes.get(0));

            assertNotNull(content.getIssuerTin(), "Should extract issuer TIN");
            assertNotNull(content.getInvoiceId(), "Should extract invoice ID");
            assertNotNull(content.getTotal(), "Should extract total");

            final Invoice invoice = createInvoiceFromQr(content, pdfFile);
            assertNotNull(invoice);
            assertEquals(content.getIssuerTin(), invoice.getQrCode().getIssuerTin());
        }

        @Test
        @DisplayName("Should handle encrypted PDF with correct password")
        void shouldHandleEncryptedPdfWithPassword() throws Exception {
            final File encPdf = copyResourceToTemp("fixtures/qr_code_example_one_page_enc.pdf", "encrypted.pdf");

            List<String> qrCodes = qrCodeParser.getQrCodes(encPdf.getAbsolutePath(), List.of());
            assertTrue(qrCodes.isEmpty(), "Encrypted PDF without password should return empty");

            qrCodes = qrCodeParser.getQrCodes(encPdf.getAbsolutePath(), List.of("test"));
        }

        @Test
        @DisplayName("Should extract QR code from image")
        void shouldExtractQrCodeFromImage() throws Exception {
            final File imageFile = copyResourceToTemp("fixtures/qr_code_example_image.png", "qr_image.png");

            final List<String> qrCodes = qrCodeParser.getQrCodes(imageFile.getAbsolutePath(), List.of());
            assertFalse(qrCodes.isEmpty(), "Should find QR code in image");

            final QrCodeContent content = qrCodeParser.parseQrCodeString(qrCodes.get(0));

            assertNotNull(content.getIssuerTin(), "Should extract issuer TIN from image QR");
        }
    }

    @Nested
    @DisplayName("QR Code Content Validation")
    class QrCodeContentValidationTests {

        @Test
        @DisplayName("Should parse Portuguese ATCUD format correctly")
        void shouldParsePortugueseAtcudFormat() {
            final String qrString = "A:507957129*B:123456789*C:PT*D:FT*E:N*F:20260315*G:FT 0123/45678*" +
                "H:ABC-123*I1:PT*I7:23*I8:100.50*L:0*M:0*N:23.12*O:123.62*P:0*Q:hash*R:0001*S:Ref";

            final QrCodeContent content = qrCodeParser.parseQrCodeString(qrString);

            assertEquals("507957129", content.getIssuerTin());
            assertEquals("123456789", content.getCustomerTin());
            assertEquals("PT", content.getCustomerCountry());
            assertEquals("FT", content.getInvoiceType());
            assertEquals("N", content.getStatus());
            assertEquals("2026-03-15", content.getInvoiceDate());
            assertEquals("FT 0123/45678", content.getInvoiceId());
            assertEquals("ABC-123", content.getAtcud());
            assertEquals(new BigDecimal("123.62"), content.getTotal());
            assertEquals(new BigDecimal("23.12"), content.getTotalTaxes());

            assertNotNull(content.getFirstTaxable());
            assertEquals(new BigDecimal("100.50"), content.getFirstTaxable().totalTaxesStandardRate());
        }

        @Test
        @DisplayName("Should create complete invoice from QR content")
        void shouldCreateCompleteInvoiceFromQrContent() {
            final QrCodeContent content = createTestQrContent();

            final Invoice invoice = new Invoice(
                "msg-123",
                "Fatura Teste",
                "vendor@company.pt",
                "Vendor Lda",
                "buyer@company.pt",
                LocalDate.now(),
                "invoice_test.pdf",
                "/tmp/invoice_test.pdf",
                content,
                new InvoiceType("invoice", "vendor@company.pt", "Vendor Lda", "507957129")
            );

            assertNotNull(invoice);
            assertEquals("msg-123", invoice.getId());
            assertEquals("Fatura Teste", invoice.getSubject());
            assertEquals("vendor@company.pt", invoice.getFromAddress());
            assertEquals(content, invoice.getQrCode());
            assertEquals("507957129", invoice.getQrCode().getIssuerTin());
            assertEquals(new BigDecimal("100.00"), invoice.getQrCode().getTotal());
        }
    }

    @Nested
    @DisplayName("Invoice Processing Pipeline")
    class InvoiceProcessingPipelineTests {

        @Test
        @DisplayName("Should process invoice through complete pipeline")
        void shouldProcessInvoiceThroughCompletePipeline() throws Exception {
            final File pdfFile = copyResourceToTemp("fixtures/qr_code_example.pdf", "pipeline_test.pdf");
            
            // Step 1: Extract QR code
            final List<String> qrCodes = qrCodeParser.getQrCodes(pdfFile.getAbsolutePath(), List.of());
            assertFalse(qrCodes.isEmpty(), "Should find QR code");

            // Step 2: Parse QR content
            final QrCodeContent qrContent = qrCodeParser.parseQrCodeString(qrCodes.get(0));
            assertNotNull(qrContent.getIssuerTin());
            assertNotNull(qrContent.getInvoiceId());

            // Step 3: Create invoice
            final Invoice invoice = createInvoiceFromQr(qrContent, pdfFile);

            // Step 4: Validate invoice
            assertNotNull(invoice.getId());
            assertNotNull(invoice.getQrCode());
            assertEquals(qrContent.getIssuerTin(), invoice.getQrCode().getIssuerTin());
            assertEquals(qrContent.getInvoiceId(), invoice.getQrCode().getInvoiceId());
            assertEquals(qrContent.getTotal(), invoice.getQrCode().getTotal());
            assertNotNull(invoice.getFromAddress());
            assertNotNull(invoice.getFilename());
            assertNotNull(invoice.getFilePath());
            assertNotNull(invoice.getDate());
        }

        @Test
        @DisplayName("Should validate tax calculations from QR content")
        void shouldValidateTaxCalculationsFromQrContent() {
            final String qrString = "A:123*B:456*C:PT*D:FT*E:N*F:20260315*G:INV-001*H:ATCUD*" +
                "I1:PT*I7:23*I8:100*L:50*N:23*O:73*P:0*Q:hash*R:cert";

            final QrCodeContent content = qrCodeParser.parseQrCodeString(qrString);

            assertNotNull(content.getFirstTaxable(), "Should have first taxable");
            assertEquals(new BigDecimal("23"), content.getFirstTaxable().basicsStandardRate());
            assertEquals(new BigDecimal("100"), content.getFirstTaxable().totalTaxesStandardRate());
            assertEquals(new BigDecimal("73"), content.getTotal());
            assertEquals(new BigDecimal("23"), content.getTotalTaxes());
            assertEquals(new BigDecimal("50"), content.getNonTaxable());
        }

        @Test
        @DisplayName("Should handle invoice with multiple taxable groups")
        void shouldHandleInvoiceWithMultipleTaxableGroups() {
            final String qrString = "A:507957129*B:*C:PT*D:FS*E:N*F:20260315*G:FS 001*H:ATCUD*" +
                "I1:PT*I7:23*I8:50*J1:PT*J5:13*J6:100*L:0*N:34.50*O:150*P:0*Q:hash*R:cert";

            final QrCodeContent content = qrCodeParser.parseQrCodeString(qrString);

            assertNotNull(content.getFirstTaxable());
            assertEquals(new BigDecimal("50"), content.getFirstTaxable().totalTaxesStandardRate());

            assertNotNull(content.getSecondTaxable());
            assertEquals(new BigDecimal("100"), content.getSecondTaxable().totalTaxesIntermediateRate());

            assertEquals(new BigDecimal("150"), content.getTotal());
            assertEquals(new BigDecimal("34.50"), content.getTotalTaxes());
        }
    }

    private QrCodeContent createTestQrContent() {
        final QrCodeContent content = new QrCodeContent();
        content.setIssuerTin("507957129");
        content.setCustomerTin("123456789");
        content.setCustomerCountry("PT");
        content.setInvoiceType("FT");
        content.setStatus("N");
        content.setInvoiceDate("2026-03-15");
        content.setInvoiceId("FT 0123/45678");
        content.setAtcud("ABC-123");
        content.setTotal(new BigDecimal("100.00"));
        content.setTotalTaxes(new BigDecimal("23.00"));
        content.setNonTaxable(BigDecimal.ZERO);
        content.setStampDuty(BigDecimal.ZERO);
        content.setWithholdingTax(BigDecimal.ZERO);
        content.setHash("test-hash");
        content.setCertificateNumber("0001");
        content.setRaw("A:507957129*B:123456789*C:PT*D:FT*E:N*F:20260315*G:FT 0123/45678*O:100");
        return content;
    }

    private Invoice createInvoiceFromQr(final QrCodeContent qrContent, final File file) {
        return new Invoice(
            "msg-" + System.currentTimeMillis(),
            "Invoice from " + qrContent.getIssuerTin(),
            qrContent.getIssuerTin() + "@example.com",
            "Sender",
            "recipient@example.com",
            LocalDate.now(),
            file.getName(),
            file.getAbsolutePath(),
            qrContent,
            new InvoiceType("invoice", qrContent.getIssuerTin() + "@example.com", "Sender", qrContent.getIssuerTin())
        );
    }

    private File copyResourceToTemp(final String resourcePath, final String targetName) throws IOException {
        final Path targetPath = tempDir.resolve(targetName);
        final Path sourcePath = Path.of("src/test/resources", resourcePath);
        Files.copy(sourcePath, targetPath);
        return targetPath.toFile();
    }
}