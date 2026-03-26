package com.amfalmeida.mailhawk.service;

import com.amfalmeida.mailhawk.db.entity.InvoiceConfig;
import com.amfalmeida.mailhawk.db.entity.ProcessedInvoice;
import com.amfalmeida.mailhawk.model.InvoiceType;
import com.amfalmeida.mailhawk.model.InvoiceContent.Taxable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public final class DatabaseService {

    public boolean isInvoiceProcessedByRawQr(final String rawQr) {
        final String hash = hashRawQr(rawQr);
        return ProcessedInvoice.findByRawQrHash(hash) != null;
    }

    @Transactional
    public boolean markInvoiceProcessed(
            final String atcud, final String invoiceId, final String invoiceDate,
            final String issuerName, final String issuerNif, final String customerNif,
            final String customerCountry, final String invoiceType, final String status,
            final BigDecimal total, final BigDecimal totalTaxes, final BigDecimal nonTaxable,
            final BigDecimal stampDuty, final BigDecimal withholdingTax, final String hash,
            final String certificateNumber, final String otherInformation, final String rawQr,
            final String subject, final String fromEmail, final String fromName, final String toEmail,
            final Taxable firstTaxable, final Taxable secondTaxable, final Taxable thirdTaxable) {

        final String rawQrHash = hashRawQr(rawQr);

        if (ProcessedInvoice.findByRawQrHash(rawQrHash) != null) {
            log.debug("Invoice already processed: {}", rawQr);
            return true;
        }

        final ProcessedInvoice invoice = new ProcessedInvoice();
        invoice.rawQrHash = rawQrHash;
        invoice.atcud = atcud;
        invoice.processedAt = LocalDateTime.now();
        invoice.invoiceId = invoiceId;
        invoice.invoiceDate = invoiceDate;
        invoice.issuerName = issuerName;
        invoice.issuerNif = issuerNif;
        invoice.customerNif = customerNif;
        invoice.customerCountry = customerCountry;
        invoice.invoiceType = invoiceType;
        invoice.status = status;
        invoice.total = total;
        invoice.totalTaxes = totalTaxes;
        invoice.nonTaxable = nonTaxable;
        invoice.stampDuty = stampDuty;
        invoice.withholdingTax = withholdingTax;
        invoice.hash = hash;
        invoice.certificateNumber = certificateNumber;
        invoice.otherInformation = otherInformation;
        invoice.rawQr = rawQr;
        invoice.subject = subject;
        invoice.fromEmail = fromEmail;
        invoice.fromName = fromName;
        invoice.toEmail = toEmail;

        if (firstTaxable != null) {
            invoice.taxCountryRegion = firstTaxable.taxCountryRegion();
            invoice.basicsExemptTaxes = firstTaxable.basicsExemptTaxes();
            invoice.basicsReducedRate = firstTaxable.basicsReducedRate();
            invoice.totalTaxesReducedRate = firstTaxable.totalTaxesReducedRate();
            invoice.basicsIntermediateRate = firstTaxable.basicsIntermediateRate();
            invoice.totalTaxesIntermediateRate = firstTaxable.totalTaxesIntermediateRate();
            invoice.basicsStandardRate = firstTaxable.basicsStandardRate();
            invoice.totalTaxesStandardRate = firstTaxable.totalTaxesStandardRate();
        }

        if (secondTaxable != null) {
            invoice.taxCountryRegion2 = secondTaxable.taxCountryRegion();
            invoice.basicsExemptTaxes2 = secondTaxable.basicsExemptTaxes();
            invoice.basicsReducedRate2 = secondTaxable.basicsReducedRate();
            invoice.totalTaxesReducedRate2 = secondTaxable.totalTaxesReducedRate();
            invoice.basicsIntermediateRate2 = secondTaxable.basicsIntermediateRate();
            invoice.totalTaxesIntermediateRate2 = secondTaxable.totalTaxesIntermediateRate();
            invoice.basicsStandardRate2 = secondTaxable.basicsStandardRate();
            invoice.totalTaxesStandardRate2 = secondTaxable.totalTaxesStandardRate();
        }

        if (thirdTaxable != null) {
            invoice.taxCountryRegion3 = thirdTaxable.taxCountryRegion();
            invoice.basicsExemptTaxes3 = thirdTaxable.basicsExemptTaxes();
            invoice.basicsReducedRate3 = thirdTaxable.basicsReducedRate();
            invoice.totalTaxesReducedRate3 = thirdTaxable.totalTaxesReducedRate();
            invoice.basicsIntermediateRate3 = thirdTaxable.basicsIntermediateRate();
            invoice.totalTaxesIntermediateRate3 = thirdTaxable.totalTaxesIntermediateRate();
            invoice.basicsStandardRate3 = thirdTaxable.basicsStandardRate();
            invoice.totalTaxesStandardRate3 = thirdTaxable.totalTaxesStandardRate();
        }

        try {
            invoice.persist();
            log.debug("Marked invoice as processed: {}", atcud);
            return true;
        } catch (final Exception e) {
            log.error("Error marking invoice as processed", e);
            return false;
        }
    }

    public Optional<InvoiceType> getConfigByNif(final String nif) {
        final InvoiceConfig config = InvoiceConfig.findByNif(nif);
        if (config != null) {
            return Optional.of(new InvoiceType(config.type, config.fromEmail, config.name, config.nif));
        }
        return Optional.empty();
    }

    public Optional<InvoiceType> getConfigByEmail(final String email) {
        final InvoiceConfig config = InvoiceConfig.findByEmail(email);
        if (config != null) {
            return Optional.of(new InvoiceType(config.type, config.fromEmail, config.name, config.nif));
        }
        return Optional.empty();
    }

    @Transactional
    public void saveConfig(final String type, final String fromEmail, final String name, final String nif) {
        final String id = nif + "_" + fromEmail;
        InvoiceConfig config = InvoiceConfig.find("id", id).firstResult();

        if (config == null) {
            config = new InvoiceConfig();
            config.id = id;
        }

        config.type = type;
        config.fromEmail = fromEmail;
        config.name = name;
        config.nif = nif;

        try {
            config.persist();
        } catch (final Exception e) {
            log.error("Error saving config", e);
        }
    }

    @Transactional
    public void syncConfigs(final List<InvoiceType> configs) {
        for (final InvoiceType config : configs) {
            saveConfig(config.type(), config.fromEmail(), config.name(), config.nif());
        }
        log.info("Synced {} configs from sheets", configs.size());
    }

    private String hashRawQr(final String rawQr) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] hash = digest.digest(rawQr.getBytes("UTF-8"));
            final StringBuilder hexString = new StringBuilder();
            for (final byte b : hash) {
                final String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (final Exception e) {
            throw new RuntimeException("Failed to hash raw QR", e);
        }
    }
}
