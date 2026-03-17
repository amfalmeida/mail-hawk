package com.amfalmeida.mailhawk.db.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "processed_invoices")
public class ProcessedInvoice extends PanacheEntityBase {

    @Id
    @Column(name = "raw_qr_hash")
    public String rawQrHash;

    @Column(name = "atcud")
    public String atcud;

    @Column(name = "processed_at")
    public LocalDateTime processedAt;

    @Column(name = "invoice_id")
    public String invoiceId;

    @Column(name = "invoice_date")
    public String invoiceDate;

    @Column(name = "issuer_name")
    public String issuerName;

    @Column(name = "issuer_nif")
    public String issuerNif;

    @Column(name = "customer_nif")
    public String customerNif;

    @Column(name = "customer_country")
    public String customerCountry;

    @Column(name = "invoice_type")
    public String invoiceType;

    @Column(name = "status")
    public String status;

    @Column(name = "total")
    public BigDecimal total;

    @Column(name = "total_taxes")
    public BigDecimal totalTaxes;

    @Column(name = "non_taxable")
    public BigDecimal nonTaxable;

    @Column(name = "stamp_duty")
    public BigDecimal stampDuty;

    @Column(name = "withholding_tax")
    public BigDecimal withholdingTax;

    @Column(name = "hash")
    public String hash;

    @Column(name = "certificate_number")
    public String certificateNumber;

    @Column(name = "other_information")
    public String otherInformation;

    @Column(name = "raw_qr", columnDefinition = "TEXT")
    public String rawQr;

    @Column(name = "subject")
    public String subject;

    @Column(name = "from_email")
    public String fromEmail;

    @Column(name = "from_name")
    public String fromName;

    @Column(name = "to_email")
    public String toEmail;

    @Column(name = "tax_country_region")
    public String taxCountryRegion;

    @Column(name = "basics_exempt_taxes")
    public BigDecimal basicsExemptTaxes;

    @Column(name = "basics_reduced_rate")
    public BigDecimal basicsReducedRate;

    @Column(name = "total_taxes_reduced_rate")
    public BigDecimal totalTaxesReducedRate;

    @Column(name = "basics_intermediate_rate")
    public BigDecimal basicsIntermediateRate;

    @Column(name = "total_taxes_intermediate_rate")
    public BigDecimal totalTaxesIntermediateRate;

    @Column(name = "basics_standard_rate")
    public BigDecimal basicsStandardRate;

    @Column(name = "total_taxes_standard_rate")
    public BigDecimal totalTaxesStandardRate;

    @Column(name = "tax_country_region_2")
    public String taxCountryRegion2;

    @Column(name = "basics_exempt_taxes_2")
    public BigDecimal basicsExemptTaxes2;

    @Column(name = "basics_reduced_rate_2")
    public BigDecimal basicsReducedRate2;

    @Column(name = "total_taxes_reduced_rate_2")
    public BigDecimal totalTaxesReducedRate2;

    @Column(name = "basics_intermediate_rate_2")
    public BigDecimal basicsIntermediateRate2;

    @Column(name = "total_taxes_intermediate_rate_2")
    public BigDecimal totalTaxesIntermediateRate2;

    @Column(name = "basics_standard_rate_2")
    public BigDecimal basicsStandardRate2;

    @Column(name = "total_taxes_standard_rate_2")
    public BigDecimal totalTaxesStandardRate2;

    @Column(name = "tax_country_region_3")
    public String taxCountryRegion3;

    @Column(name = "basics_exempt_taxes_3")
    public BigDecimal basicsExemptTaxes3;

    @Column(name = "basics_reduced_rate_3")
    public BigDecimal basicsReducedRate3;

    @Column(name = "total_taxes_reduced_rate_3")
    public BigDecimal totalTaxesReducedRate3;

    @Column(name = "basics_intermediate_rate_3")
    public BigDecimal basicsIntermediateRate3;

    @Column(name = "total_taxes_intermediate_rate_3")
    public BigDecimal totalTaxesIntermediateRate3;

    @Column(name = "basics_standard_rate_3")
    public BigDecimal basicsStandardRate3;

    @Column(name = "total_taxes_standard_rate_3")
    public BigDecimal totalTaxesStandardRate3;

    public static ProcessedInvoice findByRawQrHash(String hash) {
        return find("rawQrHash", hash).firstResult();
    }
}
