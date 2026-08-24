package com.lanchuan.lims.commission;

import com.lanchuan.lims.common.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommissionChangeStateTest {
    @Test
    void separatesDraftSubmissionFromIndependentReview() {
        assertThat(CommissionChangeState.DRAFT.allowedActions()).containsExactly("SUBMIT", "WITHDRAW");
        assertThat(CommissionChangeState.DRAFT.transition("SUBMIT")).isEqualTo(CommissionChangeState.SUBMITTED);
        assertThat(CommissionChangeState.SUBMITTED.transition("APPROVE")).isEqualTo(CommissionChangeState.APPROVED);
        assertThat(CommissionChangeState.SUBMITTED.transition("REJECT")).isEqualTo(CommissionChangeState.REJECTED);
        assertThat(CommissionChangeState.SUBMITTED.transition("WITHDRAW")).isEqualTo(CommissionChangeState.WITHDRAWN);
    }

    @Test
    void terminalRequestsCannotBeChangedAgain() {
        for (CommissionChangeState state : new CommissionChangeState[]{
                CommissionChangeState.APPROVED, CommissionChangeState.REJECTED, CommissionChangeState.WITHDRAWN}) {
            assertThat(state.allowedActions()).isEmpty();
            assertThatThrownBy(() -> state.transition("APPROVE")).isInstanceOf(ApiException.class);
        }
    }
}
