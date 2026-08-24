package com.lanchuan.lims.common;

import java.util.List;

public record CursorPage<T>(List<T> items, String nextCursor, boolean hasMore, long total) {}
