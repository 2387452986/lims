package com.lanchuan.lims.common;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record TransitionCommand(
        @NotBlank @Size(max = 80) String action,
        @NotNull @PositiveOrZero Long expectedVersion,
        @Size(max = 80) String reasonCode,
        @Size(max = 1000) String comment
) {}
