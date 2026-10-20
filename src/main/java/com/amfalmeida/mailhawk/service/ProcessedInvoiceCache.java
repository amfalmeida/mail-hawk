package com.amfalmeida.mailhawk.service;

import com.amfalmeida.mailhawk.config.AppConfig;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

/**
 * In-memory record of invoices that were already processed, keyed by the SHA-256 of the raw QR string.
 * Entries expire after {@code app.processed-invoice-cache-hours} (default 24h); after that, the
 * Google Sheets duplicate check is the remaining safeguard.
 */
@Slf4j
@ApplicationScoped
@RequiredArgsConstructor(onConstructor_ = @Inject)
public final class ProcessedInvoiceCache {

    private final AppConfig appConfig;

    private Cache<String, LocalDateTime> processedInvoices;

    @PostConstruct
    void initCache() {
        this.processedInvoices = Caffeine.newBuilder()
            .maximumSize(appConfig.processedInvoiceCacheSize())
            .expireAfterWrite(appConfig.processedInvoiceCacheHours(), TimeUnit.HOURS)
            .build();
    }

    public boolean isProcessed(final String rawQr) {
        return processedInvoices.getIfPresent(hash(rawQr)) != null;
    }

    public void markProcessed(final String rawQr) {
        processedInvoices.put(hash(rawQr), LocalDateTime.now());
        log.debug("Marked invoice as processed: {}", rawQr);
    }

    private static String hash(final String rawQr) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawQr.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
