package com.amfalmeida.mailhawk.service;

import com.amfalmeida.mailhawk.model.InvoiceContent;
import com.amfalmeida.mailhawk.model.InvoiceContent.Taxable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("QrCodeParser Tests")
class QrCodeParserTest {

    private QrCodeParser parser;

    @BeforeEach
    void setUp() {
        parser = new QrCodeParser();
    }

    @Nested
    @DisplayName("Parse QR Code String")
    class ParseQrCodeStringTests {

        @Test
        @DisplayName("Should parse valid QR code string with all fields")
        void shouldParseValidQrCodeString() {
            final String qrString = "A:123456789*B:987654321*C:PT*D:FT*E:N*F:20260306*G:FR 0/99999*H:99999*L:0*M:0*N:0*O:10.00*P:0*Q:abc123*R:1234*S:Test";

            final InvoiceContent result = parser.parseQrCodeString(qrString);

            assertNotNull(result);
            assertEquals("123456789", result.getIssuerTin());
            assertEquals("987654321", result.getCustomerTin());
            assertEquals("PT", result.getCustomerCountry());
            assertEquals("FT", result.getInvoiceType());
            assertEquals("N", result.getStatus());
            assertEquals("2026-03-06", result.getInvoiceDate());
            assertEquals("FR 0/99999", result.getInvoiceId());
            assertEquals("99999", result.getAtcud());
            assertEquals(new BigDecimal("10.00"), result.getTotal());
            assertEquals(new BigDecimal("0"), result.getTotalTaxes());
            assertEquals(new BigDecimal("0"), result.getNonTaxable());
            assertEquals(new BigDecimal("0"), result.getStampDuty());
            assertEquals(new BigDecimal("0"), result.getWithholdingTax());
            assertEquals("abc123", result.getHash());
            assertEquals("1234", result.getCertificateNumber());
            assertEquals("Test", result.getOtherInformation());
            assertEquals(qrString, result.getRaw());
        }

        @Test
        @DisplayName("Should parse QR code with taxable fields (I group)")
        void shouldParseQrCodeWithTaxableFields() {
            final String qrString = "A:507957129*B:000000000*C:PT*D:FS*E:N*F:20260315*G:FR 0/1*H:ABC123*I1:PT*I2:0*I3:23*I4:0*I5:0*I6:0*I7:0*I8:0*L:0*M:0*N:0*O:100.00*P:0*Q:hash*R:cert*S:info";

            final InvoiceContent result = parser.parseQrCodeString(qrString);

            assertNotNull(result);
            assertEquals("507957129", result.getIssuerTin());
            assertEquals("PT", result.getCustomerCountry());
            assertEquals("FS", result.getInvoiceType());
            
            assertNotNull(result.getFirstTaxable());
            final Taxable taxable = result.getFirstTaxable();
            assertEquals("PT", taxable.taxCountryRegion());
            assertEquals(BigDecimal.ZERO, taxable.basicsExemptTaxes());
            assertEquals(new BigDecimal("23"), taxable.basicsReducedRate());
        }

        @Test
        @DisplayName("Should parse QR code with multiple taxable groups (I, J, K)")
        void shouldParseQrCodeWithMultipleTaxableGroups() {
            final String qrString = "A:123*B:456*C:ES*D:FT*E:N*F:20260101*G:INV1*H:ATCUD*I1:PT*I2:10*I3:13*I4:100*I5:0*I6:0*I7:0*I8:0*J1:ES*J2:0*J3:0*J4:0*J5:21*J6:200*J7:0*J8:0*L:50*M:0*N:150*O:200*P:0*Q:hash*R:cert*S:info";

            final InvoiceContent result = parser.parseQrCodeString(qrString);

            assertNotNull(result);
            
            assertNotNull(result.getFirstTaxable());
            assertEquals("PT", result.getFirstTaxable().taxCountryRegion());
            assertEquals(new BigDecimal("10"), result.getFirstTaxable().basicsExemptTaxes());
            assertEquals(new BigDecimal("13"), result.getFirstTaxable().basicsReducedRate());
            assertEquals(new BigDecimal("100"), result.getFirstTaxable().totalTaxesReducedRate());
            
            assertNotNull(result.getSecondTaxable());
            assertEquals("ES", result.getSecondTaxable().taxCountryRegion());
            assertEquals(new BigDecimal("21"), result.getSecondTaxable().basicsIntermediateRate());
            assertEquals(new BigDecimal("200"), result.getSecondTaxable().totalTaxesIntermediateRate());
            
            assertNull(result.getThirdTaxable());
        }

        @Test
        @DisplayName("Should handle invalid QR code string gracefully")
        void shouldHandleInvalidQrCodeString() {
            final String qrString = "invalid qr code without proper format";

            final InvoiceContent result = parser.parseQrCodeString(qrString);

            assertNotNull(result);
            assertNull(result.getIssuerTin());
            assertNull(result.getCustomerTin());
            assertEquals(qrString, result.getRaw());
        }

        @Test
        @DisplayName("Should parse partial QR code string")
        void shouldParsePartialQrCodeString() {
            final String qrString = "A:123456789*F:20260306*G:INV001";

            final InvoiceContent result = parser.parseQrCodeString(qrString);

            assertNotNull(result);
            assertEquals("123456789", result.getIssuerTin());
            assertEquals("2026-03-06", result.getInvoiceDate());
            assertEquals("INV001", result.getInvoiceId());
        }

        @Test
        @DisplayName("Should handle empty QR code string")
        void shouldHandleEmptyQrCodeString() {
            final InvoiceContent result = parser.parseQrCodeString("");

            assertNotNull(result);
            assertNull(result.getIssuerTin());
            assertEquals("", result.getRaw());
        }

        @Test
        @DisplayName("Should parse date in yyyy-MM-dd format")
        void shouldParseDateWithDashes() {
            final String qrString = "F:2026-03-15";

            final InvoiceContent result = parser.parseQrCodeString(qrString);

            assertEquals("2026-03-15", result.getInvoiceDate());
        }

        @Test
        @DisplayName("Should parse decimal amounts with comma separator")
        void shouldParseDecimalAmountsWithComma() {
            final String qrString = "O:10,50*N:2,30";

            final InvoiceContent result = parser.parseQrCodeString(qrString);

            assertEquals(new BigDecimal("10.50"), result.getTotal());
            assertEquals(new BigDecimal("2.30"), result.getTotalTaxes());
        }

        @Test
        @DisplayName("Should return null for missing amounts")
        void shouldReturnNullForMissingAmounts() {
            final String qrString = "A:123456789";

            final InvoiceContent result = parser.parseQrCodeString(qrString);

            assertNull(result.getTotal());
            assertNull(result.getTotalTaxes());
            assertNull(result.getNonTaxable());
        }
    }

    @Nested
    @DisplayName("Get QR Codes From Files")
    class GetQrCodesFromFileTests {

        private String fixturesDir;

        @BeforeEach
        void setUp() {
            final File resourcesDir = new File("src/test/resources/fixtures");
            fixturesDir = resourcesDir.exists() ? resourcesDir.getAbsolutePath() : null;
        }

        @Test
        @DisplayName("Should read QR codes from PDF file")
        void shouldReadQrCodesFromPdf() {
            if (fixturesDir == null) {
                return;
            }
            
            final String pdfPath = fixturesDir + "/qr_code_example.pdf";
            final File file = new File(pdfPath);
            if (!file.exists()) {
                return;
            }

            final List<String> qrCodes = parser.getQrCodes(pdfPath, List.of());

            assertNotNull(qrCodes);
            assertFalse(qrCodes.isEmpty(), "Expected at least one QR code in PDF");
            
            final InvoiceContent parsed = parser.parseQrCodeString(qrCodes.get(0));
            assertNotNull(parsed);
            assertNotNull(parsed.getIssuerTin());
        }

        @Test
        @DisplayName("Should read QR codes from image file (PNG)")
        void shouldReadQrCodesFromImage() {
            if (fixturesDir == null) {
                return;
            }
            
            final String imagePath = fixturesDir + "/qr_code_example_image.png";
            final File file = new File(imagePath);
            if (!file.exists()) {
                return;
            }

            final List<String> qrCodes = parser.getQrCodes(imagePath, List.of());

            assertNotNull(qrCodes);
            assertFalse(qrCodes.isEmpty(), "Expected at least one QR code in image");
            
            final InvoiceContent parsed = parser.parseQrCodeString(qrCodes.get(0));
            assertNotNull(parsed);
        }

        @Test
        @DisplayName("Should read QR codes from single page PDF")
        void shouldReadQrCodesFromSinglePagePdf() {
            if (fixturesDir == null) {
                return;
            }
            
            final String pdfPath = fixturesDir + "/qr_code_example_one_page.pdf";
            final File file = new File(pdfPath);
            if (!file.exists()) {
                return;
            }

            final List<String> qrCodes = parser.getQrCodes(pdfPath, List.of());

            assertNotNull(qrCodes);
        }

        @Test
        @DisplayName("Should return empty list for non-existent file")
        void shouldReturnEmptyForNonExistentFile() {
            final List<String> qrCodes = parser.getQrCodes("/nonexistent/path/file.pdf", List.of());

            assertNotNull(qrCodes);
            assertTrue(qrCodes.isEmpty());
        }

        @Test
        @DisplayName("Should return empty list for unsupported file format")
        void shouldReturnEmptyForUnsupportedFormat() {
            final List<String> qrCodes = parser.getQrCodes("test.txt", List.of());

            assertNotNull(qrCodes);
            assertTrue(qrCodes.isEmpty());
        }

        @Test
        @DisplayName("Should read encrypted PDF with correct password")
        void shouldReadEncryptedPdfWithPassword() {
            if (fixturesDir == null) {
                return;
            }
            
            final String pdfPath = fixturesDir + "/qr_code_example_one_page_enc.pdf";
            final File file = new File(pdfPath);
            if (!file.exists()) {
                return;
            }

            final List<String> qrCodes = parser.getQrCodes(pdfPath, List.of("test"));

            assertNotNull(qrCodes);
        }

        @Test
        @DisplayName("Should return empty list for encrypted PDF with wrong password")
        void shouldReturnEmptyForWrongPassword() {
            if (fixturesDir == null) {
                return;
            }
            
            final String pdfPath = fixturesDir + "/qr_code_example_one_page_enc.pdf";
            final File file = new File(pdfPath);
            if (!file.exists()) {
                return;
            }

            final List<String> qrCodes = parser.getQrCodes(pdfPath, List.of("wrong_password"));

            assertNotNull(qrCodes);
            assertTrue(qrCodes.isEmpty());
        }

        @Test
        @DisplayName("Should return empty list for encrypted PDF without password")
        void shouldReturnEmptyForEncryptedPdfWithoutPassword() {
            if (fixturesDir == null) {
                return;
            }
            
            final String pdfPath = fixturesDir + "/qr_code_example_one_page_enc.pdf";
            final File file = new File(pdfPath);
            if (!file.exists()) {
                return;
            }

            final List<String> qrCodes = parser.getQrCodes(pdfPath, List.of());

            assertNotNull(qrCodes);
            assertTrue(qrCodes.isEmpty());
        }

        @Test
        @DisplayName("Should support various image formats")
        void shouldSupportVariousImageFormats() {
            final String[] extensions = {"jpg", "jpeg", "png", "bmp", "gif"};

            for (final String ext : extensions) {
                final File tempFile;
                try {
                    tempFile = File.createTempFile("test", "." + ext);
                    tempFile.deleteOnExit();
                } catch (final Exception e) {
                    continue;
                }

                final List<String> result = parser.getQrCodes(tempFile.getAbsolutePath(), List.of());
                assertNotNull(result);
                
                tempFile.delete();
            }
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should handle QR code with special characters")
        void shouldHandleSpecialCharacters() {
            final String qrString = "A:123456789*G:FR 0/2026-0001*S:Test with spaces and symbols!@#$%";

            final InvoiceContent result = parser.parseQrCodeString(qrString);

            assertNotNull(result);
            assertEquals("FR 0/2026-0001", result.getInvoiceId());
            assertEquals("Test with spaces and symbols!@#$%", result.getOtherInformation());
        }

        @Test
        @DisplayName("Should handle very long QR code string")
        void shouldHandleLongQrCodeString() {
            final StringBuilder sb = new StringBuilder("A:123456789");
            for (int i = 0; i < 100; i++) {
                sb.append("*S:Information").append(i);
            }
            final String qrString = sb.toString();

            final InvoiceContent result = parser.parseQrCodeString(qrString);

            assertNotNull(result);
            assertEquals("123456789", result.getIssuerTin());
        }

        @Test
        @DisplayName("Should handle QR code with empty field values")
        void shouldHandleEmptyFieldValues() {
            final String qrString = "A:*B:*C:*D:FT*E:**F:*G:*H:*";

            final InvoiceContent result = parser.parseQrCodeString(qrString);

            assertNotNull(result);
            assertEquals("", result.getIssuerTin());
            assertEquals("", result.getCustomerTin());
            assertEquals("FT", result.getInvoiceType());
        }

        @Test
        @DisplayName("Should handle QR code with only tokens separator")
        void shouldHandleOnlySeparators() {
            final String qrString = "******";

            final InvoiceContent result = parser.parseQrCodeString(qrString);

            assertNotNull(result);
            assertEquals(qrString, result.getRaw());
        }
    }

    @Nested
    @DisplayName("Portuguese ATCUD Format")
    class PortugueseAtcudFormatTests {

        @Test
        @DisplayName("Should parse standard Portuguese invoice QR code")
        void shouldParseStandardPortugueseInvoice() {
            final String qrString = "A:507957129*B:123456789*C:PT*D:FT*E:N*F:20260315*G:FT 0123/45678*H:ABC-123*I1:PT*I2:0*I3:0*I4:0*I5:0*I6:0*I7:23*I8:110.5*L:0*M:0*N:25.50*O:111.50*P:0*Q:abc123def456*R:0001*S:Invoice";

            final InvoiceContent result = parser.parseQrCodeString(qrString);

            assertNotNull(result);
            assertEquals("507957129", result.getIssuerTin());
            assertEquals("123456789", result.getCustomerTin());
            assertEquals("PT", result.getCustomerCountry());
            assertEquals("FT", result.getInvoiceType());
            assertEquals("N", result.getStatus());
            assertEquals("2026-03-15", result.getInvoiceDate());
            assertEquals("FT 0123/45678", result.getInvoiceId());
            assertEquals("ABC-123", result.getAtcud());
            assertEquals(new BigDecimal("111.50"), result.getTotal());
            assertEquals(new BigDecimal("25.50"), result.getTotalTaxes());
            assertEquals("abc123def456", result.getHash());
            assertEquals("0001", result.getCertificateNumber());
            
            assertNotNull(result.getFirstTaxable());
            assertEquals("PT", result.getFirstTaxable().taxCountryRegion());
            assertEquals(new BigDecimal("23"), result.getFirstTaxable().basicsStandardRate());
            assertEquals(new BigDecimal("110.5"), result.getFirstTaxable().totalTaxesStandardRate());
        }

        @Test
        @DisplayName("Should parse invoice receipt (FR)")
        void shouldParseInvoiceReceipt() {
            final String qrString = "A:507957129*B:*C:PT*D:FR*E:N*F:20260315*G:FR 0123/45678*H:ABC-123*L:100*M:0*N:0*O:100*P:0*Q:hash*R:cert";

            final InvoiceContent result = parser.parseQrCodeString(qrString);

            assertNotNull(result);
            assertEquals("FR", result.getInvoiceType());
            assertEquals("", result.getCustomerTin());
            assertEquals(new BigDecimal("100"), result.getTotal());
        }

        @Test
        @DisplayName("Should parse credit note (NC)")
        void shouldParseCreditNote() {
            final String qrString = "A:507957129*B:987654321*C:PT*D:NC*E:N*F:20260315*G:NC 0123/45678*H:ABC-123*L:50*N:-11.5*O:38.5*P:0*Q:hash*R:cert";

            final InvoiceContent result = parser.parseQrCodeString(qrString);

            assertNotNull(result);
            assertEquals("NC", result.getInvoiceType());
            assertEquals(new BigDecimal("38.5"), result.getTotal());
            assertEquals(new BigDecimal("-11.5"), result.getTotalTaxes());
        }

        @Test
        @DisplayName("Should parse debit note (ND)")
        void shouldParseDebitNote() {
            final String qrString = "A:507957129*B:987654321*C:PT*D:ND*E:N*F:20260315*G:ND 0123/45678*H:ABC-123*L:0*N:23*O:25*P:0*Q:hash*R:cert";

            final InvoiceContent result = parser.parseQrCodeString(qrString);

            assertNotNull(result);
            assertEquals("ND", result.getInvoiceType());
        }

        @Test
        @DisplayName("Should parse simplified invoice (FS)")
        void shouldParseSimplifiedInvoice() {
            final String qrString = "A:507957129*B:*C:PT*D:FS*E:N*F:20260315*G:FS 0123/45678*H:ABC-123*I1:PT*I7:23*I8:23*L:100*N:23*O:123*P:0*Q:hash*R:cert";

            final InvoiceContent result = parser.parseQrCodeString(qrString);

            assertNotNull(result);
            assertEquals("FS", result.getInvoiceType());
            assertEquals("", result.getCustomerTin());
            assertEquals(new BigDecimal("123"), result.getTotal());
            assertEquals(new BigDecimal("23"), result.getTotalTaxes());
        }
    }

    @Nested
    @DisplayName("File Extension Detection")
    class FileExtensionTests {

        @Test
        @DisplayName("Should detect PDF extension")
        void shouldDetectPdfExtension() {
            final List<String> result = parser.getQrCodes("test.PDF", List.of());
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should detect lowercase extension")
        void shouldDetectLowercaseExtension() {
            final List<String> result = parser.getQrCodes("test.pdf", List.of());
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should handle file with multiple dots")
        void shouldHandleMultipleDots() {
            final List<String> result = parser.getQrCodes("test.file.name.pdf", List.of());
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should handle file without extension")
        void shouldHandleNoExtension() {
            final List<String> result = parser.getQrCodes("testfile", List.of());
            assertTrue(result.isEmpty());
        }
    }
}