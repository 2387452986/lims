package com.lanchuan.lims.commission;

import com.lanchuan.lims.common.ApiException;
import org.springframework.http.HttpStatus;

import java.util.List;

public enum ProjectState {
    DRAFT, ACTIVE, SUSPENDED, COMPLETED, CLOSED;

    public ProjectState transition(String action) {
        return switch (this) {
            case DRAFT -> switch (action) { case "SUBMIT" -> ACTIVE; case "CLOSE" -> CLOSED; default -> invalid(); };
            case ACTIVE -> switch (action) {
                case "SUSPEND" -> SUSPENDED; case "COMPLETE" -> COMPLETED; case "CLOSE" -> CLOSED; default -> invalid();
            };
            case SUSPENDED -> switch (action) { case "ACTIVATE" -> ACTIVE; case "CLOSE" -> CLOSED; default -> invalid(); };
            case COMPLETED -> switch (action) { case "REOPEN" -> ACTIVE; case "CLOSE" -> CLOSED; default -> invalid(); };
            case CLOSED -> invalid();
        };
    }

    public List<String> allowedActions() {
        return switch (this) {
            case DRAFT -> List.of("SUBMIT", "CLOSE");
            case ACTIVE -> List.of("SUSPEND", "COMPLETE", "CLOSE");
            case SUSPENDED -> List.of("ACTIVATE", "CLOSE");
            case COMPLETED -> List.of("REOPEN", "CLOSE");
            case CLOSED -> List.of();
        };
    }

    private ProjectState invalid() {
        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "PROJECT_ACTION_INVALID", "当前工程状态不允许该操作");
    }
}
