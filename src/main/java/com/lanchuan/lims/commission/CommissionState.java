package com.lanchuan.lims.commission;

import com.lanchuan.lims.common.ApiException;
import org.springframework.http.HttpStatus;

import java.util.List;

public enum CommissionState {
    DRAFT, PRE_ACCEPTED, ACCEPTED, SAMPLING, ASSIGNED, TESTING, REPORTING, ISSUED, ARCHIVED, REJECTED, CANCELLED;

    public CommissionState transition(String action) {
        return switch (this) {
            case DRAFT -> switch (action) { case "SUBMIT" -> PRE_ACCEPTED; case "CANCEL" -> CANCELLED; default -> invalid(action); };
            case PRE_ACCEPTED -> switch (action) { case "ACCEPT" -> ACCEPTED; case "REJECT" -> REJECTED; case "WITHDRAW" -> DRAFT; default -> invalid(action); };
            case ACCEPTED -> switch (action) { case "START_SAMPLING" -> SAMPLING; case "CANCEL" -> CANCELLED; default -> invalid(action); };
            case SAMPLING -> switch (action) { case "COMPLETE_SAMPLING" -> ASSIGNED; case "CANCEL" -> CANCELLED; default -> invalid(action); };
            case ASSIGNED -> switch (action) { case "START_TESTING" -> TESTING; case "CANCEL" -> CANCELLED; default -> invalid(action); };
            case TESTING -> switch (action) { case "COMPLETE_TESTING" -> REPORTING; default -> invalid(action); };
            case REPORTING -> switch (action) { case "MARK_ISSUED" -> ISSUED; default -> invalid(action); };
            case ISSUED -> switch (action) { case "ARCHIVE" -> ARCHIVED; default -> invalid(action); };
            case ARCHIVED, REJECTED, CANCELLED -> invalid(action);
        };
    }

    public List<String> allowedActions() {
        return switch (this) {
            case DRAFT -> List.of("SUBMIT", "CANCEL");
            case PRE_ACCEPTED -> List.of("ACCEPT", "REJECT", "WITHDRAW");
            case ACCEPTED -> List.of("START_SAMPLING", "CANCEL");
            case SAMPLING -> List.of("COMPLETE_SAMPLING", "CANCEL");
            case ASSIGNED -> List.of("START_TESTING", "CANCEL");
            case TESTING -> List.of("COMPLETE_TESTING");
            case REPORTING -> List.of("MARK_ISSUED");
            case ISSUED -> List.of("ARCHIVE");
            case ARCHIVED, REJECTED, CANCELLED -> List.of();
        };
    }

    private CommissionState invalid(String action) {
        throw new ApiException(HttpStatus.CONFLICT, "INVALID_COMMISSION_TRANSITION",
                "委托状态 %s 不允许动作 %s".formatted(this, action));
    }
}
