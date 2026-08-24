package com.lanchuan.lims.common;

import com.lanchuan.lims.nonconformance.ExceptionApprovalService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MaintenanceTasks {
    private final IdempotencyService idempotency;
    private final ExceptionApprovalService exceptions;

    public MaintenanceTasks(IdempotencyService idempotency, ExceptionApprovalService exceptions) {
        this.idempotency = idempotency;
        this.exceptions = exceptions;
    }

    @Scheduled(cron = "0 17 * * * *")
    void purgeExpiredRecords() {
        idempotency.purgeExpired();
        exceptions.expire();
    }
}
