package com.amfalmeida.mailhawk.service;

import com.amfalmeida.common.MdcSetter;
import com.amfalmeida.mailhawk.config.MailConfig;
import com.amfalmeida.mailhawk.model.Invoice;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.annotation.PostConstruct;
import jakarta.mail.*;
import jakarta.mail.search.SearchTerm;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

@Slf4j
@ApplicationScoped
@RequiredArgsConstructor(onConstructor_ = @Inject)
public final class MailService {

    private final MailConfig mailConfig;

    private Session session;
    @Getter private Store store;
    private Cache<String, Boolean> processedMessageIds;

    @PostConstruct
    void initCache() {
        this.processedMessageIds = Caffeine.newBuilder()
            .maximumSize(mailConfig.messageCacheSize())
            .expireAfterWrite(mailConfig.messageCacheExpireDays(), TimeUnit.DAYS)
            .build();
    }

    public boolean connect() {
        try {
            final String host = mailConfig.host();
            final int port = mailConfig.port();

            log.info("Connecting to mail server: {}:{}", host, port);

            final Properties props = new Properties();
            props.setProperty("mail.store.protocol", "imaps");
            props.setProperty("mail.imaps.host", host);
            props.setProperty("mail.imaps.port", String.valueOf(port));
            props.setProperty("mail.imaps.ssl.enable", "true");
            props.setProperty("mail.debug", String.valueOf(mailConfig.debug()));

            session = Session.getInstance(props, null);
            store = session.getStore("imaps");
            store.connect(host, port, mailConfig.username(), mailConfig.password());

            log.info("Connected to {}:{}", host, port);
            return true;
        } catch (final AuthenticationFailedException e) {
            log.error("Authentication failed for user '{}': {}", mailConfig.username(), e.getMessage());
            return false;
        } catch (final Exception e) {
            log.error("Failed to connect to mail server: {}", e.getMessage(), e);
            return false;
        }
    }

    public void disconnect() {
        try {
            if (store != null && store.isConnected()) {
                store.close();
                log.info("Disconnected from mail server");
            }
        } catch (final Exception e) {
            log.error("Error disconnecting", e);
        }
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
            folder = store.getFolder(mailConfig.folder());
            folder.open(Folder.READ_ONLY);

            final LocalDate searchStartDate = lastCheckedAt != null
            ? lastCheckedAt.toLocalDate()
            : LocalDate.now().minusDays(mailConfig.daysOlder());

            log.info("Searching emails - folder: {}, since: {}, subjectTerms: {}, minSize: {}",
                mailConfig.folder(), searchStartDate, mailConfig.subjectTerms(),
                mailConfig.minAttachmentSize() > 0 ? mailConfig.minAttachmentSize() : "disabled");

            Message[] messages = searchMessages(folder, searchStartDate);
            messages = filterBySubject(messages);

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
        if (store != null && store.isConnected()) {
            return true;
        }
        return connect();
    }

    private Message[] searchMessages(
            final Folder folder,
            final LocalDate startDate) throws MessagingException {
        final SearchTerm searchTerm = SearchTermBuilder.buildDateAndSizeFilter(
            startDate, mailConfig.minAttachmentSize());
        final Message[] messages = folder.search(searchTerm);
        log.info("Found {} emails from server", messages.length);

        if (messages.length > 0) {
            final FetchProfile profile = new FetchProfile();
            profile.add(FetchProfile.Item.ENVELOPE);
            profile.add(FetchProfile.Item.CONTENT_INFO);
            folder.fetch(messages, profile);
            log.debug("Prefetched ENVELOPE and CONTENT_INFO for {} messages", messages.length);
        }

        return messages;
    }

    private Message[] filterBySubject(final Message[] messages) {
        final List<String> subjectTerms = mailConfig.subjectTerms();
        if (subjectTerms == null || subjectTerms.isEmpty()) {
            return messages;
        }

        final int beforeFilter = messages.length;
        final Message[] filtered = Arrays.stream(messages)
            .filter(msg -> {
                try {
                    return SearchTermBuilder.matchesSubject(msg.getSubject(), subjectTerms);
                } catch (final MessagingException e) {
                    return false;
                }
            })
            .toArray(Message[]::new);
        log.info("Subject filter: {} -> {} emails", beforeFilter, filtered.length);
        return filtered;
    }

    private Optional<List<Invoice>> processMessage(
            final Message msg,
            final Consumer<Invoice> invoiceCallback,
            final Consumer<Invoice> processCallback) {
        try (MdcSetter ignored = new MdcSetter(UUID.randomUUID().toString())) {
            final String messageId = getMessageId(msg);

            if (processedMessageIds.getIfPresent(messageId) != null) {
                log.debug("Skipping message: already processed. messageId: {}", messageId);
                return Optional.empty();
            }

            if (mailConfig.onlyAttachments() && !hasAttachments(msg)) {
                log.debug("Skipping message: no attachments. messageId: {}", messageId);
                return Optional.empty();
            }

            final List<Invoice> invoices = extractInvoicesFromMessage(msg, messageId);

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

    private List<Invoice> extractInvoicesFromMessage(
            final Message msg,
            final String messageId) throws IOException, MessagingException {
        final String subject = decodeHeader(msg.getSubject());
        final String fromEmail = extractEmail(msg.getFrom());
        final String fromName = extractName(msg.getFrom());
        final String toEmail = extractEmail(msg.getRecipients(Message.RecipientType.TO));
        final LocalDate date = extractDate(msg);

        final List<File> attachments = saveAttachments(msg);

        return attachments.stream()
            .map(file -> new Invoice(
                messageId,
                subject,
                fromEmail,
                fromName,
                toEmail,
                date,
                file.getName(),
                file.getAbsolutePath(),
                null,
                null
            ))
            .peek(invoice -> log.info("Found invoice attachment: {}", invoice.getFilename()))
            .toList();
    }

    private List<File> saveAttachments(final Message msg) throws IOException, MessagingException {
        if (!msg.isMimeType("multipart/*")) {
            return List.of();
        }

        final File tempDir = Files.createTempDirectory("invoice_").toFile();
        tempDir.deleteOnExit();
        final List<File> attachments = new ArrayList<>();

        final Multipart mp = (Multipart) msg.getContent();
        for (int i = 0; i < mp.getCount(); i++) {
            saveAttachmentPart(mp.getBodyPart(i), tempDir)
                .ifPresent(attachments::add);
        }

        return attachments;
    }

    private Optional<File> saveAttachmentPart(
            final Part part,
            final File tempDir) throws IOException, MessagingException {
        final String disposition = part.getDisposition();
        if (!Part.ATTACHMENT.equalsIgnoreCase(disposition) && !Part.INLINE.equalsIgnoreCase(disposition)) {
            return Optional.empty();
        }

        final String filename = decodeHeader(part.getFileName());
        if (!FileTypes.isSupported(filename)) {
            if (filename != null) {
                log.info("Skipping unsupported attachment: {}", filename);
            }
            return Optional.empty();
        }

        final File file = new File(tempDir, sanitizeFilename(filename));
        try (var fos = Files.newOutputStream(file.toPath())) {
            part.getInputStream().transferTo(fos);
        }
        log.info("Saved attachment: {}", filename);
        return Optional.of(file);
    }

    private boolean hasAttachments(final Message msg) throws IOException, MessagingException {
        if (!msg.isMimeType("multipart/*")) {
            return false;
        }

        final Multipart mp = (Multipart) msg.getContent();
        for (int i = 0; i < mp.getCount(); i++) {
            final Part part = mp.getBodyPart(i);
            final String disposition = part.getDisposition();
            if (Part.ATTACHMENT.equalsIgnoreCase(disposition) || Part.INLINE.equalsIgnoreCase(disposition)) {
                if (FileTypes.isSupported(part.getFileName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private String getMessageId(final Message msg) throws MessagingException {
        final String[] ids = msg.getHeader("Message-ID");
        return ids != null && ids.length > 0 ? ids[0] : UUID.randomUUID().toString();
    }

    private String extractEmail(final Address[] addresses) {
        if (addresses == null || addresses.length == 0) {
            return "";
        }
        final String str = addresses[0].toString();
        final int start = str.indexOf('<');
        final int end = str.indexOf('>');
        return start >= 0 && end > start ? str.substring(start + 1, end) : str;
    }

    private String extractName(final Address[] addresses) {
        if (addresses == null || addresses.length == 0) {
            return "";
        }
        final String str = addresses[0].toString();
        final int lt = str.indexOf('<');
        if (lt > 0) {
            return decodeHeader(str.substring(0, lt).trim().replaceAll("^\"|\"$", ""));
        }
        return "";
    }

    private LocalDate extractDate(final Message msg) throws MessagingException {
        final Date date = msg.getReceivedDate() != null ? msg.getReceivedDate() : msg.getSentDate();
        return date != null ? date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate() : LocalDate.now();
    }

    private String decodeHeader(final String header) {
        if (header == null) {
            return "";
        }
        try {
            return jakarta.mail.internet.MimeUtility.decodeText(header);
        } catch (final Exception e) {
            return header;
        }
    }

    private String sanitizeFilename(final String filename) {
        return filename.replaceAll("[<>:\"/\\\\|?*]", "_");
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
