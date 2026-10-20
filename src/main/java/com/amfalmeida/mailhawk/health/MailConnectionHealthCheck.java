package com.amfalmeida.mailhawk.health;

import com.amfalmeida.mailhawk.config.MailConfig;
import com.amfalmeida.mailhawk.email.EmailClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.mail.Folder;
import jakarta.mail.Store;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/**
 * Readiness: reports whether the IMAP store is connected and the configured folder is reachable.
 * The store connects lazily on the first email check, so this is DOWN until that run completes.
 */
@Slf4j
@Readiness
@ApplicationScoped
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class MailConnectionHealthCheck implements HealthCheck {

    private final EmailClient emailClient;
    private final MailConfig mailConfig;

    @Override
    public HealthCheckResponse call() {
        final Store store = emailClient.getStore();
        if (store == null || !store.isConnected()) {
            log.warn("Mail store is not connected");
            return HealthCheckResponse.down("Mail store is not connected");
        }

        try {
            final Folder folder = store.getFolder(mailConfig.folder());
            if (!folder.exists()) {
                log.warn("Folder {} does not exist", mailConfig.folder());
                return HealthCheckResponse.down("Folder " + mailConfig.folder() + " does not exist");
            }
        } catch (final Exception e) {
            log.warn("Failed to access folder {}: {}", mailConfig.folder(), e.getMessage());
            return HealthCheckResponse.down("Cannot access mail folder: " + e.getMessage());
        }

        return HealthCheckResponse.up("Mail connection is healthy");
    }
}
