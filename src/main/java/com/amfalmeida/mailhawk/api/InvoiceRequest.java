package com.amfalmeida.mailhawk.api;

import java.time.LocalDate;

/**
 * Body of {@code POST /api/invoices}. The invoice data is parsed from {@code raw}, the same way the email
 * pipeline parses the QR code found in an attachment.
 *
 * @param date        invoice date; the Sheets date column, and the invoice date when the QR code has none
 * @param entityName  entity (payee) name; the entity column in Sheets and the Actual Budget payee
 * @param description free-text description, stored as the invoice subject (Sheets subject column); optional
 * @param raw         Portuguese AT QR code string ({@code A:...*B:...*...})
 */
public record InvoiceRequest(LocalDate date, String entityName, String description, String raw) { }
