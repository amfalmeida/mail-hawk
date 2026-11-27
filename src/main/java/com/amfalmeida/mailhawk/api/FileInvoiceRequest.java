package com.amfalmeida.mailhawk.api;

import java.time.LocalDate;

/**
 * Body of {@code POST /api/invoices/file}. Same fields as {@link InvoiceRequest}, except that the invoice data comes
 * from the file in {@code file} (PDF or image) instead of the {@code raw} QR string.
 *
 * @param date        invoice date; used when a QR code has no {@code F} field
 * @param entityName  entity (payee) name; optional
 * @param description free-text description, stored as the invoice subject; optional
 * @param file        the PDF or image file, base64-encoded
 */
public record FileInvoiceRequest(LocalDate date, String entityName, String description, String file) { }
