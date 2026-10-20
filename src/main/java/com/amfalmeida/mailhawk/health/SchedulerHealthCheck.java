package com.amfalmeida.mailhawk.health;

import io.quarkus.scheduler.Scheduler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.RequiredArgsConstructor;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

/**
 * Liveness: the app only needs restarting if the scheduler has stopped. Mail connectivity is
 * reported separately by {@link MailConnectionHealthCheck}, so an IMAP outage doesn't trigger
 * Home Assistant watchdog restarts.
 */
@Liveness
@ApplicationScoped
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class SchedulerHealthCheck implements HealthCheck {

    private final Scheduler scheduler;

    @Override
    public HealthCheckResponse call() {
        if (!scheduler.isRunning()) {
            return HealthCheckResponse.down("Scheduler is not running");
        }
        return HealthCheckResponse.up("Scheduler is running");
    }
}
