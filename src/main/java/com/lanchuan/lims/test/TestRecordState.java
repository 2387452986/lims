package com.lanchuan.lims.test;

import com.lanchuan.lims.common.ApiException;
import org.springframework.http.HttpStatus;

import java.util.List;

public enum TestRecordState {
    DRAFT, SUBMITTED, RETURNED, CONFIRMED, SUPERSEDED;

    public TestRecordState transition(String action) {
        return switch (this) {
            case DRAFT -> action.equals("SUBMIT") ? SUBMITTED : invalid(action);
            case SUBMITTED -> switch (action) { case "RETURN" -> RETURNED; case "CONFIRM" -> CONFIRMED; default -> invalid(action); };
            case RETURNED -> action.equals("RESUBMIT") ? SUBMITTED : invalid(action);
            case CONFIRMED, SUPERSEDED -> invalid(action);
        };
    }

    public List<String> allowedActions() {
        return switch (this) {
            case DRAFT -> List.of("SUBMIT");
            case SUBMITTED -> List.of("RETURN", "CONFIRM");
            case RETURNED -> List.of("RESUBMIT");
            case CONFIRMED, SUPERSEDED -> List.of();
        };
    }

    private TestRecordState invalid(String action) {
        throw new ApiException(HttpStatus.CONFLICT, "INVALID_TEST_RECORD_TRANSITION",
                "试验记录状态 %s 不允许动作 %s".formatted(this, action));
    }
}
