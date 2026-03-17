package com.amfalmeida.mailhawk.health;

import io.quarkus.scheduler.Scheduler;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

import jakarta.inject.Inject;

@Liveness
@ApplicationScoped
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class SchedulerHealthCheck implements HealthCheck {

    private final Scheduler scheduler;

    @Override
    public HealthCheckResponse call() {
        if (scheduler.isRunning()) {
            return HealthCheckResponse.up("Scheduler is running");
        }
        return HealthCheckResponse.down("Scheduler is not running");
    }
}
