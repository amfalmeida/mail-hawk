package com.amfalmeida.mailhawk.model;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public final class InvoiceContent {
    private String issuerTin;
    private String customerTin;
    private String customerCountry;
    private String invoiceType;
    private String status;
    private String invoiceDate;
    private String invoiceId;
    private String atcud;
    private Taxable firstTaxable;
    private Taxable secondTaxable;
    private Taxable thirdTaxable;
    private BigDecimal nonTaxable;
    private BigDecimal stampDuty;
    private BigDecimal totalTaxes;
    private BigDecimal total;
    private BigDecimal withholdingTax;
    private String hash;
    private String certificateNumber;
    private String otherInformation;
    private String raw;

    public record Taxable(
            String taxCountryRegion,
            BigDecimal basicsExemptTaxes,
            BigDecimal basicsReducedRate,
            BigDecimal totalTaxesReducedRate,
            BigDecimal basicsIntermediateRate,
            BigDecimal totalTaxesIntermediateRate,
            BigDecimal basicsStandardRate,
            BigDecimal totalTaxesStandardRate
    ) {}
}