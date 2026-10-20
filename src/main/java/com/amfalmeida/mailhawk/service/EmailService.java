package com.amfalmeida.mailhawk.service;

import com.amfalmeida.mailhawk.config.AppConfig;
import com.amfalmeida.mailhawk.email.EmailProcessor;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Scheduling only: runs the email check job on a configured interval and
 * delegates the actual work to {@link EmailProcessor} and {@link InvoiceProcessor}.
 * The last check time expires after {@code app.last-checked-cache-hours}; once gone, the search
 * falls back to {@code mail.days-older}.
 */
@Slf4j
@ApplicationScoped
@RequiredArgsConstructor(onConstructor_ = @Inject)
public final class EmailService {

    private static final String LAST_CHECKED_KEY = "lastCheckedAt";

    private final EmailProcessor emailProcessor;
    private final InvoiceProcessor invoiceProcessor;
    private final AppConfig appConfig;

    private Cache<String, LocalDateTime> lastCheckedAt;
    private final AtomicInteger checkEmailsCount = new AtomicInteger(0);

    @PostConstruct
    void initCache() {
        this.lastCheckedAt = Caffeine.newBuilder()
            .maximumSize(1)
            .expireAfterWrite(appConfig.lastCheckedCacheHours(), TimeUnit.HOURS)
            .build();
    }

    @Scheduled(every = "${app.check-interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void checkEmails() {
        final int runCount = checkEmailsCount.incrementAndGet();
        log.info("Checking for new emails... (run #{})", runCount);
        try {
            final LocalDateTime searchStartDate = lastCheckedAt.getIfPresent(LAST_CHECKED_KEY);
            final LocalDateTime checkStartedAt = LocalDateTime.now();

            final boolean checked = emailProcessor.checkAndProcessEmails(
                invoice -> log.info("Processing invoice: {} | From: {} | To: {}",
                    invoice.getFilename(),
                    invoice.getFromAddress(),
                    invoice.getToAddress()),
                invoiceProcessor::processInvoice,
                searchStartDate
            ).isPresent();

            if (checked) {
                lastCheckedAt.put(LAST_CHECKED_KEY, checkStartedAt);
            } else {
                log.warn("Email check failed; next run will search again from {}",
                    searchStartDate != null ? searchStartDate : "mail.days-older");
            }
        } catch (final Exception e) {
            log.error("Error checking emails", e);
        }
    }
}
