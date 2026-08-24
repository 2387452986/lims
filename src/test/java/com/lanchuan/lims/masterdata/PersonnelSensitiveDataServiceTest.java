package com.lanchuan.lims.masterdata;

import com.lanchuan.lims.common.ApiException;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PersonnelSensitiveDataServiceTest {
    private final PersonnelSensitiveDataService service = new PersonnelSensitiveDataService(
            Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes()));

    @Test
    void encryptsWithRandomNonceAndStableKeyedDigest() {
        var first = service.protectNationalId("46010019900101123X");
        var second = service.protectNationalId("460100-19900101123x");

        assertThat(first.cipher()).isNotEqualTo(second.cipher());
        assertThat(first.nonce()).isNotEqualTo(second.nonce());
        assertThat(first.digest()).isEqualTo(second.digest());
        assertThat(first.last4()).isEqualTo("123X");
        assertThat(service.reveal(first.cipher(), first.nonce())).isEqualTo("46010019900101123X");
        assertThat(service.masked(first.last4())).endsWith("123X").doesNotContain("460100");
    }

    @Test
    void refusesSensitiveOperationsWithoutAValidKey() {
        var missing = new PersonnelSensitiveDataService("");
        assertThatThrownBy(() -> missing.protectNationalId("46010019900101123X"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("密钥未配置");
    }
}
