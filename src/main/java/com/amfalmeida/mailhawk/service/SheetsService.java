package com.amfalmeida.mailhawk.service;

import com.amfalmeida.mailhawk.config.AppConfig;
import com.amfalmeida.mailhawk.config.SheetsConfig;
import com.amfalmeida.mailhawk.email.model.Invoice;
import com.amfalmeida.mailhawk.email.model.InvoiceType;
import com.amfalmeida.mailhawk.email.model.InvoiceContent;
import com.amfalmeida.mailhawk.email.model.InvoiceContent.Taxable;
import com.amfalmeida.mailhawk.email.model.RecurrentBill;
import com.amfalmeida.mailhawk.email.model.SheetsResult;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.model.ValueRange;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.ServiceAccountCredentials;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import jakarta.inject.Inject;

@ApplicationScoped
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public final class SheetsService {

    private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();

    private final SheetsConfig sheetsConfig;
    private final AppConfig appConfig;

    private Sheets sheetsService;
    private Cache<String, InvoiceType> configCacheByNif;
    private Cache<String, InvoiceType> configCacheByEmail;

    public SheetsResult addInvoice(final Invoice invoice) {
        if (sheetsService == null) {
            return SheetsResult.error("Google Sheets service not initialized");
        }

        if (invoice.getInvoiceContent() == null) {
            return SheetsResult.error("No QR code data");
        }

        final String spreadsheetId = sheetsConfig.id();
        if (spreadsheetId.isBlank()) {
            return SheetsResult.error("Spreadsheet ID not configured");
        }

        try {
            if (checkExisting(invoice)) {
                return SheetsResult.alreadyExists("Invoice already exists in spreadsheet");
            }

            final List<Object> values = getValues(invoice);
            final ValueRange body = new ValueRange().setValues(List.of(values));

            sheetsService.spreadsheets().values()
                .append(spreadsheetId, buildRange(sheetsConfig.sheetName(), "A:A"), body)
                .setValueInputOption("USER_ENTERED")
                .execute();

            log.info("Appended invoice to spreadsheet");
            return SheetsResult.appended("Invoice added successfully");

        } catch (final Exception e) {
            log.error("Error adding invoice to spreadsheet", e);
            return SheetsResult.error(e.getMessage());
        }
    }

    public Optional<InvoiceType> getConfigurationByNif(final String nif) {
        ensureConfigurationsLoaded();
        return Optional.ofNullable(configCacheByNif.getIfPresent(nif.trim()));
    }

    public Optional<InvoiceType> getConfigurationByEmail(final String email) {
        ensureConfigurationsLoaded();
        return Optional.ofNullable(configCacheByEmail.getIfPresent(email.trim().toLowerCase()));
    }

    /**
     * Reloads both config caches from the config sheet. Called by the scheduled sync, and on demand
     * when the caches are empty (before the first sync, or after entries expired because syncs failed).
     * Keeps the current entries if the sheet can't be read.
     */
    public void refreshConfigurations() {
        final List<InvoiceType> configs = getConfigurations();
        if (configs.isEmpty()) {
            return;
        }
        configCacheByNif.invalidateAll();
        configCacheByEmail.invalidateAll();
        for (final InvoiceType config : configs) {
            if (!config.nif().isBlank()) {
                configCacheByNif.put(config.nif().trim(), config);
            }
            if (!config.fromEmail().isBlank()) {
                configCacheByEmail.put(config.fromEmail().trim().toLowerCase(), config);
            }
        }
        log.info("Synced {} configs from sheets", configs.size());
    }

    public List<RecurrentBill> getRecurrentBills() {
        if (sheetsService == null) {
            return List.of();
        }

        try {
            final String spreadsheetId = sheetsConfig.id();
            if (spreadsheetId.isBlank()) {
                return List.of();
            }

            final ValueRange result = sheetsService.spreadsheets().values()
                .get(spreadsheetId, buildRange(sheetsConfig.recurrentSheet().replace("\"", ""), "A:K"))
                .execute();

            final List<List<Object>> values = result.getValues();
            if (values == null || values.size() <= 1) {
                return List.of();
            }

            final List<RecurrentBill> bills = new ArrayList<>();
            for (int i = 1; i < values.size(); i++) {
                final List<Object> row = values.get(i);
                if (row.size() >= 4 && row.get(3) != null && !row.get(3).toString().isEmpty()) {
                    bills.add(new RecurrentBill(
                        row.size() > 0 ? row.get(0).toString() : "",
                        row.size() > 1 ? row.get(1).toString() : "",
                        row.size() > 2 ? row.get(2).toString() : "",
                        row.size() > 3 ? row.get(3).toString() : "",
                        row.size() > 4 ? row.get(4).toString() : "",
                        row.size() > 5 ? row.get(5).toString() : "",
                        parseDecimal(row.size() > 6 ? row.get(6) : null),
                        parseDecimal(row.size() > 7 ? row.get(7) : null),
                        parseInteger(row.size() > 8 ? row.get(8) : null),
                        row.size() > 9 ? row.get(9).toString() : "",
                        row.size() > 10 ? row.get(10).toString() : ""
                    ));
                }
            }
            return bills;
        } catch (final Exception e) {
            log.error("Error getting recurrent bills", e);
            return List.of();
        }
    }

    @PostConstruct
    void init() {
        configCacheByNif = buildConfigCache();
        configCacheByEmail = buildConfigCache();

        try {
            final String encodedCredentials = sheetsConfig.encodedCredentials();
            if (encodedCredentials == null || encodedCredentials.isEmpty()) {
                log.warn("No Google Sheets credentials configured");
                return;
            }

            final byte[] decoded = Base64.getDecoder().decode(encodedCredentials);
            final ServiceAccountCredentials credentials = ServiceAccountCredentials.fromStream(
                new ByteArrayInputStream(decoded)
            );

            final HttpRequestInitializer requestInitializer = new HttpCredentialsAdapter(credentials);
            sheetsService = new Sheets.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                JSON_FACTORY,
                requestInitializer
            ).setApplicationName("MailHawk").build();

            log.info("Authenticated to Google Sheets");
        } catch (final Exception e) {
            log.error("Failed to authenticate to Google Sheets", e);
        }
    }

    private List<InvoiceType> getConfigurations() {
        if (sheetsService == null) {
            return List.of();
        }

        try {
            final String spreadsheetId = sheetsConfig.id();
            if (spreadsheetId.isBlank()) {
                return List.of();
            }

            final ValueRange result = sheetsService.spreadsheets().values()
                .get(spreadsheetId, buildRange(sheetsConfig.configSheet().replace("\"", ""), "A:E"))
                .execute();

            final List<List<Object>> values = result.getValues();
            if (values == null || values.size() <= 1) {
                return List.of();
            }

            final List<InvoiceType> configs = new ArrayList<>();
            for (int i = 1; i < values.size(); i++) {
                final List<Object> row = values.get(i);
                if (row.size() >= 4) {
                    configs.add(new InvoiceType(
                        row.size() > 0 ? row.get(0).toString() : "",
                        row.size() > 1 ? row.get(1).toString() : "",
                        row.size() > 2 ? row.get(2).toString() : "",
                        row.size() > 3 ? row.get(3).toString() : ""
                    ));
                }
            }
            return configs;
        } catch (final Exception e) {
            log.error("Error getting configurations", e);
            return List.of();
        }
    }

    private void ensureConfigurationsLoaded() {
        configCacheByNif.cleanUp();
        configCacheByEmail.cleanUp();
        if (configCacheByNif.estimatedSize() == 0 && configCacheByEmail.estimatedSize() == 0) {
            refreshConfigurations();
        }
    }

    private Cache<String, InvoiceType> buildConfigCache() {
        return Caffeine.newBuilder()
            .expireAfterWrite(appConfig.configCacheHours(), TimeUnit.HOURS)
            .build();
    }

    private String buildRange(final String sheetName, final String range) {
        return sheetName + "!" + range;
    }

    private boolean checkExisting(final Invoice invoice) {
        try {
            final String spreadsheetId = sheetsConfig.id();
            if (spreadsheetId.isBlank()) {
                return false;
            }

            final ValueRange result = sheetsService.spreadsheets().values()
                .get(spreadsheetId, buildRange(sheetsConfig.sheetName(), "AD:AD"))
                .execute();

            final List<List<Object>> values = result.getValues();
            if (values == null) {
                return false;
            }

            final String rawQr = invoice.getInvoiceContent().getRaw();
            for (final List<Object> row : values) {
                if (!row.isEmpty() && rawQr.equals(row.get(0))) {
                    return true;
                }
            }
        } catch (final Exception e) {
            log.error("Error checking existing invoice", e);
        }
        return false;
    }

    private List<Object> getValues(final Invoice invoice) {
        final List<Object> values = new ArrayList<>();
        final InvoiceContent invoiceContent = invoice.getInvoiceContent();

        InvoiceType type = invoice.getInvoiceType();
        if (type == null) {
            type = new InvoiceType(
                appConfig.defaultInvoiceType(),
                invoice.getFromAddress(),
                invoice.getFromAddress(),
                "");
        }

        values.add(type.type() != null ? type.type() : "");
        values.add(invoice.getToAddress() != null ? invoice.getToAddress() : "");
        values.add(invoice.getFromAddress() != null ? invoice.getFromAddress() : "");
        values.add(type.name() != null ? type.name() : "");

        if (invoiceContent != null) {
            values.add(invoiceContent.getInvoiceId() != null ? invoiceContent.getInvoiceId() : "");
            values.add(invoiceContent.getIssuerTin() != null ? invoiceContent.getIssuerTin() : "");
            values.add(invoiceContent.getCustomerTin() != null ? invoiceContent.getCustomerTin() : "");
            values.add(invoiceContent.getInvoiceDate() != null ? invoiceContent.getInvoiceDate() : "");
            values.add(formatNumber(invoiceContent.getTotal()));
            values.add(invoiceContent.getCustomerCountry() != null ? invoiceContent.getCustomerCountry() : "");
            values.add(invoiceContent.getInvoiceType() != null ? invoiceContent.getInvoiceType() : "");
            values.add(formatNumber(invoiceContent.getNonTaxable()));
            values.add(formatNumber(invoiceContent.getStampDuty()));
            values.add(formatNumber(invoiceContent.getTotalTaxes()));
            values.add(formatNumber(invoiceContent.getWithholdingTax()));
            values.add(invoiceContent.getAtcud() != null ? invoiceContent.getAtcud() : "");

            final Taxable taxable = invoiceContent.getFirstTaxable() != null
                ? invoiceContent.getFirstTaxable()
                : invoiceContent.getSecondTaxable() != null
                    ? invoiceContent.getSecondTaxable()
                    : invoiceContent.getThirdTaxable();

            if (taxable != null) {
                values.add(taxable.taxCountryRegion() != null ? taxable.taxCountryRegion() : "");
                values.add(formatNumber(taxable.basicsExemptTaxes()));
                values.add(formatNumber(taxable.basicsReducedRate()));
                values.add(formatNumber(taxable.totalTaxesReducedRate()));
                values.add(formatNumber(taxable.basicsIntermediateRate()));
                values.add(formatNumber(taxable.totalTaxesIntermediateRate()));
                values.add(formatNumber(taxable.basicsStandardRate()));
                values.add(formatNumber(taxable.totalTaxesStandardRate()));
            } else {
                for (int i = 0; i < 8; i++) {
                    values.add("");
                }
            }

            values.add(invoiceContent.getHash() != null ? invoiceContent.getHash() : "");
            values.add(invoiceContent.getCertificateNumber() != null ? invoiceContent.getCertificateNumber() : "");
            values.add(invoiceContent.getOtherInformation() != null ? invoiceContent.getOtherInformation() : "");

            final String[] dateParts = invoiceContent.getInvoiceDate() != null
                ? invoiceContent.getInvoiceDate().split("-")
                : new String[0];
            values.add(dateParts.length == 3 ? dateParts[1] : "");
            values.add(dateParts.length == 3 ? dateParts[0] : "");

            values.add(invoiceContent.getRaw() != null ? invoiceContent.getRaw() : "");
        } else {
            for (int i = 0; i < 30; i++) {
                values.add("");
            }
        }

        values.add(invoice.getFilename() != null ? invoice.getFilename() : "");
        values.add(invoice.getId() != null ? invoice.getId() : "");
        values.add(invoice.getDate() != null ? invoice.getDate().toString() : "");
        values.add(invoice.getSubject() != null ? invoice.getSubject() : "");
        values.add(java.time.LocalDateTime.now().toString());

        return values;
    }

    private String formatNumber(final java.math.BigDecimal value) {
        return value != null ? String.format("%.2f", value) : "";
    }

    private BigDecimal parseDecimal(final Object value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value.toString().replace(",", "."));
        } catch (final Exception e) {
            return null;
        }
    }

    private Integer parseInteger(final Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (final Exception e) {
            return null;
        }
    }
}
