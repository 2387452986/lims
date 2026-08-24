package com.lanchuan.lims.identity;

import java.io.Serializable;
import java.security.Principal;
import java.util.List;
import java.util.UUID;

public record AuthenticatedUser(
        UUID id,
        UUID personId,
        UUID organizationId,
        UUID departmentId,
        String username,
        String displayName,
        boolean mustChangePassword,
        long sessionVersion,
        String dataScope,
        List<String> roles,
        List<String> permissions
) implements Principal, Serializable {
    @Override public String getName() { return username; }
}
