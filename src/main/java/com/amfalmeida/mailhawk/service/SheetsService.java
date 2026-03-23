package com.amfalmeida.mailhawk.service;

import com.amfalmeida.mailhawk.config.AppConfig;
import com.amfalmeida.mailhawk.config.SheetsConfig;
import com.amfalmeida.mailhawk.model.Invoice;
import com.amfalmeida.mailhawk.model.InvoiceType;
import com.amfalmeida.mailhawk.model.InvoiceContent;
import com.amfalmeida.mailhawk.model.InvoiceContent.Taxable;
import com.amfalmeida.mailhawk.model.RecurrentBill;
import com.amfalmeida.mailhawk.model.SheetsResult;
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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import jakarta.inject.Inject;

@ApplicationScoped
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public final class SheetsService {

    private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();

    private final SheetsConfig sheetsConfig;
    private final DatabaseService databaseService;
    private final AppConfig appConfig;

    private Sheets sheetsService;
    private final Map<String, InvoiceType> configCacheByNif = new ConcurrentHashMap<>();
    private final Map<String, InvoiceType> configCacheByEmail = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        try {
            final String encodedCredentials = getSheetsCredentials();
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

    private String getSheetsCredentials() {
        try {
            return sheetsConfig.encodedCredentials();
        } catch (final Exception e) {
            log.debug("Getting credentials from config: {}", e.getMessage());
            return null;
        }
    }

    private String getSpreadsheetId() {
        try {
            return sheetsConfig.id();
        } catch (final Exception e) {
            return null;
        }
    }

    private String getSheetName() {
        try {
            return sheetsConfig.sheetName();
        } catch (final Exception e) {
            return "Bills values";
        }
    }

    private String getConfigSheet() {
        try {
            return sheetsConfig.configSheet().replace("\"", "");
        } catch (final Exception e) {
            return "config";
        }
    }

    private String getRecurrentSheet() {
        try {
            return sheetsConfig.recurrentSheet().replace("\"", "");
        } catch (final Exception e) {
            return "recurrent";
        }
    }

    private String buildRange(String sheetName, String range) {
        // For Google Sheets API v4, just use sheet name directly (no quotes)
        // Spaces are handled by the HTTP client's URL encoding
        return sheetName + "!" + range;
    }

    public SheetsResult addInvoice(final Invoice invoice) {
        if (sheetsService == null) {
            return SheetsResult.error("Google Sheets service not initialized");
        }

        if (invoice.getInvoiceContent() == null) {
            return SheetsResult.error("No QR code data");
        }

        final String spreadsheetId = getSpreadsheetId();
        if (spreadsheetId == null || spreadsheetId.isEmpty()) {
            return SheetsResult.error("Spreadsheet ID not configured");
        }

        try {
            if (checkExisting(invoice)) {
                return SheetsResult.alreadyExists("Invoice already exists in spreadsheet");
            }

            final List<Object> values = getValues(invoice);
            final ValueRange body = new ValueRange().setValues(List.of(values));

            sheetsService.spreadsheets().values()
                .append(spreadsheetId, buildRange(getSheetName(), "A:A"), body)
                .setValueInputOption("USER_ENTERED")
                .execute();

            log.info("Appended invoice to spreadsheet");
            return SheetsResult.appended("Invoice added successfully");

        } catch (final Exception e) {
            log.error("Error adding invoice to spreadsheet", e);
            return SheetsResult.error(e.getMessage());
        }
    }

    private boolean checkExisting(final Invoice invoice) {
        try {
            final String spreadsheetId = getSpreadsheetId();
            if (spreadsheetId == null) return false;

            final ValueRange result = sheetsService.spreadsheets().values()
                .get(spreadsheetId, buildRange(getSheetName(), "AD:AD"))
                .execute();

            final List<List<Object>> values = result.getValues();
            if (values == null) return false;

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
            type = new InvoiceType(appConfig.defaultInvoiceType(), invoice.getFromAddress(), invoice.getFromAddress(), "");
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

            final Taxable taxable = invoiceContent.getFirstTaxable() != null ? invoiceContent.getFirstTaxable() :
                                     invoiceContent.getSecondTaxable() != null ? invoiceContent.getSecondTaxable() :
                                     invoiceContent.getThirdTaxable();

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
                for (int i = 0; i < 8; i++) values.add("");
            }

            values.add(invoiceContent.getHash() != null ? invoiceContent.getHash() : "");
            values.add(invoiceContent.getCertificateNumber() != null ? invoiceContent.getCertificateNumber() : "");
            values.add(invoiceContent.getOtherInformation() != null ? invoiceContent.getOtherInformation() : "");

            final String[] dateParts = invoiceContent.getInvoiceDate() != null ? invoiceContent.getInvoiceDate().split("-") : new String[0];
            values.add(dateParts.length == 3 ? dateParts[1] : "");
            values.add(dateParts.length == 3 ? dateParts[0] : "");

            values.add(invoiceContent.getRaw() != null ? invoiceContent.getRaw() : "");
        } else {
            for (int i = 0; i < 30; i++) values.add("");
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

    public Optional<InvoiceType> getConfigurationByNif(final String nif) {
        final InvoiceType cached = configCacheByNif.get(nif);
        if (cached != null) return Optional.of(cached);

        final Optional<InvoiceType> dbConfig = databaseService.getConfigByNif(nif);
        if (dbConfig.isPresent()) {
            configCacheByNif.put(nif, dbConfig.get());
            return dbConfig;
        }

        try {
            final List<InvoiceType> configs = getConfigurations();
            for (final InvoiceType config : configs) {
                if (config.nif().trim().equals(nif.trim())) {
                    configCacheByNif.put(nif, config);
                    databaseService.saveConfig(config.type(), config.fromEmail(), config.name(), config.nif());
                    return Optional.of(config);
                }
            }
        } catch (final Exception e) {
            log.error("Error getting configurations from sheet", e);
        }

        return Optional.empty();
    }

    public Optional<InvoiceType> getConfigurationByEmail(final String email) {
        final InvoiceType cached = configCacheByEmail.get(email);
        if (cached != null) return Optional.of(cached);

        final Optional<InvoiceType> dbConfig = databaseService.getConfigByEmail(email);
        if (dbConfig.isPresent()) {
            configCacheByEmail.put(email, dbConfig.get());
            return dbConfig;
        }

        try {
            final List<InvoiceType> configs = getConfigurations();
            for (final InvoiceType config : configs) {
                if (config.fromEmail().trim().equals(email.trim())) {
                    configCacheByEmail.put(email, config);
                    databaseService.saveConfig(config.type(), config.fromEmail(), config.name(), config.nif());
                    return Optional.of(config);
                }
            }
        } catch (final Exception e) {
            log.error("Error getting configurations from sheet", e);
        }

        return Optional.empty();
    }

    public List<InvoiceType> getConfigurations() {
        if (sheetsService == null) return List.of();

        try {
            final String spreadsheetId = getSpreadsheetId();
            if (spreadsheetId == null) return List.of();

            final ValueRange result = sheetsService.spreadsheets().values()
                .get(spreadsheetId, buildRange(getConfigSheet(), "A:E"))
                .execute();

            final List<List<Object>> values = result.getValues();
            if (values == null || values.size() <= 1) return List.of();

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

    public List<RecurrentBill> getRecurrentBills() {
        if (sheetsService == null) return List.of();

        try {
            final String spreadsheetId = getSpreadsheetId();
            if (spreadsheetId == null) return List.of();

            final ValueRange result = sheetsService.spreadsheets().values()
                .get(spreadsheetId, buildRange(getRecurrentSheet(), "A:K"))
                .execute();

            final List<List<Object>> values = result.getValues();
            if (values == null || values.size() <= 1) return List.of();

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

    private BigDecimal parseDecimal(final Object value) {
        if (value == null) return null;
        try {
            return new BigDecimal(value.toString().replace(",", "."));
        } catch (final Exception e) {
            return null;
        }
    }

    private Integer parseInteger(final Object value) {
        if (value == null) return null;
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (final Exception e) {
            return null;
        }
    }
}