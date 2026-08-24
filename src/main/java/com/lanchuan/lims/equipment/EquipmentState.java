package com.lanchuan.lims.equipment;

import com.lanchuan.lims.common.ApiException;
import org.springframework.http.HttpStatus;

import java.util.List;

public enum EquipmentState {
    AVAILABLE, IN_USE, BORROWED, UNDER_INSPECTION, UNDER_CALIBRATION, MAINTENANCE, OUT_OF_SERVICE, SCRAPPED;

    public EquipmentState transition(String action) {
        return switch (this) {
            case AVAILABLE -> switch (action) {
                case "START_USE" -> IN_USE; case "BORROW" -> BORROWED; case "SEND_INSPECTION" -> UNDER_INSPECTION;
                case "SEND_CALIBRATION" -> UNDER_CALIBRATION; case "START_MAINTENANCE" -> MAINTENANCE;
                case "STOCK_OUT" -> OUT_OF_SERVICE; case "SCRAP" -> SCRAPPED; default -> invalid(action);
            };
            case IN_USE -> action.equals("END_USE") ? AVAILABLE : invalid(action);
            case BORROWED -> action.equals("RETURN") ? AVAILABLE : invalid(action);
            case UNDER_INSPECTION -> action.equals("VERIFIED") ? AVAILABLE : invalid(action);
            case UNDER_CALIBRATION -> action.equals("CALIBRATED") ? AVAILABLE : invalid(action);
            case MAINTENANCE -> action.equals("MAINTENANCE_COMPLETE") ? AVAILABLE : invalid(action);
            case OUT_OF_SERVICE -> action.equals("STOCK_IN") ? AVAILABLE : invalid(action);
            case SCRAPPED -> invalid(action);
        };
    }

    public List<String> allowedActions() {
        return switch (this) {
            case AVAILABLE -> List.of("START_USE", "BORROW", "SEND_INSPECTION", "SEND_CALIBRATION", "START_MAINTENANCE", "STOCK_OUT", "SCRAP");
            case IN_USE -> List.of("END_USE"); case BORROWED -> List.of("RETURN");
            case UNDER_INSPECTION -> List.of("VERIFIED"); case UNDER_CALIBRATION -> List.of("CALIBRATED");
            case MAINTENANCE -> List.of("MAINTENANCE_COMPLETE"); case OUT_OF_SERVICE -> List.of("STOCK_IN");
            case SCRAPPED -> List.of();
        };
    }

    private EquipmentState invalid(String action) {
        throw new ApiException(HttpStatus.CONFLICT, "INVALID_EQUIPMENT_TRANSITION",
                "设备状态 %s 不允许动作 %s".formatted(this, action));
    }
}
