package com.amfalmeida.mailhawk.service;

import com.amfalmeida.mailhawk.email.model.InvoiceContent;
import com.amfalmeida.mailhawk.email.model.InvoiceContent.Taxable;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.multi.GenericMultipleBarcodeReader;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
@Slf4j
public final class QrCodeParser {

    private static final String INNER_TOKEN = ":";
    private static final int[] RENDER_DPIS = {250, 400};
    private static final Map<DecodeHintType, Object> DECODE_HINTS = Map.of(
        DecodeHintType.TRY_HARDER, Boolean.TRUE,
        DecodeHintType.ALSO_INVERTED, Boolean.TRUE,
        DecodeHintType.POSSIBLE_FORMATS, List.of(BarcodeFormat.QR_CODE)
    );
    private static final DateTimeFormatter[] DATE_FORMATS = {
        DateTimeFormatter.ofPattern("yyyyMMdd"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd"),
        DateTimeFormatter.ofPattern("dd/MM/yyyy"),
        DateTimeFormatter.ofPattern("dd-MM-yyyy")
    };

    public List<String> getQrCodes(final String filePath, final List<String> pdfPasswords) {
        final File file = new File(filePath);
        if (!file.exists()) {
            log.warn("File not found: {}", filePath);
            return List.of();
        }

        final String lowerPath = filePath.toLowerCase();
        if (FileTypes.isPdf(lowerPath)) {
            return extractQrCodesFromPdf(file, pdfPasswords);
        } else if (FileTypes.isImage(lowerPath)) {
            return extractQrCodesFromImage(file);
        }

        return List.of();
    }

    public InvoiceContent parseQrCodeString(final String qrString) {
        final String[] tokens = qrString.split("\\*");
        final Map<String, String> firstTaxable = new HashMap<>();
        final Map<String, String> secondTaxable = new HashMap<>();
        final Map<String, String> thirdTaxable = new HashMap<>();

        String issuerTin = null;
        String customerTin = null;
        String customerCountry = null;
        String invoiceType = null;
        String status = null;
        String invoiceDate = null;
        String invoiceId = null;
        String atcud = null;
        BigDecimal nonTaxable = null;
        BigDecimal stampDuty = null;
        BigDecimal totalTaxes = null;
        BigDecimal total = null;
        BigDecimal withholdingTax = null;
        String hash = null;
        String certificateNumber = null;
        String otherInformation = null;

        for (final String token : tokens) {
            if (!token.contains(INNER_TOKEN)) {
                continue;
            }

            final int colonIndex = token.indexOf(INNER_TOKEN);
            final String key = token.substring(0, colonIndex);
            final String value = token.substring(colonIndex + 1);

            switch (key) {
                case "A" -> issuerTin = value;
                case "B" -> customerTin = value;
                case "C" -> customerCountry = value;
                case "D" -> invoiceType = value;
                case "E" -> status = value;
                case "F" -> invoiceDate = parseDate(value);
                case "G" -> invoiceId = value;
                case "H" -> atcud = value;
                case "L" -> nonTaxable = parseAmount(value);
                case "M" -> stampDuty = parseAmount(value);
                case "N" -> totalTaxes = parseAmount(value);
                case "O" -> total = parseAmount(value);
                case "P" -> withholdingTax = parseAmount(value);
                case "Q" -> hash = value;
                case "R" -> certificateNumber = value;
                case "S" -> otherInformation = value;
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

        return InvoiceContent.builder()
                .issuerTin(issuerTin)
                .customerTin(customerTin)
                .customerCountry(customerCountry)
                .invoiceType(invoiceType)
                .status(status)
                .invoiceDate(invoiceDate)
                .invoiceId(invoiceId)
                .atcud(atcud)
                .nonTaxable(nonTaxable)
                .stampDuty(stampDuty)
                .totalTaxes(totalTaxes)
                .total(total)
                .withholdingTax(withholdingTax)
                .hash(hash)
                .certificateNumber(certificateNumber)
                .otherInformation(otherInformation)
                .firstTaxable(firstTaxable.isEmpty() ? null : buildTaxable(firstTaxable))
                .secondTaxable(secondTaxable.isEmpty() ? null : buildTaxable(secondTaxable))
                .thirdTaxable(thirdTaxable.isEmpty() ? null : buildTaxable(thirdTaxable))
                .raw(qrString)
                .build();
    }

    private List<String> extractQrCodesFromPdf(final File file, final List<String> passwords) {
        try {
            return tryExtractFromPdf(file, null);
        } catch (InvalidPasswordException e) {
            log.debug("PDF is password protected, trying configured passwords");
        } catch (IOException e) {
            log.error("Failed to read PDF: {}", file.getAbsolutePath(), e);
            return List.of();
        }

        if (passwords != null && !passwords.isEmpty()) {
            for (final String password : passwords) {
                try {
                    final List<String> result = tryExtractFromPdf(file, password);
                    if (!result.isEmpty()) {
                        return result;
                    }
                } catch (InvalidPasswordException e) {
                    log.debug("Password didn't work for PDF: {}", file.getAbsolutePath());
                } catch (IOException e) {
                    log.error("Failed to read PDF: {}", file.getAbsolutePath(), e);
                }
            }
        }
        return List.of();
    }

    private List<String> tryExtractFromPdf(final File file, final String password) throws IOException {
        try (PDDocument document = password != null && !password.isEmpty()
                ? Loader.loadPDF(file, password)
                : Loader.loadPDF(file)) {
            final List<String> embedded = extractQrCodesFromEmbeddedImages(document);
            if (!embedded.isEmpty()) {
                log.debug("Found {} QR codes in embedded PDF images", embedded.size());
                return embedded;
            }
            return extractQrCodesFromRenderedPages(document);
        }
    }

    /**
     * Fast path: scans the image objects embedded in each page. Most invoice QR codes are embedded
     * images, and decoding them avoids rendering the page, which dominates the cost on long PDFs.
     */
    private List<String> extractQrCodesFromEmbeddedImages(final PDDocument document) {
        final Set<String> qrCodes = new LinkedHashSet<>();
        for (final PDPage page : document.getPages()) {
            final PDResources resources = page.getResources();
            if (resources == null) {
                continue;
            }
            for (final COSName name : resources.getXObjectNames()) {
                try {
                    if (resources.getXObject(name) instanceof PDImageXObject image) {
                        qrCodes.addAll(scanImage(image.getImage()));
                    }
                } catch (final Exception e) {
                    log.debug("Failed to read embedded image {}: {}", name.getName(), e.getMessage());
                }
            }
        }
        return new ArrayList<>(qrCodes);
    }

    /**
     * Slow path for QR codes drawn as vector graphics or nested in form objects: renders every page,
     * retrying at a higher DPI if nothing was found.
     */
    private List<String> extractQrCodesFromRenderedPages(final PDDocument document) {
        final List<String> qrCodes = new ArrayList<>();
        final PDFRenderer renderer = new PDFRenderer(document);
        renderer.setSubsamplingAllowed(true);

        for (final int dpi : RENDER_DPIS) {
            if (!qrCodes.isEmpty()) {
                break;
            }
            for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); pageIndex++) {
                try {
                    final BufferedImage image = renderer.renderImageWithDPI(pageIndex, dpi, ImageType.GRAY);
                    qrCodes.addAll(extractQrCodesFromImage(image));
                } catch (final Exception e) {
                    log.debug("Failed to render page {} at {} DPI: {}", pageIndex, dpi, e.getMessage());
                }
            }
        }
        return qrCodes;
    }

    private List<String> extractQrCodesFromImage(final File file) {
        try {
            final BufferedImage image = javax.imageio.ImageIO.read(file);
            if (image == null) {
                log.warn("Could not read image file: {}", file.getAbsolutePath());
                return List.of();
            }
            return extractQrCodesFromImage(image);
        } catch (IOException e) {
            log.error("Failed to read image: {}", file.getAbsolutePath(), e);
            return List.of();
        }
    }

    private List<String> extractQrCodesFromImage(final BufferedImage image) {
        List<String> qrCodes = scanImage(image);

        if (qrCodes.isEmpty()) {
            qrCodes = scanImage(enhanceContrast(image));
        }

        if (!qrCodes.isEmpty()) {
            qrCodes.sort((a, b) -> Integer.compare(b.length(), a.length()));
        }

        return qrCodes;
    }

    private List<String> scanImage(final BufferedImage image) {
        final List<String> qrCodes = new ArrayList<>();

        try {
            final BinaryBitmap bitmap = new BinaryBitmap(
                new HybridBinarizer(
                    new BufferedImageLuminanceSource(image)
                )
            );

            final MultiFormatReader reader = new MultiFormatReader();
            final GenericMultipleBarcodeReader multiReader = new GenericMultipleBarcodeReader(reader);

            final Result[] results = multiReader.decodeMultiple(bitmap, DECODE_HINTS);

            for (final Result result : results) {
                if (result.getText() != null && !result.getText().isEmpty()) {
                    qrCodes.add(result.getText().trim());
                }
            }
        } catch (final Exception e) {
            log.debug("No QR code found in image: {}", e.getMessage());
        }

        return qrCodes;
    }

    /**
     * Returns a grayscale copy of {@code image} with its gray levels stretched to the full 0-255 range.
     * Works on the raster bytes directly; per-pixel getRGB/setRGB is far slower on large renders.
     */
    private static BufferedImage enhanceContrast(final BufferedImage image) {
        final BufferedImage result = new BufferedImage(
            image.getWidth(), image.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        final Graphics2D graphics = result.createGraphics();
        try {
            graphics.drawImage(image, 0, 0, null);
        } finally {
            graphics.dispose();
        }

        final byte[] pixels = ((DataBufferByte) result.getRaster().getDataBuffer()).getData();
        int min = 255;
        int max = 0;
        for (final byte pixel : pixels) {
            final int gray = pixel & 0xFF;
            min = Math.min(min, gray);
            max = Math.max(max, gray);
        }

        final int range = max - min;
        if (range > 0 && range < 255) {
            for (int i = 0; i < pixels.length; i++) {
                pixels[i] = (byte) (((pixels[i] & 0xFF) - min) * 255 / range);
            }
        }

        return result;
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
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(value.replace(",", "."));
        } catch (final NumberFormatException e) {
            return null;
        }
    }
}
