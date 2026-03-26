package com.amfalmeida.mailhawk.service;

import com.amfalmeida.mailhawk.model.InvoiceContent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("QR Code Fixture Tests")
@Tag("ondemand")
class QrCodeFixtureTest {

    private static final String FIXTURE_FILE = "test.pdf";

    private QrCodeParser parser;
    private File fixturesDir;

    @BeforeEach
    void setUp() {
        parser = new QrCodeParser();
        fixturesDir = new File("src/test/resources/fixtures");
    }

    @Test
    @DisplayName("Check specific fixture file QR code is parseable")
    @Tag("ondemand")
    void checkSpecificFixtureFileQrCodeIsParseable() {
        if (!fixturesDir.exists()) {
            fail("Fixtures directory not found: " + fixturesDir.getAbsolutePath());
            return;
        }

        final File file = new File(fixturesDir, FIXTURE_FILE);
        if (!file.exists()) {
            fail("Fixture file not found: " + file.getAbsolutePath());
            return;
        }

        final List<String> qrCodes = parser.getQrCodes(file.getAbsolutePath(), List.of());

        assertFalse(qrCodes.isEmpty(), "No QR codes found in fixture file: " + file.getAbsolutePath());

        for (final String qrCode : qrCodes) {
            final InvoiceContent content = parser.parseQrCodeString(qrCode);

            assertNotNull(content, "Parsed InvoiceContent should not be null");
            assertNotNull(content.getRaw(), "Raw QR code should be preserved");
            assertNotNull(content.getIssuerTin(), "Issuer TIN should be present in QR code");

            System.out.println("=== Successfully parsed QR code ===");
            System.out.println("Issuer TIN: " + content.getIssuerTin());
            System.out.println("Customer TIN: " + content.getCustomerTin());
            System.out.println("Invoice ID: " + content.getInvoiceId());
            System.out.println("Invoice Type: " + content.getInvoiceType());
            System.out.println("Total: " + content.getTotal());
            System.out.println("Invoice Date: " + content.getInvoiceDate());
            System.out.println("Raw: " + content.getRaw());
            System.out.println("=================================");
        }
    }
}