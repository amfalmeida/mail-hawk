package com.amfalmeida.mailhawk.email.model;

import java.math.BigDecimal;

/**
 * Outcome of processing one QR code found in an invoice attachment.
 */
public record InvoiceResult(
        String invoiceId,
        String issuerTin,
        BigDecimal total,
        String invoiceType,
        Status status,
        String message) {

    public enum Status {
        APPENDED, ALREADY_EXISTS, ALREADY_PROCESSED, ERROR
    }

    public static InvoiceResult of(final InvoiceContent invoiceContent, final String invoiceType,
            final Status status, final String message) {
        return new InvoiceResult(
            invoiceContent.getInvoiceId(),
            invoiceContent.getIssuerTin(),
            invoiceContent.getTotal(),
            invoiceType,
            status,
            message
        );
    }
}
