package com.lanchuan.lims.nonconformance;

import com.lanchuan.lims.common.ApiException;
import org.springframework.http.HttpStatus;

import java.util.List;

public enum NonconformanceState {
    OPEN, QUALITY_CONFIRMED, DISPOSITION_IN_PROGRESS, PENDING_VERIFICATION, CLOSED, REJECTED;

    public NonconformanceState transition(String action) {
        return switch (this) {
            case OPEN -> switch (action) { case "CONFIRM" -> QUALITY_CONFIRMED; case "REJECT" -> REJECTED; default -> invalid(action); };
            case QUALITY_CONFIRMED -> action.equals("START_DISPOSITION") ? DISPOSITION_IN_PROGRESS : invalid(action);
            case DISPOSITION_IN_PROGRESS -> action.equals("SUBMIT_VERIFICATION") ? PENDING_VERIFICATION : invalid(action);
            case PENDING_VERIFICATION -> switch (action) { case "CLOSE" -> CLOSED; case "RETURN" -> DISPOSITION_IN_PROGRESS; default -> invalid(action); };
            case CLOSED, REJECTED -> invalid(action);
        };
    }

    public List<String> allowedActions() {
        return switch (this) {
            case OPEN -> List.of("CONFIRM", "REJECT"); case QUALITY_CONFIRMED -> List.of("START_DISPOSITION");
            case DISPOSITION_IN_PROGRESS -> List.of("SUBMIT_VERIFICATION");
            case PENDING_VERIFICATION -> List.of("CLOSE", "RETURN"); case CLOSED, REJECTED -> List.of();
        };
    }

    private NonconformanceState invalid(String action) {
        throw new ApiException(HttpStatus.CONFLICT, "INVALID_NONCONFORMANCE_TRANSITION",
                "不符合项状态 %s 不允许动作 %s".formatted(this, action));
    }
}
