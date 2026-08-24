package com.lanchuan.lims.workbench;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkbenchPresentationTest {
    @Test
    void acceptsAnUnfilteredWorkbenchRequestAndUsesTheLatestThirtyDays() throws Exception {
        var method = WorkbenchController.class.getDeclaredMethod("get", String.class, LocalDate.class,
                LocalDate.class, String.class);
        assertThat(method.getParameters()[1].getAnnotation(RequestParam.class).required()).isFalse();
        assertThat(method.getParameters()[2].getAnnotation(RequestParam.class).required()).isFalse();

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        WorkbenchController.DateWindow window = WorkbenchController.dateWindow(null, null);
        assertThat(window.to()).isEqualTo(today);
        assertThat(window.from()).isEqualTo(today.minusDays(30));
    }

    @Test
    void rejectsAnInvertedWorkbenchDateRange() {
        assertThatThrownBy(() -> WorkbenchController.dateWindow(
                LocalDate.of(2026, 8, 23), LocalDate.of(2026, 8, 22)))
                .hasMessage("开始日期不能晚于结束日期");
    }

    @Test
    void translatesKnownAuditActionsWithoutExposingInternalCodes() {
        assertThat(WorkbenchController.actionLabel("TASK_ASSIGN")).isEqualTo("检测任务已派发");
        assertThat(WorkbenchController.actionLabel("DATA_DICTIONARY_UPDATED")).isEqualTo("数据字典已修改");
    }

    @Test
    void usesAStableChineseFallbackForUnknownAuditActions() {
        assertThat(WorkbenchController.actionLabel("FUTURE_INTERNAL_ACTION")).isEqualTo("业务记录已更新");
        assertThat(WorkbenchController.actionLabel(null)).isEqualTo("业务记录已更新");
    }
}
