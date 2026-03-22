package com.amfalmeida.mailhawk.health;

import com.amfalmeida.mailhawk.config.MailConfig;
import com.amfalmeida.mailhawk.service.MailService;
import io.quarkus.scheduler.Scheduler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.mail.Folder;
import jakarta.mail.Store;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

import jakarta.inject.Inject;

@Slf4j
@Liveness
@ApplicationScoped
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class SchedulerHealthCheck implements HealthCheck {

    private final Scheduler scheduler;
    private final MailService mailService;
    private final MailConfig mailConfig;

    @Override
    public HealthCheckResponse call() {
        if (!scheduler.isRunning()) {
            return HealthCheckResponse.down("Scheduler is not running");
        }

        final Store store = mailService.getStore();
        if (store == null || !store.isConnected()) {
            log.warn("Mail store is not connected");
            return HealthCheckResponse.down("Mail store is not connected");
        }

        Folder folder = null;
        try {
            folder = store.getFolder(mailConfig.folder());
            if (!folder.exists()) {
                log.warn("Folder {} does not exist", mailConfig.folder());
                return HealthCheckResponse.down("Folder " + mailConfig.folder() + " does not exist");
            }
        } catch (Exception e) {
            log.warn("Failed to access folder {}: {}", mailConfig.folder(), e.getMessage());
            return HealthCheckResponse.down("Cannot access mail folder: " + e.getMessage());
        } finally {
            if (folder != null && folder.isOpen()) {
                try {
                    folder.close(false);
                } catch (Exception e) {
                    log.debug("Error closing folder: {}", e.getMessage());
                }
            }
        }

        return HealthCheckResponse.up("Scheduler and mail connection are healthy");
    }
}
