package com.lanchuan.lims.workflow;

import com.lanchuan.lims.commission.CommissionState;
import com.lanchuan.lims.common.ApiException;
import com.lanchuan.lims.equipment.EquipmentState;
import com.lanchuan.lims.nonconformance.NonconformanceState;
import com.lanchuan.lims.report.ReportState;
import com.lanchuan.lims.sample.SampleState;
import com.lanchuan.lims.task.TaskState;
import com.lanchuan.lims.test.TestRecordState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StateMachineTest {
    @Test
    void primaryFlowsReachTerminalStates() {
        assertThat(CommissionState.DRAFT.transition("SUBMIT").transition("ACCEPT")
                .transition("START_SAMPLING").transition("COMPLETE_SAMPLING")
                .transition("START_TESTING").transition("COMPLETE_TESTING")
                .transition("MARK_ISSUED").transition("ARCHIVE")).isEqualTo(CommissionState.ARCHIVED);
        assertThat(SampleState.EXPECTED.transition("RECEIVE").transition("HANDOVER")
                .transition("START_TEST").transition("RETAIN").transition("DISPOSE"))
                .isEqualTo(SampleState.DISPOSED);
        assertThat(TestRecordState.DRAFT.transition("SUBMIT").transition("CONFIRM"))
                .isEqualTo(TestRecordState.CONFIRMED);
        assertThat(ReportState.DRAFT.transition("SUBMIT_REVIEW").transition("APPROVE")
                .transition("ISSUE").transition("SEAL").transition("DISTRIBUTE").transition("ARCHIVE"))
                .isEqualTo(ReportState.ARCHIVED);
        assertThat(NonconformanceState.OPEN.transition("CONFIRM").transition("START_DISPOSITION")
                .transition("SUBMIT_VERIFICATION").transition("CLOSE"))
                .isEqualTo(NonconformanceState.CLOSED);
    }

    @Test
    void taskSubmissionCanOnlyBeDrivenByControlledTestRecord() {
        assertThatThrownBy(() -> TaskState.IN_PROGRESS.transition("SUBMIT")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> TaskState.RETURNED.transition("RESUBMIT")).isInstanceOf(ApiException.class);
    }

    @Test
    void submittedCommissionCanBeWithdrawnBeforeAcceptance() {
        assertThat(CommissionState.DRAFT.transition("SUBMIT").transition("WITHDRAW"))
                .isEqualTo(CommissionState.DRAFT);
        assertThatThrownBy(() -> CommissionState.ACCEPTED.transition("WITHDRAW"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void equipmentOperationsReturnToAnAvailableControlledState() {
        assertThat(EquipmentState.AVAILABLE.transition("START_USE").transition("END_USE"))
                .isEqualTo(EquipmentState.AVAILABLE);
        assertThat(EquipmentState.AVAILABLE.transition("BORROW").transition("RETURN"))
                .isEqualTo(EquipmentState.AVAILABLE);
        assertThat(EquipmentState.AVAILABLE.transition("SEND_INSPECTION").transition("VERIFIED"))
                .isEqualTo(EquipmentState.AVAILABLE);
        assertThat(EquipmentState.AVAILABLE.transition("SEND_CALIBRATION").transition("CALIBRATED"))
                .isEqualTo(EquipmentState.AVAILABLE);
        assertThat(EquipmentState.AVAILABLE.transition("STOCK_OUT").transition("STOCK_IN"))
                .isEqualTo(EquipmentState.AVAILABLE);
        assertThat(EquipmentState.AVAILABLE.transition("SCRAP")).isEqualTo(EquipmentState.SCRAPPED);
    }

    @Test
    void sampleReturnAndDisposalAreTerminalAlternatives() {
        assertThat(SampleState.RECEIVED.transition("RETURN")).isEqualTo(SampleState.RETURNED);
        assertThat(SampleState.HANDED_OVER.transition("START_TEST").transition("RETURN"))
                .isEqualTo(SampleState.RETURNED);
        assertThat(SampleState.IN_TEST.transition("DISPOSE")).isEqualTo(SampleState.DISPOSED);
        assertThatThrownBy(() -> SampleState.RETURNED.transition("HANDOVER")).isInstanceOf(ApiException.class);
    }
}
