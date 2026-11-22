package de.palsoftware.yvoke.shared.jobengine;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.worker")
public record WorkerProperties(
        boolean enabled,
        int concurrency,
        Duration pollInterval,
        Duration heartbeatInterval,
        Duration staleAfter) {

    public WorkerProperties {
        if (concurrency <= 0) {
            concurrency = 4;
        }
        if (pollInterval == null || pollInterval.isZero() || pollInterval.isNegative()) {
            pollInterval = Duration.ofSeconds(2);
        }
        if (heartbeatInterval == null
                || heartbeatInterval.isZero()
                || heartbeatInterval.isNegative()) {
            throw new IllegalArgumentException(
                    "app.worker.heartbeat-interval must be a positive duration");
        }
        if (staleAfter == null || staleAfter.isZero() || staleAfter.isNegative()) {
            throw new IllegalArgumentException(
                    "app.worker.stale-after must be a positive duration");
        }
    }
}
