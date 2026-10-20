package com.amfalmeida.mailhawk.service;

import com.amfalmeida.mailhawk.client.ActualBudgetClient;
import com.amfalmeida.mailhawk.config.ActualConfig;
import com.amfalmeida.mailhawk.client.dto.TransactionDto;
import com.amfalmeida.mailhawk.client.dto.TransactionImportRequest;
import com.amfalmeida.mailhawk.email.model.Invoice;
import com.amfalmeida.mailhawk.email.model.InvoiceContent;
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

    private boolean isEnabled() {
        return config.enabled().orElse(false);
    }

    public void importInvoices(final List<Invoice> invoices) {
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
                    config.budgetSyncId().get(),
                    config.accountId().get(),
                    config.apiKey().get(),
                    request
            );

            log.info("Successfully imported {} invoices to Actual Budget", invoices.size());
        } catch (Exception e) {
            log.error("Failed to import invoices to Actual Budget", e);
        }
    }

    private TransactionDto toTransactionDto(final Invoice invoice) {
        var invoiceContent = invoice.getInvoiceContent();
        var invoiceType = invoice.getInvoiceType();

        String date;
        if (invoiceContent != null
                && invoiceContent.getInvoiceDate() != null
                && !invoiceContent.getInvoiceDate().isEmpty()) {
            date = invoiceContent.getInvoiceDate();
        } else if (invoice.getDate() != null) {
            date = invoice.getDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
        } else {
            date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        }

        Integer amount = null;
        if (invoiceContent != null && invoiceContent.getTotal() != null) {
            amount = -invoiceContent.getTotal().multiply(BigDecimal.valueOf(100)).intValue();
        }

        String payeeName = invoiceType != null ? invoiceType.name() : null;
        String notes = invoice.getFilename();
        String importedId = importedId(invoiceContent);

        return new TransactionDto(
            config.accountId().get(),
            date,
            amount,
            payeeName,
            null,
            notes,
            importedId,
            true
        );
    }

    /**
     * Key Actual Budget uses to skip duplicate imports. Uses the ATCUD when it's a real code;
     * otherwise (missing, or "0" which the AT spec allows when there is no ATCUD, and recurrent
     * bills) falls back to the raw QR string, which is unique per invoice.
     */
    private static String importedId(final InvoiceContent invoiceContent) {
        if (invoiceContent == null) {
            return null;
        }
        final String atcud = invoiceContent.getAtcud();
        if (atcud != null && !atcud.isBlank() && !"0".equals(atcud.trim())) {
            return atcud;
        }
        return invoiceContent.getRaw();
    }
}
