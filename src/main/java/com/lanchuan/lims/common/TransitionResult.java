package com.lanchuan.lims.common;

import java.util.List;

public record TransitionResult(String state, long version, List<String> allowedActions) {}
