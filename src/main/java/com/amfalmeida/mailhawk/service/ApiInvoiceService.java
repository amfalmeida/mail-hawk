package com.amfalmeida.mailhawk.service;

import com.amfalmeida.common.MdcSetter;
import com.amfalmeida.mailhawk.config.MailConfig;
import com.amfalmeida.mailhawk.email.model.Invoice;
import com.amfalmeida.mailhawk.email.model.InvoiceContent;
import com.amfalmeida.mailhawk.email.model.InvoiceResult;
import com.amfalmeida.mailhawk.service.InvoiceRequestException.Reason;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Use cases of the invoice API: store one invoice from a raw AT QR code string, or every invoice found in an uploaded
 * PDF or image. Each invoice goes through {@link InvoiceProcessor#storeInvoice(Invoice, String)}, so type resolution,
 * Sheets, Actual Budget and dedupe behave the same as for email. Failures are reported as
 * {@link InvoiceRequestException}.
 */
@Slf4j
@ApplicationScoped
@RequiredArgsConstructor(onConstructor_ = @Inject)
public final class ApiInvoiceService {

    private static final String UNSUPPORTED_FILE =
        "Field 'file' must be a base64-encoded PDF, PNG, JPEG, GIF or BMP file";
    private static final Map<String, byte[]> FILE_SIGNATURES = Map.of(
        "pdf", signature("%PDF-"),
        "png", new byte[] {(byte) 0x89, 'P', 'N', 'G'},
        "jpg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF},
        "gif", signature("GIF8"),
        "bmp", signature("BM"));

    private final InvoiceProcessor invoiceProcessor;
    private final QrCodeParser qrCodeParser;
    private final MailConfig mailConfig;

    /**
     * Stores the invoice described by one raw AT QR code string.
     *
     * @param raw         AT QR code string; must contain the issuer NIF (A) and the invoice ID (G)
     * @param date        invoice date; used when the QR code has no {@code F} field
     * @param entityName  entity (payee) name; replaces the configured name when not blank
     * @param description free-text description, stored as the invoice subject; optional
     * @return the outcome of storing the invoice
     * @throws InvoiceRequestException with {@link Reason#INVALID_INPUT} when a field is missing or the QR code is
     *         invalid
     */
    public InvoiceResult processQrCode(
            final String raw,
            final LocalDate date,
            final String entityName,
            final String description) {
        return inRequest(id -> {
            final InvoiceContent content = parseRawQrCode(raw, date);
            final Invoice invoice = toInvoice(id, content, date, entityName, description, "");
            log.info("API invoice: {} | Issuer: {} | Entity: {} | Total: {}",
                content.getInvoiceId(), content.getIssuerTin(), invoice.getFromName(), content.getTotal());
            return invoiceProcessor.storeInvoice(invoice, entityName);
        });
    }

    /**
     * Stores every invoice found in a base64-encoded PDF or image. The file type is detected from its content. Each
     * valid AT QR code in the file is stored as its own invoice.
     *
     * @param base64File  the file, base64-encoded
     * @param date        invoice date; used when a QR code has no {@code F} field
     * @param entityName  entity (payee) name; optional
     * @param description free-text description, stored as the invoice subject; optional
     * @return one result per invoice stored
     * @throws InvoiceRequestException with {@link Reason#INVALID_INPUT} for a missing date or an unsupported file,
     *         {@link Reason#NO_QR_CODE} when no valid AT QR code is found, {@link Reason#STORAGE_FAILED} when the
     *         temporary file can't be written
     */
    public List<InvoiceResult> processFile(
            final String base64File,
            final LocalDate date,
            final String entityName,
            final String description) {
        return inRequest(id -> {
            final byte[] fileBytes = decodeFile(base64File, date);
            final String extension = detectExtension(fileBytes);
            if (extension == null) {
                throw unsupportedFile();
            }
            return processUploadedFile(id, fileBytes, extension, date, entityName, description);
        });
    }

    /**
     * Writes the file to a temporary directory, reads its QR codes and stores each one. The directory is always
     * removed afterwards.
     */
    private List<InvoiceResult> processUploadedFile(
            final String id,
            final byte[] fileBytes,
            final String extension,
            final LocalDate date,
            final String entityName,
            final String description) {
        final File tempDir = createTempDir();
        try {
            final String fileName = "invoice." + extension;
            final File file = writeFile(tempDir, fileName, fileBytes);

            final List<InvoiceContent> contents = readQrCodes(file.getAbsolutePath(), date);
            if (contents.isEmpty()) {
                throw new InvoiceRequestException(Reason.NO_QR_CODE, "No valid AT QR code found in the file");
            }
            return processFileInvoices(id, fileName, contents, date, entityName, description);
        } finally {
            deleteTempDir(tempDir);
        }
    }

    private List<InvoiceResult> processFileInvoices(
            final String id,
            final String fileName,
            final List<InvoiceContent> contents,
            final LocalDate date,
            final String entityName,
            final String description) {
        final List<InvoiceResult> results = new ArrayList<>();
        for (final InvoiceContent content : contents) {
            final Invoice invoice = toInvoice(id, content, date, entityName, description, fileName);
            log.info("API file invoice: {} | Issuer: {} | Entity: {} | Total: {}",
                content.getInvoiceId(), content.getIssuerTin(), invoice.getFromName(), content.getTotal());
            results.add(invoiceProcessor.storeInvoice(invoice, entityName));
        }
        return results;
    }

    /**
     * Reads every QR code in the file and keeps the valid ones. Invalid QR codes are skipped with a warning.
     */
    private List<InvoiceContent> readQrCodes(final String filePath, final LocalDate date) {
        final List<InvoiceContent> contents = new ArrayList<>();
        for (final String qrCode : qrCodeParser.getQrCodes(filePath, mailConfig.pdfPasswords())) {
            final InvoiceContent content = qrCodeParser.parseQrCodeString(qrCode);
            if (isValidContent(content)) {
                fillInvoiceDate(content, date);
                contents.add(content);
            } else {
                log.warn("Skipping invalid QR code in uploaded file: {}", qrCode);
            }
        }
        return contents;
    }

    private InvoiceContent parseRawQrCode(final String raw, final LocalDate date) {
        if (isBlank(raw)) {
            throw invalidInput("Field 'raw' is required");
        }
        requireDate(date);

        final InvoiceContent content = qrCodeParser.parseQrCodeString(raw.trim());
        if (!isValidContent(content)) {
            throw invalidInput("Field 'raw' is not a valid AT QR code: issuer NIF (A) and invoice ID (G) are required");
        }
        fillInvoiceDate(content, date);
        return content;
    }

    private static byte[] decodeFile(final String base64File, final LocalDate date) {
        if (isBlank(base64File)) {
            throw invalidInput("Field 'file' is required (base64-encoded PDF or image)");
        }
        requireDate(date);
        try {
            return Base64.getMimeDecoder().decode(base64File);
        } catch (final IllegalArgumentException e) {
            throw unsupportedFile();
        }
    }

    /**
     * @return the extension of the file type whose signature the bytes start with, or null when none matches
     */
    private static String detectExtension(final byte[] bytes) {
        return FILE_SIGNATURES.entrySet().stream()
            .filter(entry -> startsWith(bytes, entry.getValue()))
            .map(Map.Entry::getKey)
            .findFirst()
            .orElse(null);
    }

    private static boolean startsWith(final byte[] bytes, final byte[] prefix) {
        return bytes.length >= prefix.length && Arrays.equals(Arrays.copyOf(bytes, prefix.length), prefix);
    }

    private static byte[] signature(final String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static File createTempDir() {
        try {
            return Files.createTempDirectory("invoice_").toFile();
        } catch (final IOException e) {
            log.error("Could not create temp directory for file", e);
            throw storageFailed();
        }
    }

    private static File writeFile(final File tempDir, final String fileName, final byte[] fileBytes) {
        final File file = new File(tempDir, fileName);
        try {
            Files.write(file.toPath(), fileBytes);
            return file;
        } catch (final IOException e) {
            log.error("Could not write file to temp directory", e);
            throw storageFailed();
        }
    }

    private static void deleteTempDir(final File tempDir) {
        final File[] files = tempDir.listFiles();
        if (files != null) {
            for (final File file : files) {
                if (!file.delete()) {
                    log.warn("Could not delete temp file {}", file);
                }
            }
        }
        if (!tempDir.delete()) {
            log.warn("Could not delete temp directory {}", tempDir);
        }
    }

    private static Invoice toInvoice(
            final String id,
            final InvoiceContent content,
            final LocalDate date,
            final String entityName,
            final String description,
            final String filename) {
        return Invoice.builder()
            .id(id)
            .subject(trimOrEmpty(description))
            .fromAddress("")
            .fromName(trimOrEmpty(entityName))
            .toAddress("")
            .date(date)
            .filename(filename)
            .invoiceContent(content)
            .build();
    }

    /**
     * Runs one request with a fresh invoice ID, which is also the MDC correlation ID for its log lines.
     */
    private static <T> T inRequest(final Function<String, T> request) {
        final String id = "api-" + UUID.randomUUID();
        try (MdcSetter ignored = new MdcSetter(id)) {
            return request.apply(id);
        }
    }

    private static void fillInvoiceDate(final InvoiceContent content, final LocalDate date) {
        if (isBlank(content.getInvoiceDate())) {
            content.setInvoiceDate(date.toString());
        }
    }

    private static void requireDate(final LocalDate date) {
        if (date == null) {
            throw invalidInput("Field 'date' is required (yyyy-MM-dd)");
        }
    }

    private static boolean isValidContent(final InvoiceContent content) {
        return content != null && !isBlank(content.getInvoiceId()) && !isBlank(content.getIssuerTin());
    }

    private static InvoiceRequestException invalidInput(final String message) {
        return new InvoiceRequestException(Reason.INVALID_INPUT, message);
    }

    private static InvoiceRequestException unsupportedFile() {
        return invalidInput(UNSUPPORTED_FILE);
    }

    private static InvoiceRequestException storageFailed() {
        return new InvoiceRequestException(Reason.STORAGE_FAILED, "Could not store the file");
    }

    private static String trimOrEmpty(final String value) {
        return value != null ? value.trim() : "";
    }

    private static boolean isBlank(final String value) {
        return value == null || value.isBlank();
    }
}
