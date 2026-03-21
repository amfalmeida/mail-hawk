package com.amfalmeida.mailhawk.service;

import com.amfalmeida.mailhawk.config.AppConfig;
import com.amfalmeida.mailhawk.config.MailConfig;
import com.amfalmeida.mailhawk.model.Invoice;
import com.amfalmeida.mailhawk.model.InvoiceType;
import com.amfalmeida.mailhawk.model.QrCodeContent;
import com.amfalmeida.mailhawk.model.SheetsResult;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.inject.Inject;

@ApplicationScoped
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public final class InvoiceProcessor {

    private final MailService mailService;
    private final SheetsService sheetsService;
    private final DatabaseService databaseService;
    private final QrCodeParser qrCodeParser;
    private final AppConfig appConfig;
    private final MailConfig mailConfig;
    private final ActualBudgetService actualBudgetService;

    private LocalDateTime lastCheckedAt;
    private final AtomicInteger checkEmailsCount = new AtomicInteger(0);

    @Scheduled(every = "${app.config-sync-interval:300s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void syncConfigs() {
        log.info("Syncing configs from sheets...");
        try {
            final List<InvoiceType> configs = sheetsService.getConfigurations();
            if (!configs.isEmpty()) {
                databaseService.syncConfigs(configs);
            }
        } catch (final Exception e) {
            log.error("Error syncing configs from sheets", e);
        }
    }

    @Scheduled(every = "${app.check-interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void checkEmails() {
        final int runCount = checkEmailsCount.incrementAndGet();
        log.info("Checking for new emails... (run #{})", runCount);
        try {
            final LocalDateTime searchStartDate = lastCheckedAt;
            lastCheckedAt = LocalDateTime.now();
            
            mailService.checkAndProcessEmails(
                invoice -> log.info("Processing invoice: {} | From: {}", invoice.getFilename(), invoice.getFromAddress()),
                this::processInvoice,
                searchStartDate
            );
        } catch (final Exception e) {
            log.error("Error checking emails", e);
        }
    }

void processInvoice(final Invoice invoice) {
        log.info("Processing invoice: {} | From: {} | Date: {}", 
            invoice.getSubject(), invoice.getFromAddress(), invoice.getDate());
        
        if (invoice.getFilePath() == null || !new File(invoice.getFilePath()).exists()) {
            log.error("No file to process");
            return;
        }

        try {
            final List<String> pdfPasswords = getPdfPasswords();

            final List<String> qrCodes = qrCodeParser.getQrCodes(invoice.getFilePath(), pdfPasswords);
            log.debug("Found {} QR codes", qrCodes.size());

            if (qrCodes.isEmpty()) {
                log.warn("No QR codes found in invoice");
                return;
            }

            for (final String qrStr : qrCodes) {
                final QrCodeContent qrCode = qrCodeParser.parseQrCodeString(qrStr);
                if (qrCode == null || qrCode.getInvoiceId() == null || qrCode.getInvoiceId().isBlank()) {
                    log.warn("Invalid QR code: {}", qrStr.substring(0, Math.min(50, qrStr.length())));
                    continue;
                }

                log.debug("Parsed QR code: {}", qrCode);

                if (databaseService.isInvoiceProcessedByRawQr(qrCode.getRaw())) {
                    log.info("Invoice already processed: {}", qrCode.getInvoiceId());
                    continue;
                }

                invoice.setQrCode(qrCode);

                final InvoiceType invoiceType = determineInvoiceType(invoice, qrCode);
                invoice.setInvoiceType(invoiceType);

                final var result = sheetsService.addInvoice(invoice);
                log.info("Sheet operation result: {} - {}", result.status(), result.message());

                if (result.status() == SheetsResult.Status.APPENDED || 
                    result.status() == SheetsResult.Status.ALREADY_EXISTS) {
                    
                    databaseService.markInvoiceProcessed(
                        qrCode.getAtcud(),
                        qrCode.getInvoiceId(),
                        qrCode.getInvoiceDate(),
                        invoiceType.name(),
                        qrCode.getIssuerTin(),
                        qrCode.getCustomerTin(),
                        qrCode.getCustomerCountry(),
                        invoiceType.type(),
                        qrCode.getStatus(),
                        qrCode.getTotal(),
                        qrCode.getTotalTaxes(),
                        qrCode.getNonTaxable(),
                        qrCode.getStampDuty(),
                        qrCode.getWithholdingTax(),
                        qrCode.getHash(),
                        qrCode.getCertificateNumber(),
                        qrCode.getOtherInformation(),
                        qrCode.getRaw(),
                        invoice.getSubject(),
                        invoice.getFromAddress(),
                        invoice.getFromName(),
                        invoice.getToAddress(),
                        qrCode.getFirstTaxable(),
                        qrCode.getSecondTaxable(),
                        qrCode.getThirdTaxable()
                    );

                    actualBudgetService.importInvoices(List.of(invoice));
                }
            }
        } finally {
            cleanupFile(invoice.getFilePath());
        }
    }

    private InvoiceType determineInvoiceType(final Invoice invoice, final QrCodeContent qrCode) {
        String issuerTin = qrCode.getIssuerTin();
        
        if (issuerTin != null && !issuerTin.isEmpty()) {
            final var config = sheetsService.getConfigurationByNif(issuerTin);
            if (config.isPresent()) return config.get();
        }

        if (invoice.getFromAddress() != null && !invoice.getFromAddress().isEmpty()) {
            final var config = sheetsService.getConfigurationByEmail(invoice.getFromAddress());
            if (config.isPresent()) return config.get();
        }

        return new InvoiceType(
            appConfig.defaultInvoiceType(),
            invoice.getFromAddress(),
            invoice.getFromAddress(),
            issuerTin != null ? issuerTin : ""
        );
    }

    private List<String> getPdfPasswords() {
        final String passwords = mailConfig.pdfPasswords();
        if (passwords == null || passwords.isEmpty()) return List.of();
        return Arrays.asList(passwords.split(","));
    }

    private void cleanupFile(final String filePath) {
        if (filePath == null) return;
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