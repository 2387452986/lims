package com.lanchuan.lims.masterdata;

import com.lanchuan.lims.common.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PersonnelQualificationServiceTest {

    @Test
    void followsControlledSubmitReviewAndValidityLifecycle() {
        assertEquals("SUBMITTED", PersonnelQualificationService.nextState("DRAFT", "SUBMIT"));
        assertEquals("DRAFT", PersonnelQualificationService.nextState("SUBMITTED", "WITHDRAW"));
        assertEquals("RETURNED", PersonnelQualificationService.nextState("SUBMITTED", "RETURN"));
        assertEquals("VALID", PersonnelQualificationService.nextState("SUBMITTED", "APPROVE"));
        assertEquals("SUSPENDED", PersonnelQualificationService.nextState("VALID", "SUSPEND"));
        assertEquals("VALID", PersonnelQualificationService.nextState("SUSPENDED", "ACTIVATE"));
        assertEquals("EXPIRED", PersonnelQualificationService.nextState("SUSPENDED", "EXPIRE"));
        assertEquals("ARCHIVED", PersonnelQualificationService.nextState("RETURNED", "ARCHIVE"));
    }

    @Test
    void exposesOnlyCurrentActionsAndRejectsBypasses() {
        assertEquals(List.of("SUBMIT", "ARCHIVE"), PersonnelQualificationService.actions("DRAFT"));
        assertEquals(List.of("WITHDRAW", "APPROVE", "RETURN"), PersonnelQualificationService.actions("SUBMITTED"));
        assertEquals(List.of(), PersonnelQualificationService.actions("ARCHIVED"));
        ApiException exception = assertThrows(ApiException.class,
                () -> PersonnelQualificationService.nextState("DRAFT", "APPROVE"));
        assertEquals("QUALIFICATION_TRANSITION_INVALID", exception.code());
        assertEquals(422, exception.status().value());
    }
}
