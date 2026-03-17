package com.amfalmeida.mailhawk.service;

import com.amfalmeida.common.MdcSetter;
import com.amfalmeida.mailhawk.config.MailConfig;
import com.amfalmeida.mailhawk.model.Invoice;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.mail.*;
import jakarta.mail.search.ReceivedDateTerm;
import jakarta.mail.search.ComparisonTerm;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.Getter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import jakarta.inject.Inject;

@ApplicationScoped
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public final class MailService {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("pdf", "jpg", "jpeg", "png", "bmp", "gif");

    private final MailConfig mailConfig;

    private Session session;
    @Getter private Store store;
    private Folder folder;
    private final Set<String> processedMessageIds = Collections.synchronizedSet(new HashSet<>());

    public boolean connect() {
        try {
            final String username = mailConfig.username();
            final String password = mailConfig.password();
            final String host = mailConfig.host();
            final int port = mailConfig.port();
            
            log.info("Connecting to mail server: {}:{}", host, port);
            
            final Properties props = System.getProperties();
            props.setProperty("mail.store.protocol", "imaps");
            props.setProperty("mail.imaps.host", host);
            props.setProperty("mail.imaps.port", String.valueOf(port));
            props.setProperty("mail.imaps.ssl.enable", "true");
            props.setProperty("mail.debug", String.valueOf(mailConfig.debug()));
            
            session = Session.getInstance(props, null);
            store = session.getStore("imaps");
            store.connect(host, port, username, password);
            
            log.info("Connected to {}:{}", host, port);
            return true;
        } catch (final AuthenticationFailedException e) {
            log.error("Authentication failed for user '{}'. Error: {}", mailConfig.username(), e.getMessage());
            return false;
        } catch (final Exception e) {
            log.error("Failed to connect to mail server: {}", e.getMessage(), e);
            return false;
        }
    }

    public void disconnect() {
        try {
            if (folder != null && folder.isOpen()) {
                folder.close(false);
            }
            if (store != null && store.isConnected()) {
                store.close();
            }
            log.info("Disconnected from mail server");
        } catch (final Exception e) {
            log.error("Error disconnecting", e);
        }
    }

    public List<Invoice> checkAndProcessEmails(final Consumer<Invoice> invoiceCallback, final Consumer<Invoice> processCallback) {
        return checkAndProcessEmails(invoiceCallback, processCallback, null);
    }

    public List<Invoice> checkAndProcessEmails(final Consumer<Invoice> invoiceCallback, final Consumer<Invoice> processCallback, final LocalDateTime lastCheckedAt) {
        if (store == null || !store.isConnected()) {
            if (!connect()) {
                return List.of();
            }
        }

        final List<Invoice> invoices = new ArrayList<>();
        try {
            folder = store.getFolder(mailConfig.folder());
            folder.open(Folder.READ_ONLY);

            final LocalDate searchStartDate;
            if (lastCheckedAt != null) {
                searchStartDate = lastCheckedAt.toLocalDate();
            } else {
                searchStartDate = LocalDate.now().minusDays(mailConfig.daysOlder());
            }
            final Date searchDate = Date.from(searchStartDate.atStartOfDay(ZoneId.systemDefault()).toInstant());

            log.info("Searching emails - folder: {}, since: {}, max: {}, subjectFilter: {}", 
                mailConfig.folder(), 
                searchStartDate,
                mailConfig.maxEmails() > 0 ? mailConfig.maxEmails() : "unlimited",
                mailConfig.subjectFilter());

            Message[] messages = folder.search(new ReceivedDateTerm(ComparisonTerm.GT, searchDate));

            log.info("Found {} emails to check", messages.length);

            int maxEmails = mailConfig.maxEmails();
            if (maxEmails > 0 && messages.length > maxEmails) {
                log.info("Limiting to {} emails", maxEmails);
                messages = Arrays.copyOf(messages, maxEmails);
            }

            final Pattern subjectPattern = mailConfig.subjectFilter() != null && !mailConfig.subjectFilter().isEmpty()
                ? Pattern.compile(mailConfig.subjectFilter())
                : null;

            for (final Message msg : messages) {
                try (MdcSetter ignored = new MdcSetter(UUID.randomUUID().toString())) {
                    final String messageId = getMessageId(msg);
                    if (processedMessageIds.contains(messageId)) {
                        continue;
                    }

                    if (subjectPattern != null && !subjectMatches(msg, subjectPattern)) {
                        log.debug("Skipping message: subject doesn't match filter");
                        continue;
                    }

                    if (mailConfig.onlyAttachments() && !hasAttachments(msg)) {
                        log.debug("Skipping message: no attachments");
                        continue;
                    }

                    final List<Invoice> msgInvoices = processMessage(msg, messageId);
                    for (final Invoice invoice : msgInvoices) {
                        if (invoiceCallback != null) {
                            invoiceCallback.accept(invoice);
                        }
                        if (processCallback != null) {
                            processCallback.accept(invoice);
                        }
                        invoices.add(invoice);
                    }

                    processedMessageIds.add(messageId);
                } catch (final Exception e) {
                    log.error("Error processing message", e);
                }
            }
        } catch (final Exception e) {
            log.error("Error checking emails", e);
        }

        return invoices;
    }

    private boolean subjectMatches(final Message msg, final Pattern pattern) throws MessagingException {
        final String subject = msg.getSubject();
        if (subject == null) return false;
        return pattern.matcher(subject).find();
    }

    private boolean hasAttachments(final Message msg) throws IOException, MessagingException {
        if (msg.isMimeType("multipart/*")) {
            final Multipart mp = (Multipart) msg.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                final Part part = mp.getBodyPart(i);
                if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())) {
                    return true;
                }
            }
        }
        return false;
    }

    private String decodeHeader(final String header) {
        if (header == null) return "";
        try {
            return jakarta.mail.internet.MimeUtility.decodeText(header);
        } catch (final Exception e) {
            return header;
        }
    }

    private String getFromEmail(final Message msg) throws MessagingException {
        final Address[] from = msg.getFrom();
        if (from == null || from.length == 0) return "";
        final String fromStr = from[0].toString();
        final int start = fromStr.indexOf('<');
        final int end = fromStr.indexOf('>');
        if (start >= 0 && end > start) {
            return fromStr.substring(start + 1, end);
        }
        return fromStr;
    }

    private String getFromName(final Message msg) throws MessagingException {
        final Address[] from = msg.getFrom();
        if (from == null || from.length == 0) return "";
        final String fromStr = from[0].toString();
        final int lt = fromStr.indexOf('<');
        if (lt > 0) {
            return decodeHeader(fromStr.substring(0, lt).trim().replaceAll("^\"|\"$", ""));
        }
        return "";
    }

    private LocalDate getDate(final Message msg) throws MessagingException {
        Date date = msg.getReceivedDate();
        if (date == null) date = msg.getSentDate();
        return date != null ? date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate() : LocalDate.now();
    }

    private String sanitizeFilename(final String filename) {
        return filename.replaceAll("[<>:\"/\\\\|?*]", "_");
    }

    private String getMessageId(final Message msg) throws MessagingException {
        final String[] ids = msg.getHeader("Message-ID");
        return ids != null && ids.length > 0 ? ids[0] : UUID.randomUUID().toString();
    }

    private List<Invoice> processMessage(final Message msg, final String messageId) throws IOException, MessagingException {
        final List<Invoice> invoices = new ArrayList<>();

        final String subject = decodeHeader(msg.getSubject());
        final String fromEmail = getFromEmail(msg);
        final String fromName = getFromName(msg);
        final String toEmail = mailConfig.username();
        final LocalDate date = getDate(msg);

        final List<File> attachments = getAttachments(msg);

        for (final File file : attachments) {
            final Invoice invoice = new Invoice(
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
            );
            invoices.add(invoice);
            log.info("Found invoice attachment: {}", file.getName());
        }

        return invoices;
    }

    private List<File> getAttachments(final Message msg) throws IOException, MessagingException {
        final List<File> attachments = new ArrayList<>();
        final File tempDir = Files.createTempDirectory("invoice_").toFile();
        tempDir.deleteOnExit();

        if (msg.isMimeType("multipart/*")) {
            final Multipart mp = (Multipart) msg.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                final Part part = mp.getBodyPart(i);
                if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())) {
                    String filename = decodeHeader(part.getFileName());
                    if (filename != null) {
                        final String ext = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
                        if (SUPPORTED_EXTENSIONS.contains(ext)) {
                            final File file = new File(tempDir, sanitizeFilename(filename));
                            try (final var fos = Files.newOutputStream(file.toPath())) {
                                part.getInputStream().transferTo(fos);
                            }
                            attachments.add(file);
                            log.info("Saved attachment: {}", filename);
                        } else {
                            log.info("Skipping unsupported attachment: {}", filename);
                        }
                    }
                }
            }
        }

        return attachments;
    }
}