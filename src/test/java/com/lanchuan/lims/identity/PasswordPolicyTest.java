package com.lanchuan.lims.identity;

import com.lanchuan.lims.common.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordPolicyTest {
    private final PasswordPolicy policy = new PasswordPolicy();

    @Test
    void acceptsStrongPasswordAndRejectsWeakOrUsernameBasedValues() {
        assertThatCode(() -> policy.validate("operator", "Secure#2026Pass")).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.validate("operator", "short"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> policy.validate("operator", "Operator#2026Pass"))
                .isInstanceOf(ApiException.class);
    }
}
