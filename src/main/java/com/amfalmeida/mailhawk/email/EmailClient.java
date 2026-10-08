package com.amfalmeida.mailhawk.email;

import com.amfalmeida.mailhawk.config.MailConfig;
import com.amfalmeida.mailhawk.model.Invoice;
import com.amfalmeida.mailhawk.service.FileTypes;
import com.amfalmeida.mailhawk.service.SearchTermBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.mail.Address;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.FetchProfile;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.MimeUtility;
import jakarta.mail.search.SearchTerm;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/**
 * Low-level email handling: IMAP connection, folder search, subject filtering,
 * MIME attachment detection and invoice extraction. Contains no scheduling or
 * deduplication state, so it can be unit-tested with plain {@link Message} objects.
 */
@Slf4j
@ApplicationScoped
@RequiredArgsConstructor(onConstructor_ = @Inject)
public final class EmailClient {

    private final MailConfig mailConfig;

    private Session session;
    @Getter
    private Store store;

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

    public boolean isConnected() {
        return store != null && store.isConnected();
    }

    public Message[] searchMessages(
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

    public Message[] filterBySubject(final Message[] messages) {
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

    public boolean hasAttachments(final Message msg) throws IOException, MessagingException {
        return hasAttachments((Part) msg);
    }

    public String getMessageId(final Message msg) throws MessagingException {
        final String[] ids = msg.getHeader("Message-ID");
        return ids != null && ids.length > 0 ? ids[0] : UUID.randomUUID().toString();
    }

    public String decodeHeader(final String header) {
        if (header == null) {
            return "";
        }
        try {
            return MimeUtility.decodeText(header);
        } catch (final Exception e) {
            return header;
        }
    }

    /**
     * Extracts one {@link Invoice} per supported attachment found in the message,
     * saving each attachment to a temporary file. Header fields are decoded from
     * the message envelope; invoice content (QR code) is not parsed here.
     */
    public List<Invoice> extractInvoices(final Message msg) throws IOException, MessagingException {
        final String messageId = getMessageId(msg);
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

    private boolean hasAttachments(final Part part) throws IOException, MessagingException {
        if (part.isMimeType("multipart/*")) {
            final Multipart mp = (Multipart) part.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                if (hasAttachments(mp.getBodyPart(i))) {
                    return true;
                }
            }
            return false;
        }

        final String disposition = part.getDisposition();
        final boolean isAttachment = Part.ATTACHMENT.equalsIgnoreCase(disposition)
            || Part.INLINE.equalsIgnoreCase(disposition)
            || part.getFileName() != null;
        return isAttachment && FileTypes.isSupported(part.getFileName());
    }

    private List<File> saveAttachments(final Message msg) throws IOException, MessagingException {
        final File tempDir = Files.createTempDirectory("invoice_").toFile();
        tempDir.deleteOnExit();
        final List<File> attachments = new ArrayList<>();
        saveAttachments((Part) msg, tempDir, attachments);
        return attachments;
    }

    private void saveAttachments(
            final Part part,
            final File tempDir,
            final List<File> attachments) throws IOException, MessagingException {
        if (part.isMimeType("multipart/*")) {
            final Multipart mp = (Multipart) part.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                saveAttachments(mp.getBodyPart(i), tempDir, attachments);
            }
            return;
        }

        final String disposition = part.getDisposition();
        final String filename = decodeHeader(part.getFileName());
        final boolean isAttachment = Part.ATTACHMENT.equalsIgnoreCase(disposition)
            || Part.INLINE.equalsIgnoreCase(disposition)
            || filename != null;
        if (!isAttachment) {
            return;
        }

        if (!FileTypes.isSupported(filename)) {
            if (filename != null) {
                log.info("Skipping unsupported attachment: {}", filename);
            }
            return;
        }

        final File file = new File(tempDir, sanitizeFilename(filename));
        try (var fos = Files.newOutputStream(file.toPath())) {
            part.getInputStream().transferTo(fos);
        }
        log.info("Saved attachment: {}", filename);
        attachments.add(file);
    }

    private String extractEmail(final Address[] addresses) {
        if (addresses == null || addresses.length == 0) {
            return "";
        }
        final String str = addresses[0].toString();
        final int start = str.indexOf('<');
        final int end = str.indexOf('>');
        final String emailAddr = start >= 0 && end > start ? str.substring(start + 1, end) : str;
        return emailAddr.trim().toLowerCase();
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

    private String sanitizeFilename(final String filename) {
        return filename.replaceAll("[<>:\"/\\\\|?*]", "_");
    }
}
