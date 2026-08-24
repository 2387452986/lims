package com.lanchuan.lims.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class NotificationProjectionWorker {
    private static final Logger log = LoggerFactory.getLogger(NotificationProjectionWorker.class);
    private final NotificationProjectionService projection;

    public NotificationProjectionWorker(NotificationProjectionService projection) {
        this.projection = projection;
    }

    @Scheduled(initialDelayString = "${lims.notification.initial-delay-ms:15000}",
            fixedDelayString = "${lims.notification.projection-delay-ms:60000}")
    public void run() {
        try {
            NotificationProjectionService.ProjectionResult result = projection.projectAll();
            if (result.deliveryCount() > 0) {
                log.info("Projected {} notifications from {} candidates across {} rules",
                        result.deliveryCount(), result.candidateCount(), result.ruleCount());
            }
        } catch (RuntimeException exception) {
            log.error("Notification projection failed", exception);
        }
    }
}
