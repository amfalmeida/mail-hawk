package com.amfalmeida.mailhawk.service;

import com.amfalmeida.mailhawk.client.ActualBudgetClient;
import com.amfalmeida.mailhawk.config.ActualConfig;
import com.amfalmeida.mailhawk.dto.TransactionDto;
import com.amfalmeida.mailhawk.dto.TransactionImportRequest;
import com.amfalmeida.mailhawk.model.Invoice;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@ApplicationScoped
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class ActualBudgetService {
    
    private final ActualConfig config;

    @RestClient
    ActualBudgetClient client;

    public boolean isEnabled() {
        return config.enabled() && 
               config.url() != null && !config.url().isEmpty() &&
               config.apiKey() != null && !config.apiKey().isEmpty() &&
               config.budgetSyncId() != null && !config.budgetSyncId().isEmpty() &&
               config.accountId() != null && !config.accountId().isEmpty();
    }

    public void importInvoices(List<Invoice> invoices) {
        if (!isEnabled()) {
            log.debug("Actual Budget integration is disabled or not configured");
            return;
        }

        if (invoices == null || invoices.isEmpty()) {
            return;
        }

        try {
            List<TransactionDto> transactions = new ArrayList<>();
            for (Invoice invoice : invoices) {
                transactions.add(toTransactionDto(invoice));
            }

            TransactionImportRequest request = new TransactionImportRequest(transactions);

            client.importTransactions(
                    config.budgetSyncId(),
                    config.accountId(),
                    config.apiKey(),
                    request
            );

            log.info("Successfully imported {} invoices to Actual Budget", invoices.size());
        } catch (Exception e) {
            log.error("Failed to import invoices to Actual Budget", e);
        }
    }

    private TransactionDto toTransactionDto(Invoice invoice) {
        var qr = invoice.getQrCode();
        var invoiceType = invoice.getInvoiceType();
        
        String date;
        if (qr != null && qr.getInvoiceDate() != null && !qr.getInvoiceDate().isEmpty()) {
            date = qr.getInvoiceDate();
        } else if (invoice.getDate() != null) {
            date = invoice.getDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
        } else {
            date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        }
        
        Integer amount = null;
        if (qr != null && qr.getTotal() != null) {
            amount = -qr.getTotal().multiply(BigDecimal.valueOf(100)).intValue();
        }

        String payeeName = invoiceType != null ? invoiceType.name() : null;
        String notes = invoice.getFilename();
        String importedId = qr != null ? qr.getAtcud() : null;

        return new TransactionDto(
            config.accountId(),
            date,
            amount,
            payeeName,
            null,
            notes,
            importedId,
            true
        );
    }
}