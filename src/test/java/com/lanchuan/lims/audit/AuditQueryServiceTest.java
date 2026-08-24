package com.lanchuan.lims.audit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuditQueryServiceTest {
    @Test
    void descriptionsPreferOperatorCommentAndNeverExposeInternalActionCodes() {
        assertThat(AuditQueryService.descriptionFor(
                "TEMPLATE_NUMBERING_RULE_CREATED", "模板编号配置", null))
                .isEqualTo("新增模板编号配置");
        assertThat(AuditQueryService.descriptionFor(
                "BUSINESS_NUMBERING_RULE_PUBLISH", "工程流水号", "  已复核并发布  "))
                .isEqualTo("已复核并发布");
        assertThat(AuditQueryService.descriptionFor(
                "REPORT_ARCHIVE", "报告", ""))
                .isEqualTo("归档报告");
        assertThat(AuditQueryService.descriptionFor(
                "UNRECOGNIZED_ACTION", "配置", null))
                .isEqualTo("操作配置");
    }
}
