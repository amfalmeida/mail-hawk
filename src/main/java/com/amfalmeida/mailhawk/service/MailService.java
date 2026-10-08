package com.amfalmeida.mailhawk.service;

import com.amfalmeida.mailhawk.email.EmailProcessor;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Scheduling only: runs the email check job on a configured interval and
 * delegates the actual work to {@link EmailProcessor} and {@link InvoiceProcessor}.
 */
@Slf4j
@ApplicationScoped
@RequiredArgsConstructor(onConstructor_ = @Inject)
public final class MailService {

    private final EmailProcessor emailProcessor;
    private final InvoiceProcessor invoiceProcessor;

    private LocalDateTime lastCheckedAt;
    private final AtomicInteger checkEmailsCount = new AtomicInteger(0);

    @Scheduled(every = "${app.check-interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void checkEmails() {
        final int runCount = checkEmailsCount.incrementAndGet();
        log.info("Checking for new emails... (run #{})", runCount);
        try {
            final LocalDateTime searchStartDate = lastCheckedAt;
            lastCheckedAt = LocalDateTime.now();

            emailProcessor.checkAndProcessEmails(
                invoice -> log.info("Processing invoice: {} | From: {} | To: {}",
                    invoice.getFilename(),
                    invoice.getFromAddress(),
                    invoice.getToAddress()),
                invoiceProcessor::processInvoice,
                searchStartDate
            );
        } catch (final Exception e) {
            log.error("Error checking emails", e);
        }
    }
}
