package com.lanchuan.lims.masterdata;

import com.lanchuan.lims.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class PersonnelSensitiveDataService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final String encodedKey;

    public PersonnelSensitiveDataService(@Value("${lims.personnel.encryption-key:}") String encodedKey) {
        this.encodedKey = encodedKey == null ? "" : encodedKey.trim();
    }

    public record ProtectedValue(byte[] cipher, byte[] nonce, String digest, String last4) {}

    public ProtectedValue protectNationalId(String value) {
        String normalized = normalize(value);
        if (normalized == null) return null;
        if (!normalized.matches("[0-9A-Z]{6,32}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NATIONAL_ID_INVALID", "身份证件号码格式无效");
        }
        try {
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(normalized.getBytes(StandardCharsets.UTF_8));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key());
            String digest = HexFormat.of().formatHex(mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8)));
            return new ProtectedValue(encrypted, nonce, digest, normalized.substring(normalized.length() - 4));
        } catch (GeneralSecurityException exception) {
            throw unavailable();
        }
    }

    public String reveal(byte[] encrypted, byte[] nonce) {
        if (encrypted == null || nonce == null) return null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, nonce));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw unavailable();
        }
    }

    public String masked(String last4) {
        return last4 == null ? null : "**************" + last4;
    }

    private SecretKeySpec key() {
        try {
            byte[] decoded = Base64.getDecoder().decode(encodedKey);
            if (decoded.length != 32) throw unavailable();
            return new SecretKeySpec(decoded, "AES");
        } catch (IllegalArgumentException exception) {
            throw unavailable();
        }
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) return null;
        return value.replace(" ", "").replace("-", "").trim().toUpperCase();
    }

    private ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PERSONNEL_DATA_KEY_UNAVAILABLE",
                "人员敏感资料密钥未配置或无效，已阻止保存和读取");
    }
}
