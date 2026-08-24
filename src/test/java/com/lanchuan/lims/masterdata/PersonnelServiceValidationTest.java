package com.lanchuan.lims.masterdata;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PersonnelServiceValidationTest {
    @Test
    void validatesResidentIdentityNumberChecksum() {
        assertThat(PersonnelService.validResidentIdentityNumber("110105199001010037")).isTrue();
        assertThat(PersonnelService.validResidentIdentityNumber("110105199001010038")).isFalse();
        assertThat(PersonnelService.validResidentIdentityNumber("not-an-id")).isFalse();
    }
}
