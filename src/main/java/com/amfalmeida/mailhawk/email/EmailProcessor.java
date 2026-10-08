package com.amfalmeida.mailhawk.email;

import com.amfalmeida.common.MdcSetter;
import com.amfalmeida.mailhawk.config.MailConfig;
import com.amfalmeida.mailhawk.model.Invoice;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Email processing pipeline: opens the configured folder, searches and filters
 * messages, and processes each one while avoiding duplicates across runs.
 * Scheduling belongs to {@link MailService}; low-level email access belongs to
 * {@link EmailClient}.
 */
@Slf4j
@ApplicationScoped
@RequiredArgsConstructor(onConstructor_ = @Inject)
public final class EmailProcessor {

    private final MailConfig mailConfig;
    private final EmailClient emailClient;

    private Cache<String, Boolean> processedMessageIds;

    @PostConstruct
    void initCache() {
        this.processedMessageIds = Caffeine.newBuilder()
            .maximumSize(mailConfig.messageCacheSize())
            .expireAfterWrite(mailConfig.messageCacheExpireSeconds(), TimeUnit.SECONDS)
            .build();
    }

    public List<Invoice> checkAndProcessEmails(
            final Consumer<Invoice> invoiceCallback,
            final Consumer<Invoice> processCallback) {
        return checkAndProcessEmails(invoiceCallback, processCallback, null);
    }

    public List<Invoice> checkAndProcessEmails(
            final Consumer<Invoice> invoiceCallback,
            final Consumer<Invoice> processCallback,
            final LocalDateTime lastCheckedAt) {
        if (!ensureConnected()) {
            return List.of();
        }

        final List<Invoice> invoices = new ArrayList<>();
        Folder folder = null;

        try {
            folder = emailClient.getStore().getFolder(mailConfig.folder());
            folder.open(Folder.READ_ONLY);

            final LocalDate searchStartDate = lastCheckedAt != null
                ? lastCheckedAt.toLocalDate()
                : LocalDate.now().minusDays(mailConfig.daysOlder());

            log.info("Searching emails - folder: {}, since: {}, subjectTerms: {}, minSize: {}",
                mailConfig.folder(), searchStartDate, mailConfig.subjectTerms(),
                mailConfig.minAttachmentSize() > 0 ? mailConfig.minAttachmentSize() : "disabled");

            Message[] messages = emailClient.searchMessages(folder, searchStartDate);
            messages = emailClient.filterBySubject(messages);

            for (final Message msg : messages) {
                processMessage(msg, invoiceCallback, processCallback)
                    .ifPresent(invoices::addAll);
            }
        } catch (final Exception e) {
            log.error("Error checking emails", e);
        } finally {
            closeFolder(folder);
        }

        return invoices;
    }

    private boolean ensureConnected() {
        return emailClient.isConnected() || emailClient.connect();
    }

    private Optional<List<Invoice>> processMessage(
            final Message msg,
            final Consumer<Invoice> invoiceCallback,
            final Consumer<Invoice> processCallback) {
        try (MdcSetter ignored = new MdcSetter(UUID.randomUUID().toString())) {
            final String messageId = emailClient.getMessageId(msg);

            if (processedMessageIds.getIfPresent(messageId) != null) {
                log.debug("Skipping message: already processed. messageId: {}", messageId);
                return Optional.empty();
            }

            if (mailConfig.onlyAttachments() && !emailClient.hasAttachments(msg)) {
                final String subject = emailClient.decodeHeader(msg.getSubject());
                log.debug("Skipping message: no attachments. messageId: {}, subject: '{}'", messageId, subject);
                return Optional.empty();
            }

            final List<Invoice> invoices = emailClient.extractInvoices(msg);

            invoices.forEach(invoice -> {
                if (invoiceCallback != null) {
                    invoiceCallback.accept(invoice);
                }
                if (processCallback != null) {
                    processCallback.accept(invoice);
                }
            });

            processedMessageIds.put(messageId, Boolean.TRUE);
            return Optional.of(invoices);
        } catch (final Exception e) {
            log.error("Error processing message", e);
            return Optional.empty();
        }
    }

    private void closeFolder(final Folder folder) {
        if (folder != null && folder.isOpen()) {
            try {
                folder.close(false);
            } catch (final Exception e) {
                log.error("Error closing folder", e);
            }
        }
    }
}
