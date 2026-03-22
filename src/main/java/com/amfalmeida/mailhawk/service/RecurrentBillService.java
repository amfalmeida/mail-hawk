package com.amfalmeida.mailhawk.service;

import com.amfalmeida.mailhawk.config.AppConfig;
import com.amfalmeida.mailhawk.db.entity.ProcessedInvoice;
import com.amfalmeida.mailhawk.model.Invoice;
import com.amfalmeida.mailhawk.model.InvoiceContent;
import com.amfalmeida.mailhawk.model.InvoiceType;
import com.amfalmeida.mailhawk.model.RecurrentBill;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

@ApplicationScoped
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class RecurrentBillService {

    private final SheetsService sheetsService;
    private final ActualBudgetService actualBudgetService;

    @Scheduled(every = "${app.recurrent-check-interval:360}s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void processRecurrentBills() {
        log.info("Checking recurrent bills...");
        
        try {
            List<RecurrentBill> bills = sheetsService.getRecurrentBills();
            int processed = 0;
            int skipped = 0;

            for (RecurrentBill bill : bills) {
                try {
                    if (shouldProcess(bill)) {
                        processBill(bill);
                        processed++;
                    } else {
                        skipped++;
                    }
                } catch (Exception e) {
                    log.error("Failed to process recurrent bill: {}", bill.entityName(), e);
                }
            }

            log.info("Recurrent bills processed: {}, skipped: {}", processed, skipped);
        } catch (Exception e) {
            log.error("Error processing recurrent bills", e);
        }
    }

    private boolean shouldProcess(RecurrentBill bill) {
        if (bill.entityName() == null || bill.entityName().isEmpty()) {
            return false;
        }

        int today = LocalDate.now().getDayOfMonth();
        if (bill.paymentDay() == null || today != bill.paymentDay()) {
            return false;
        }

        if (bill.until() != null && !bill.until().isEmpty()) {
            try {
                LocalDate untilDate = LocalDate.parse(bill.until());
                if (LocalDate.now().isAfter(untilDate)) {
                    return false;
                }
            } catch (Exception e) {
                log.warn("Invalid until date format for bill: {}", bill.entityName());
            }
        }

        String invoiceId = generateInvoiceId(bill.entityName());
        if (ProcessedInvoice.findByRawQrHash(invoiceId) != null) {
            return false;
        }

        return true;
    }

    private void processBill(RecurrentBill bill) {
        log.info("Processing recurrent bill: {}", bill.entityName());

        Invoice invoice = createInvoice(bill);

        sheetsService.addInvoice(invoice);

        if (actualBudgetService.isEnabled()) {
            actualBudgetService.importInvoices(List.of(invoice));
        }

        saveProcessedInvoice(bill, invoice);
    }

    private Invoice createInvoice(RecurrentBill bill) {
        LocalDate today = LocalDate.now();
        String invoiceId = generateInvoiceId(bill.entityName());
        BigDecimal value = bill.value() != null ? bill.value() : BigDecimal.ZERO;

        InvoiceContent invoiceContent = InvoiceContent.builder()
                .invoiceId(invoiceId)
                .issuerTin(bill.nif())
                .customerTin(bill.customerNif())
                .invoiceDate(today.toString())
                .total(value)
                .totalTaxes(BigDecimal.ZERO)
                .nonTaxable(value)
                .invoiceType(bill.type())
                .invoiceId(invoiceId)
                .raw("RECURRENT:" + invoiceId)
                .build();

        InvoiceType invoiceType = new InvoiceType(
                bill.type(),
                bill.entityEmail(),
                bill.entityName(),
                bill.nif()
        );

        return Invoice.builder()
                .id(invoiceId)
                .subject("")
                .fromAddress(bill.entityEmail() != null ? bill.entityEmail() : "")
                .fromName(bill.entityName())
                .date(today)
                .filename("")
                .invoiceContent(invoiceContent)
                .invoiceType(invoiceType)
                .build();
    }

    private String generateInvoiceId(String entityName) {
        return "REC-" + entityName.replaceAll("[^a-zA-Z0-9]", "") + "-" + YearMonth.now().toString();
    }

    @Transactional
    void saveProcessedInvoice(RecurrentBill bill, Invoice invoice) {
        ProcessedInvoice processed = new ProcessedInvoice();
        processed.rawQrHash = invoice.getId();
        processed.atcud = invoice.getId();
        processed.processedAt = java.time.LocalDateTime.now();
        processed.invoiceId = invoice.getId();
        processed.invoiceDate = invoice.getDate().toString();
        processed.issuerName = bill.entityName();
        processed.issuerNif = bill.nif();
        processed.customerNif = bill.customerNif();
        processed.total = bill.value();
        processed.fromEmail = bill.entityEmail();
        processed.fromName = bill.entityName();
        processed.subject = invoice.getSubject();
        processed.persist();
    }
}