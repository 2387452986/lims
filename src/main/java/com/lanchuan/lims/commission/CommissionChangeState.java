package com.lanchuan.lims.commission;

import com.lanchuan.lims.common.ApiException;
import org.springframework.http.HttpStatus;

import java.util.List;

public enum CommissionChangeState {
    DRAFT, SUBMITTED, APPROVED, REJECTED, WITHDRAWN;

    public CommissionChangeState transition(String action) {
        return switch (this) {
            case DRAFT -> switch (action) {
                case "SUBMIT" -> SUBMITTED;
                case "WITHDRAW" -> WITHDRAWN;
                default -> invalid();
            };
            case SUBMITTED -> switch (action) {
                case "APPROVE" -> APPROVED;
                case "REJECT" -> REJECTED;
                case "WITHDRAW" -> WITHDRAWN;
                default -> invalid();
            };
            case APPROVED, REJECTED, WITHDRAWN -> invalid();
        };
    }

    public List<String> allowedActions() {
        return switch (this) {
            case DRAFT -> List.of("SUBMIT", "WITHDRAW");
            case SUBMITTED -> List.of("APPROVE", "REJECT", "WITHDRAW");
            case APPROVED, REJECTED, WITHDRAWN -> List.of();
        };
    }

    private CommissionChangeState invalid() {
        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "COMMISSION_CHANGE_ACTION_INVALID",
                "当前委托修改申请状态不允许该操作");
    }
}
