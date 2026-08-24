package com.lanchuan.lims.identity;

import com.lanchuan.lims.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class PasswordPolicy {
    public void validate(String username, String password) {
        boolean classes = password.chars().anyMatch(Character::isUpperCase)
                && password.chars().anyMatch(Character::isLowerCase)
                && password.chars().anyMatch(Character::isDigit)
                && password.chars().anyMatch(ch -> !Character.isLetterOrDigit(ch));
        if (password.length() < 12 || password.length() > 128 || !classes
                || password.toLowerCase().contains(username.toLowerCase())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WEAK_PASSWORD",
                    "密码须为12至128位，并同时包含大小写字母、数字和特殊字符，且不能包含账号");
        }
    }
}
