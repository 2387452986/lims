package com.lanchuan.lims.configuration;

import java.util.List;

/**
 * Boundary implemented by a real hardware or platform connector. The core application never
 * synthesizes external readings when no adapter is installed.
 */
public interface ExternalIntegrationAdapter {
    String key();
    Readiness readiness();

    record Readiness(boolean ready, List<String> blockers) {}
}
