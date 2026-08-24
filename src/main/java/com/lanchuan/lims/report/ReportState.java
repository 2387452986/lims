package com.lanchuan.lims.report;

import com.lanchuan.lims.common.ApiException;
import org.springframework.http.HttpStatus;

import java.util.List;

public enum ReportState {
    DRAFT, IN_REVIEW, RETURNED, APPROVED, ISSUED, DISTRIBUTED, ARCHIVED, VOID;

    public ReportState transition(String action) {
        return switch (this) {
            case DRAFT -> action.equals("SUBMIT_REVIEW") ? IN_REVIEW : invalid(action);
            case IN_REVIEW -> switch (action) { case "APPROVE" -> APPROVED; case "RETURN" -> RETURNED; default -> invalid(action); };
            case RETURNED -> action.equals("RESUBMIT") ? IN_REVIEW : invalid(action);
            case APPROVED -> action.equals("ISSUE") ? ISSUED : invalid(action);
            case ISSUED -> switch (action) { case "SEAL" -> ISSUED; case "DISTRIBUTE" -> DISTRIBUTED; case "VOID" -> VOID; default -> invalid(action); };
            case DISTRIBUTED -> switch (action) { case "ARCHIVE" -> ARCHIVED; case "VOID" -> VOID; default -> invalid(action); };
            case ARCHIVED -> action.equals("VOID") ? VOID : invalid(action);
            case VOID -> invalid(action);
        };
    }

    public List<String> allowedActions(boolean sealed) {
        return switch (this) {
            case DRAFT -> List.of("SUBMIT_REVIEW"); case IN_REVIEW -> List.of("APPROVE", "RETURN");
            case RETURNED -> List.of("RESUBMIT"); case APPROVED -> List.of("ISSUE");
            case ISSUED -> sealed ? List.of("DISTRIBUTE", "VOID") : List.of("SEAL", "VOID");
            case DISTRIBUTED -> List.of("ARCHIVE", "VOID"); case ARCHIVED -> List.of("VOID"); case VOID -> List.of();
        };
    }

    private ReportState invalid(String action) {
        throw new ApiException(HttpStatus.CONFLICT, "INVALID_REPORT_TRANSITION",
                "报告状态 %s 不允许动作 %s".formatted(this, action));
    }
}
