package com.amfalmeida.mailhawk.service;

import com.amfalmeida.mailhawk.model.QrCodeContent;
import com.amfalmeida.mailhawk.model.QrCodeContent.Taxable;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.BarcodeFormat;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@ApplicationScoped
@Slf4j
public final class QrCodeParser {

    private static final String INNER_TOKEN = ":";
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "bmp", "gif");
    private static final DateTimeFormatter[] DATE_FORMATS = {
        DateTimeFormatter.ofPattern("yyyyMMdd"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd")
    };

    public List<String> getQrCodes(final String filePath, final List<String> pdfPasswords) {
        final File file = new File(filePath);
        final String extension = getExtension(file.getName()).toLowerCase();

        if ("pdf".equals(extension)) {
            return readPdfQrCodes(file, pdfPasswords);
        } else if (IMAGE_EXTENSIONS.contains(extension)) {
            return readImageQrCodes(file);
        }

        log.warn("Unsupported file format: {}", extension);
        return List.of();
    }

    private String getExtension(final String filename) {
        final int dotIndex = filename.lastIndexOf('.');
        return dotIndex > 0 ? filename.substring(dotIndex + 1) : "";
    }

    private List<String> readImageQrCodes(final File file) {
        final List<String> qrCodes = new ArrayList<>();
        try {
            final BufferedImage image = javax.imageio.ImageIO.read(file);
            if (image == null) {
                log.error("Could not read image: {}", file.getPath());
                return qrCodes;
            }

            final BinaryBitmap bitmap = new BinaryBitmap(
                new HybridBinarizer(new BufferedImageLuminanceSource(image))
            );

            final MultiFormatReader reader = new MultiFormatReader();
            final Map<DecodeHintType, Object> hints = Map.of(
                DecodeHintType.TRY_HARDER, Boolean.TRUE,
                DecodeHintType.POSSIBLE_FORMATS, List.of(BarcodeFormat.QR_CODE)
            );

            try {
                final Result result = reader.decode(bitmap, hints);
                if (result.getText() != null && !result.getText().isEmpty()) {
                    qrCodes.add(result.getText());
                    log.info("QR Code found in image: {}", 
                        result.getText().substring(0, Math.min(50, result.getText().length())));
                }
            } catch (final Exception e) {
                log.debug("No QR code found in image: {}", e.getMessage());
            }
        } catch (final IOException e) {
            log.error("Error reading image QR codes", e);
        }
        return qrCodes;
    }

    private List<String> readPdfQrCodes(final File file, final List<String> pdfPasswords) {
        final List<String> qrCodes = new ArrayList<>();
        PDDocument document = null;

        // Try to load without password first
        try {
            document = Loader.loadPDF(file);
        } catch (final org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
            // PDF is encrypted, try passwords
            if (pdfPasswords == null || pdfPasswords.isEmpty()) {
                log.warn("PDF is encrypted but no passwords provided");
                return qrCodes;
            }
            
            boolean decrypted = false;
            for (final String password : pdfPasswords) {
                try {
                    document = Loader.loadPDF(file, password);
                    decrypted = true;
                    log.info("Successfully decrypted PDF with password");
                    break;
                } catch (final Exception ex) {
                    log.debug("Failed to decrypt with password: {}", ex.getMessage());
                }
            }
            if (!decrypted) {
                log.warn("PDF is encrypted and no valid password found");
                return qrCodes;
            }
        } catch (final IOException e) {
            log.error("Error loading PDF: {}", e.getMessage());
            return qrCodes;
        }

        try {
            final PDFRenderer renderer = new PDFRenderer(document);
            final MultiFormatReader qrReader = new MultiFormatReader();
            final Map<DecodeHintType, Object> hints = Map.of(
                DecodeHintType.TRY_HARDER, Boolean.TRUE,
                DecodeHintType.POSSIBLE_FORMATS, List.of(BarcodeFormat.QR_CODE)
            );

            for (int pageNum = 0; pageNum < document.getNumberOfPages(); pageNum++) {
                log.debug("Processing page {}", pageNum + 1);

                try {
                    final BufferedImage image = renderer.renderImage(pageNum, 2.0f);
                    final BinaryBitmap bitmap = new BinaryBitmap(
                        new HybridBinarizer(new BufferedImageLuminanceSource(image))
                    );

                    try {
                        final Result result = qrReader.decode(bitmap, hints);
                        if (result.getText() != null && !result.getText().isEmpty()) {
                            qrCodes.add(result.getText());
                            log.info("QR Code found on page {}: {}", 
                                pageNum + 1,
                                result.getText().substring(0, Math.min(50, result.getText().length())));
                        }
                    } catch (final Exception e) {
                        log.debug("No QR code on page {}: {}", pageNum + 1, e.getMessage());
                    }
                } catch (final IOException e) {
                    log.debug("Error rendering page {}: {}", pageNum + 1, e.getMessage());
                }
            }
        } catch (final Exception e) {
            log.error("Error reading PDF QR codes", e);
        } finally {
            if (document != null) {
                try {
                    document.close();
                } catch (final Exception e) {
                    log.debug("Error closing document: {}", e.getMessage());
                }
            }
        }
        return qrCodes;
    }

    public QrCodeContent parseQrCodeString(final String qrString) {
        final String[] tokens = qrString.split("\\*");
        final QrCodeContent content = new QrCodeContent();
        final Map<String, String> firstTaxable = new HashMap<>();
        final Map<String, String> secondTaxable = new HashMap<>();
        final Map<String, String> thirdTaxable = new HashMap<>();

        for (final String token : tokens) {
            if (!token.contains(INNER_TOKEN)) continue;

            final int colonIndex = token.indexOf(INNER_TOKEN);
            final String key = token.substring(0, colonIndex);
            final String value = token.substring(colonIndex + 1);

            switch (key) {
                case "A" -> content.setIssuerTin(value);
                case "B" -> content.setCustomerTin(value);
                case "C" -> content.setCustomerCountry(value);
                case "D" -> content.setInvoiceType(value);
                case "E" -> content.setStatus(value);
                case "F" -> content.setInvoiceDate(parseDate(value));
                case "G" -> content.setInvoiceId(value);
                case "H" -> content.setAtcud(value);
                case "L" -> content.setNonTaxable(parseAmount(value));
                case "M" -> content.setStampDuty(parseAmount(value));
                case "N" -> content.setTotalTaxes(parseAmount(value));
                case "O" -> content.setTotal(parseAmount(value));
                case "P" -> content.setWithholdingTax(parseAmount(value));
                case "Q" -> content.setHash(value);
                case "R" -> content.setCertificateNumber(value);
                case "S" -> content.setOtherInformation(value);
                default -> {
                    if (key.length() == 2 && (key.startsWith("I") || key.startsWith("J") || key.startsWith("K"))) {
                        final String group = key.substring(0, 1);
                        final String subKey = key.substring(1);
                        final Map<String, String> taxable = group.equals("I") ? firstTaxable : 
                                                             group.equals("J") ? secondTaxable : thirdTaxable;
                        parseTaxable(taxable, subKey, value);
                    }
                }
            }
        }

        if (!firstTaxable.isEmpty()) {
            content.setFirstTaxable(buildTaxable(firstTaxable));
        }
        if (!secondTaxable.isEmpty()) {
            content.setSecondTaxable(buildTaxable(secondTaxable));
        }
        if (!thirdTaxable.isEmpty()) {
            content.setThirdTaxable(buildTaxable(thirdTaxable));
        }

        content.setRaw(qrString);
        return content;
    }

    private void parseTaxable(final Map<String, String> taxable, final String key, final String value) {
        taxable.put(switch (key) {
            case "1" -> "taxCountryRegion";
            case "2" -> "basicsExemptTaxes";
            case "3" -> "basicsReducedRate";
            case "4" -> "totalTaxesReducedRate";
            case "5" -> "basicsIntermediateRate";
            case "6" -> "totalTaxesIntermediateRate";
            case "7" -> "basicsStandardRate";
            case "8" -> "totalTaxesStandardRate";
            default -> key;
        }, value);
    }

    private Taxable buildTaxable(final Map<String, String> data) {
        return new Taxable(
            data.getOrDefault("taxCountryRegion", ""),
            parseAmount(data.get("basicsExemptTaxes")),
            parseAmount(data.get("basicsReducedRate")),
            parseAmount(data.get("totalTaxesReducedRate")),
            parseAmount(data.get("basicsIntermediateRate")),
            parseAmount(data.get("totalTaxesIntermediateRate")),
            parseAmount(data.get("basicsStandardRate")),
            parseAmount(data.get("totalTaxesStandardRate"))
        );
    }

    private String parseDate(final String dateStr) {
        for (final DateTimeFormatter fmt : DATE_FORMATS) {
            try {
                final LocalDate date = LocalDate.parse(dateStr, fmt);
                return date.toString();
            } catch (final Exception ignored) {}
        }
        return dateStr;
    }

    private BigDecimal parseAmount(final String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return new BigDecimal(value.replace(",", "."));
        } catch (final NumberFormatException e) {
            return null;
        }
    }
}