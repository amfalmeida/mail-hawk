package com.amfalmeida.mailhawk.service;

/**
 * Raised by {@link ApiInvoiceService} when an API request can't be stored. The {@link Reason} tells the caller which
 * kind of failure it was, so the HTTP layer can pick the status code.
 */
public final class InvoiceRequestException extends RuntimeException {

    public enum Reason {
        INVALID_INPUT, NO_QR_CODE, STORAGE_FAILED
    }

    private final Reason reason;

    public InvoiceRequestException(final Reason reason, final String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
