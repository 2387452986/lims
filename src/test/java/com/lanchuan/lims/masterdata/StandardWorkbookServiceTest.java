package com.lanchuan.lims.masterdata;

import com.lanchuan.lims.audit.AuditService;
import com.lanchuan.lims.common.ApiException;
import com.lanchuan.lims.identity.CurrentActor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StandardWorkbookServiceTest {
    private final StandardService standards = mock(StandardService.class);
    private final CurrentActor actor = mock(CurrentActor.class);
    private final AuditService audit = mock(AuditService.class);
    private final StandardWorkbookService service = new StandardWorkbookService(standards, actor, audit);

    @Test
    void controlledWorkbookImportsAValidatedRowAsDraft() throws Exception {
        String code = "GB/T SHOWCASE-1";
        when(standards.latestByCode(code)).thenReturn(Optional.empty());

        StandardWorkbookService.ImportResult result = service.importWorkbook(workbook(
                code, "受控导入验证标准", "系统", "检测依据", "国标", "测试发布机构", "测试引用依据",
                "2026-08-19", "", "现行"), "standards.xlsx");

        assertThat(result.importedCount()).isEqualTo(1);
        assertThat(result.createdCount()).isEqualTo(1);
        assertThat(result.revisedCount()).isZero();
        verify(standards).create(any(StandardService.Write.class));
    }

    @Test
    void oneInvalidRowRejectsTheWholeWorkbookBeforeWrites() throws Exception {
        byte[] content = workbook("GB/T SHOWCASE-2", "日期错误标准", "系统", "检测依据", "国标",
                "测试发布机构", "", "2026-08-20", "2026-08-19", "现行");

        assertThatThrownBy(() -> service.importWorkbook(content, "standards.xlsx"))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.code()).isEqualTo("STANDARD_IMPORT_VALIDATION_FAILED");
                    assertThat(error.blockers()).anyMatch(value -> value.contains("废止日期不能早于实施日期"));
                });
        verify(standards, never()).create(any());
        verify(standards, never()).update(any(), any());
    }

    private byte[] workbook(String... values) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(service.template()));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var row = workbook.getSheet("标准库").createRow(2);
            for (int index = 0; index < values.length; index++) row.createCell(index).setCellValue(values[index]);
            workbook.write(output);
            return output.toByteArray();
        }
    }
}
