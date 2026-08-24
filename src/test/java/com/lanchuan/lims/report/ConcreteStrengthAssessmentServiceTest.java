package com.lanchuan.lims.report;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcreteStrengthAssessmentServiceTest {

    @Test
    void calculatesControlledDescriptiveStatisticsWithSampleDeviation() {
        var statistics = ConcreteStrengthAssessmentService.statistics(List.of(
                new BigDecimal("20"), new BigDecimal("25"), new BigDecimal("30")));

        assertEquals(3, statistics.count());
        assertEquals(new BigDecimal("25.000000"), statistics.average());
        assertEquals(new BigDecimal("5.000000"), statistics.standardDeviation());
        assertEquals(new BigDecimal("20.000000"), statistics.minimum());
    }

    @Test
    void oneSpecimenGroupHasZeroSampleDeviation() {
        var statistics = ConcreteStrengthAssessmentService.statistics(List.of(new BigDecimal("42.25")));

        assertEquals(new BigDecimal("42.250000"), statistics.average());
        assertEquals(new BigDecimal("0.000000"), statistics.standardDeviation());
        assertEquals(new BigDecimal("42.250000"), statistics.minimum());
    }

    @Test
    void rejectsEmptyStatisticsInputAndExposesNoRetiredActions() {
        assertThrows(IllegalArgumentException.class,
                () -> ConcreteStrengthAssessmentService.statistics(List.of()));
        assertEquals(List.of(), ConcreteStrengthAssessmentService.actions("RETIRED"));
        assertTrue(ConcreteStrengthAssessmentService.actions("APPROVED").contains("RETIRE"));
    }

    @Test
    void rejectsAnyRepeatedActorAcrossTestReviewAndApprovalDuties() {
        var tester = java.util.UUID.randomUUID();
        var reviewer = java.util.UUID.randomUUID();
        var approver = java.util.UUID.randomUUID();

        assertTrue(ConcreteStrengthAssessmentService.repeatsDuty(tester, tester));
        assertTrue(ConcreteStrengthAssessmentService.repeatsDuty(reviewer, tester, reviewer));
        assertFalse(ConcreteStrengthAssessmentService.repeatsDuty(approver, tester, reviewer));
    }
}
