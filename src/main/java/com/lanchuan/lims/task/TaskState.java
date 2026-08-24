package com.lanchuan.lims.task;

import com.lanchuan.lims.common.ApiException;
import org.springframework.http.HttpStatus;

import java.util.List;

public enum TaskState {
    UNASSIGNED, ASSIGNED, ACCEPTED, IN_PROGRESS, SUBMITTED, RETURNED, COMPLETED, CANCELLED;

    public TaskState transition(String action) {
        return switch (this) {
            case UNASSIGNED -> switch (action) { case "ASSIGN" -> ASSIGNED; case "CANCEL" -> CANCELLED; default -> invalid(action); };
            case ASSIGNED -> switch (action) { case "ACCEPT" -> ACCEPTED; case "REJECT" -> UNASSIGNED; case "REASSIGN" -> ASSIGNED; case "CANCEL" -> CANCELLED; default -> invalid(action); };
            case ACCEPTED -> switch (action) { case "START" -> IN_PROGRESS; case "REASSIGN" -> ASSIGNED; case "CANCEL" -> CANCELLED; default -> invalid(action); };
            case IN_PROGRESS -> action.equals("REASSIGN") ? ASSIGNED : invalid(action);
            case SUBMITTED -> switch (action) { case "RETURN" -> RETURNED; case "COMPLETE" -> COMPLETED; default -> invalid(action); };
            case RETURNED -> action.equals("REASSIGN") ? ASSIGNED : invalid(action);
            case COMPLETED, CANCELLED -> invalid(action);
        };
    }

    public List<String> allowedActions() {
        return switch (this) {
            case UNASSIGNED -> List.of("ASSIGN", "CANCEL");
            case ASSIGNED -> List.of("ACCEPT", "REJECT", "REASSIGN", "CANCEL");
            case ACCEPTED -> List.of("START", "REASSIGN", "CANCEL");
            case IN_PROGRESS -> List.of("REASSIGN");
            case SUBMITTED -> List.of("RETURN", "COMPLETE");
            case RETURNED -> List.of("REASSIGN");
            case COMPLETED, CANCELLED -> List.of();
        };
    }

    private TaskState invalid(String action) {
        throw new ApiException(HttpStatus.CONFLICT, "INVALID_TASK_TRANSITION",
                "任务状态 %s 不允许动作 %s".formatted(this, action));
    }
}
