package com.amfalmeida.mailhawk.service;

import java.io.File;
import java.util.List;

import com.amfalmeida.mailhawk.config.AppConfig;
import com.amfalmeida.mailhawk.config.MailConfig;
import com.amfalmeida.mailhawk.email.model.Invoice;
import com.amfalmeida.mailhawk.email.model.InvoiceContent;
import com.amfalmeida.mailhawk.email.model.InvoiceResult;
import com.amfalmeida.mailhawk.email.model.InvoiceType;
import com.amfalmeida.mailhawk.email.model.SheetsResult;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@ApplicationScoped
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public final class InvoiceProcessor {

    private final SheetsService sheetsService;
    private final ProcessedInvoiceCache processedInvoiceCache;
    private final QrCodeParser qrCodeParser;
    private final AppConfig appConfig;
    private final MailConfig mailConfig;
    private final ActualBudgetService actualBudgetService;

    @Scheduled(every = "${app.config-sync-interval:300s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void syncConfigs() {
        log.info("Syncing configs from sheets...");
        try {
            sheetsService.refreshConfigurations();
        } catch (final Exception e) {
            log.error("Error syncing configs from sheets", e);
        }
    }

    void processInvoice(final Invoice invoice) {
        log.info("Processing invoice: {} | From: {} | To: {} | Date: {}",
            invoice.getSubject(), invoice.getFromAddress(), invoice.getToAddress(), invoice.getDate());

        if (invoice.getFilePath() == null || !new File(invoice.getFilePath()).exists()) {
            log.error("No file to process");
            return;
        }

        try {
            final List<String> pdfPasswords = mailConfig.pdfPasswords();

            final List<String> qrCodes = qrCodeParser.getQrCodes(invoice.getFilePath(), pdfPasswords);
            log.debug("Found {} QR codes", qrCodes.size());

            if (qrCodes.isEmpty()) {
                log.warn("No QR codes found in invoice");
                return;
            }

            for (final String qrStr : qrCodes) {
                final InvoiceContent invoiceContent = qrCodeParser.parseQrCodeString(qrStr);
                if (invoiceContent == null
                        || invoiceContent.getInvoiceId() == null
                        || invoiceContent.getInvoiceId().isBlank()) {
                    log.warn("Invalid QR code: {}", qrStr);
                    continue;
                }

                log.debug("Parsed QR code: {}", invoiceContent);

                invoice.setInvoiceContent(invoiceContent);
                storeInvoice(invoice);
            }
        } finally {
            cleanupFile(invoice.getFilePath());
        }
    }

    /**
     * Stores an invoice whose {@link InvoiceContent} is already known: skips it if already processed, resolves
     * the {@link InvoiceType}, appends it to Sheets and, only if that didn't fail, marks it processed and imports
     * it into Actual Budget.
     */
    public InvoiceResult storeInvoice(final Invoice invoice) {
        return storeInvoice(invoice, null);
    }

    /**
     * Same as {@link #storeInvoice(Invoice)}, but a non-blank {@code entityName} replaces the name of the resolved
     * {@link InvoiceType} (the entity column in Sheets and the Actual Budget payee). The type itself is still
     * resolved from the issuer NIF, then the sender email, then {@code app.default-invoice-type}.
     */
    public InvoiceResult storeInvoice(final Invoice invoice, final String entityName) {
        final InvoiceContent invoiceContent = invoice.getInvoiceContent();

        if (processedInvoiceCache.isProcessed(invoiceContent.getRaw())) {
            log.info("Invoice already processed: {}", invoiceContent.getInvoiceId());
            return InvoiceResult.of(invoiceContent, null,
                InvoiceResult.Status.ALREADY_PROCESSED, "Invoice already processed");
        }

        final InvoiceType resolvedType = determineInvoiceType(invoice, invoiceContent);
        final InvoiceType invoiceType = entityName == null || entityName.isBlank()
            ? resolvedType
            : new InvoiceType(resolvedType.type(), resolvedType.fromEmail(), entityName.trim(), resolvedType.nif());
        invoice.setInvoiceType(invoiceType);

        final var result = sheetsService.addInvoice(invoice);
        log.info("Sheet operation result: {} - {}", result.status(), result.message());

        if (result.status() == SheetsResult.Status.APPENDED ||
            result.status() == SheetsResult.Status.ALREADY_EXISTS) {

            processedInvoiceCache.markProcessed(invoiceContent.getRaw());

            actualBudgetService.importInvoices(List.of(invoice));
        }
        return InvoiceResult.of(invoiceContent, invoiceType.type(),
            InvoiceResult.Status.valueOf(result.status().name()), result.message());
    }

    private InvoiceType determineInvoiceType(final Invoice invoice, final InvoiceContent invoiceContent) {
        String issuerTin = invoiceContent.getIssuerTin();

        if (issuerTin != null && !issuerTin.isEmpty()) {
            final var config = sheetsService.getConfigurationByNif(issuerTin);
            if (config.isPresent()) {
                return config.get();
            }
        }

        if (invoice.getFromAddress() != null && !invoice.getFromAddress().isEmpty()) {
            final var config = sheetsService.getConfigurationByEmail(invoice.getFromAddress());
            if (config.isPresent()) {
                return config.get();
            }
        }

        return new InvoiceType(
            appConfig.defaultInvoiceType(),
            invoice.getFromAddress(),
            invoice.getFromAddress(),
            issuerTin != null ? issuerTin : ""
        );
    }

    private void cleanupFile(final String filePath) {
        if (filePath == null) {
            return;
        }
        try {
            final File file = new File(filePath);
            if (file.exists()) {
                file.delete();
                log.debug("Cleaned up file: {}", filePath);

                final File parent = file.getParentFile();
                if (parent != null && parent.exists() && parent.getName().startsWith("invoice_")) {
                    parent.delete();
                }
            }
        } catch (final Exception e) {
            log.warn("Error cleaning up file: {}", filePath, e);
        }
    }
}
