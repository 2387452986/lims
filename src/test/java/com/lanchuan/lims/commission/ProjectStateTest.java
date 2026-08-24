package com.lanchuan.lims.commission;

import com.lanchuan.lims.common.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectStateTest {
    @Test
    void supportsControlledLifecycleWithoutPhysicalDeletion() {
        assertThat(ProjectState.DRAFT.transition("SUBMIT")).isEqualTo(ProjectState.ACTIVE);
        assertThat(ProjectState.ACTIVE.transition("SUSPEND")).isEqualTo(ProjectState.SUSPENDED);
        assertThat(ProjectState.SUSPENDED.transition("ACTIVATE")).isEqualTo(ProjectState.ACTIVE);
        assertThat(ProjectState.ACTIVE.transition("COMPLETE")).isEqualTo(ProjectState.COMPLETED);
        assertThat(ProjectState.COMPLETED.transition("REOPEN")).isEqualTo(ProjectState.ACTIVE);
        assertThat(ProjectState.ACTIVE.transition("CLOSE")).isEqualTo(ProjectState.CLOSED);
        assertThat(ProjectState.CLOSED.allowedActions()).isEmpty();
    }

    @Test
    void rejectsInvalidTransition() {
        assertThatThrownBy(() -> ProjectState.CLOSED.transition("REOPEN"))
                .isInstanceOf(ApiException.class);
    }
}
