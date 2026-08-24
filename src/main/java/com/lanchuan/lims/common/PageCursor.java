package com.lanchuan.lims.common;

import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;

public record PageCursor(OffsetDateTime updatedAt, UUID id) {
    public static PageCursor decode(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            int separator = decoded.lastIndexOf('|');
            if (separator < 1) throw new IllegalArgumentException();
            return new PageCursor(OffsetDateTime.parse(decoded.substring(0, separator)),
                    UUID.fromString(decoded.substring(separator + 1)));
        } catch (RuntimeException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CURSOR_INVALID", "分页游标无效或已损坏");
        }
    }

    public String encode() {
        String value = updatedAt + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
