package com.lanchuan.lims.report;

import org.springframework.stereotype.Component;

@Component
public class ReportRevisionPolicy {
    public String label(int revision) {
        if (revision < 1) throw new IllegalArgumentException("Report revision must be positive");
        return "R" + revision;
    }

    public PolicyView view() {
        return new PolicyView("CONTROLLED_FIXED", "R{n}", "R1", "报告主编号保持稳定，修订按 R1、R2 递增；已签发版本不可覆盖。");
    }

    public record PolicyView(String mode, String pattern, String firstRevision, String description) {}
}
