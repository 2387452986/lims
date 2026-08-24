package com.lanchuan.lims.sample;

import com.lanchuan.lims.common.ApiException;
import org.springframework.http.HttpStatus;

import java.util.List;

public enum SampleState {
    EXPECTED, RECEIVED, HANDED_OVER, IN_TEST, RETAINED, RETURNED, DISPOSED;

    public SampleState transition(String action) {
        return switch (this) {
            case EXPECTED -> action.equals("RECEIVE") ? RECEIVED : invalid(action);
            case RECEIVED -> switch (action) { case "HANDOVER" -> HANDED_OVER; case "RETURN" -> RETURNED; default -> invalid(action); };
            case HANDED_OVER -> switch (action) { case "START_TEST" -> IN_TEST; case "RETURN" -> RETURNED; default -> invalid(action); };
            case IN_TEST -> switch (action) { case "RETAIN" -> RETAINED; case "RETURN" -> RETURNED; case "DISPOSE" -> DISPOSED; default -> invalid(action); };
            case RETAINED -> switch (action) { case "RETURN" -> RETURNED; case "DISPOSE" -> DISPOSED; default -> invalid(action); };
            case RETURNED, DISPOSED -> invalid(action);
        };
    }

    public List<String> allowedActions() {
        return switch (this) {
            case EXPECTED -> List.of("RECEIVE");
            case RECEIVED -> List.of("HANDOVER", "RETURN");
            case HANDED_OVER -> List.of("START_TEST", "RETURN");
            case IN_TEST -> List.of("RETAIN", "RETURN", "DISPOSE");
            case RETAINED -> List.of("RETURN", "DISPOSE");
            case RETURNED, DISPOSED -> List.of();
        };
    }

    private SampleState invalid(String action) {
        throw new ApiException(HttpStatus.CONFLICT, "INVALID_SAMPLE_TRANSITION",
                "样品状态 %s 不允许动作 %s".formatted(this, action));
    }
}
