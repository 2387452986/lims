package com.lanchuan.lims.workbench;

import com.lanchuan.lims.common.CursorPage;
import com.lanchuan.lims.identity.AuthenticatedUser;
import com.lanchuan.lims.report.ReportQueueService;
import com.lanchuan.lims.sample.SampleLedgerService;
import com.lanchuan.lims.storedfile.StoredFileService;
import com.lanchuan.lims.task.TaskQueueService;
import com.lanchuan.lims.test.TestLedgerService;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class DomainLedgerExportWorker {
    private static final Logger log = LoggerFactory.getLogger(DomainLedgerExportWorker.class);
    private static final int MAX_ROWS = 50_000;
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final StoredFileService files;
    private final SampleLedgerService samples;
    private final TaskQueueService tasks;
    private final TestLedgerService tests;
    private final ReportQueueService reports;
    private final String workerId;

    public DomainLedgerExportWorker(JdbcClient jdbc, TransactionTemplate transactions, ObjectMapper json,
                                    StoredFileService files, SampleLedgerService samples, TaskQueueService tasks,
                                    TestLedgerService tests, ReportQueueService reports,
                                    @Value("${lims.jobs.worker-id:${HOSTNAME:local}-domain-ledger-worker}") String workerId) {
        this.jdbc=jdbc; this.transactions=transactions; this.json=json; this.files=files;
        this.samples=samples; this.tasks=tasks; this.tests=tests; this.reports=reports; this.workerId=workerId;
    }

    @Scheduled(fixedDelayString = "${lims.jobs.domain-ledger-poll-delay-ms:1500}")
    void poll() {
        Job job=transactions.execute(status->claim());
        if(job==null)return;
        try { Result result=export(job); transactions.executeWithoutResult(status->complete(job,result)); }
        catch(Exception exception){log.error("Domain ledger export job {} failed",job.id(),exception);transactions.executeWithoutResult(status->fail(job.id(),exception));}
    }

    private Job claim(){
        return jdbc.sql("""
                WITH candidate AS (
                  SELECT id FROM background_job WHERE job_type='EXPORT_DOMAIN_LEDGER_XLSX'
                    AND state IN ('READY','RETRY') AND available_at<=now()
                  ORDER BY available_at,created_at FOR UPDATE SKIP LOCKED LIMIT 1
                )
                UPDATE background_job job SET state='RUNNING',locked_by=:worker,locked_at=now(),
                  attempt_count=attempt_count+1,version=version+1
                FROM candidate WHERE job.id=candidate.id RETURNING job.id,job.payload::text
                """).param("worker",workerId).query((rs,row)->new Job(rs.getObject("id",UUID.class),rs.getString("payload"))).optional().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private Result export(Job job) throws Exception {
        Map<String,Object> payload=json.readValue(job.payload(),new TypeReference<>(){});
        UUID actorId=uuid(payload.get("actorId"));UUID organizationId=uuid(payload.get("organizationId"));
        AuthenticatedUser user=new AuthenticatedUser(actorId,optionalUuid(payload.get("personId")),organizationId,
                optionalUuid(payload.get("departmentId")),"background-job",actorName(actorId,organizationId),false,0,
                text(payload.get("dataScope")),List.of(),List.of());
        String domain=text(payload.get("domain"));Map<String,Object> request=(Map<String,Object>)payload.get("request");
        ExportData data=switch(domain){
            case "SAMPLE_LEDGER"->sampleData(user,request);
            case "TASK_QUEUE"->taskData(user,request);
            case "TEST_LEDGER"->testData(user,request);
            case "REPORT_QUEUE"->reportData(user,request);
            default->throw new IllegalArgumentException("unsupported domain ledger export");
        };
        StoredFileService.View stored=files.storeGenerated(workbook(data,user.displayName()),
                data.sheetName()+"-"+LocalDate.now()+".xlsx",XLSX,domain+"_EXPORT",actorId);
        return new Result(stored.id(),actorId,organizationId,domain,data.rows().size(),stored.sha256());
    }

    private ExportData sampleData(AuthenticatedUser user,Map<String,Object> request){
        List<String[]> rows=new ArrayList<>();String cursor="";
        do{CursorPage<SampleLedgerService.Row> page=samples.listFor(user,new SampleLedgerService.Filters(
                text(request.get("view")),text(request.get("q")),text(request.get("state")),date(request.get("from")),
                date(request.get("to")),cursor,100));for(var row:page.items())rows.add(new String[]{row.sampleNumber(),
                row.commissionNumber(),row.projectNumber(),row.projectName(),row.customerName(),row.detectionProjects(),
                row.sampleName(),row.specification(),row.receivedQuantity(),row.availableQuantity(),row.unit(),
                value(row.samplingDate()),value(row.moldingDate()),value(row.ageDays()),value(row.plannedTestDate()),
                value(row.actualTestDate()),row.curingMethod(),row.strengthGrade(),row.structurePart(),row.chipCode(),row.state()});
            ensureLimit(rows);cursor=page.hasMore()?page.nextCursor():"";}while(!cursor.isEmpty());
        return new ExportData("样品台账",List.of("样品编号","委托编号","工程编号","工程名称","客户","检测项目","样品名称",
                "规格","接收数量","可用数量","单位","取样日期","成型日期","龄期","计划检测","实际检测","养护方式","强度等级","工程部位","芯片码","状态"),rows);
    }

    private ExportData taskData(AuthenticatedUser user,Map<String,Object> request){
        List<String[]> rows=new ArrayList<>();String cursor="";
        do{CursorPage<TaskQueueService.Row> page=tasks.listFor(user,new TaskQueueService.Filters(text(request.get("view")),
                text(request.get("q")),text(request.get("state")),date(request.get("dueFrom")),date(request.get("dueTo")),cursor,100));
            for(var row:page.items())rows.add(new String[]{row.taskNumber(),row.commissionNumber(),row.sampleNumber(),row.sampleName(),
                    row.parameterCode(),row.parameterName(),row.projectNumber(),row.projectName(),row.customerName(),row.assigneeName(),
                    value(row.dueAt()),row.state(),value(row.acceptedAt()),value(row.startedAt()),value(row.completedAt())});
            ensureLimit(rows);cursor=page.hasMore()?page.nextCursor():"";}while(!cursor.isEmpty());
        return new ExportData("任务队列",List.of("任务编号","委托编号","样品编号","样品名称","参数编码","检测参数","工程编号",
                "工程名称","客户","检测人员","计划完成","状态","接收时间","开始时间","完成时间"),rows);
    }

    private ExportData testData(AuthenticatedUser user,Map<String,Object> request){
        List<String[]> rows=new ArrayList<>();String cursor="";
        do{CursorPage<TestLedgerService.Row> page=tests.listFor(user,new TestLedgerService.Filters(text(request.get("view")),
                text(request.get("q")),text(request.get("state")),text(request.get("result")),date(request.get("from")),
                date(request.get("to")),cursor,100));for(var row:page.items())rows.add(new String[]{row.taskNumber(),
                row.commissionNumber(),row.sampleNumber(),row.sampleName(),row.parameterCode(),row.parameterName(),
                row.projectNumber(),row.projectName(),row.customerName(),row.testerName(),row.equipmentCode(),row.equipmentName(),
                String.valueOf(row.revision()),row.result(),row.state(),value(row.submittedAt()),value(row.confirmedAt())});
            ensureLimit(rows);cursor=page.hasMore()?page.nextCursor():"";}while(!cursor.isEmpty());
        return new ExportData("试验台账",List.of("任务编号","委托编号","样品编号","样品名称","参数编码","检测参数","工程编号",
                "工程名称","客户","检测人员","设备编号","设备名称","记录版本","结论","状态","提交时间","确认时间"),rows);
    }

    private ExportData reportData(AuthenticatedUser user,Map<String,Object> request){
        List<String[]> rows=new ArrayList<>();String cursor="";
        do{CursorPage<ReportQueueService.Row> page=reports.listFor(user,new ReportQueueService.Filters(text(request.get("view")),
                text(request.get("q")),text(request.get("states")),date(request.get("from")),date(request.get("to")),cursor,100));
            for(var row:page.items())rows.add(new String[]{row.reportNumber(),row.revisionLabel(),row.title(),row.commissionNumber(),
                    row.projectNumber(),row.projectName(),row.customerName(),row.state(),row.pdfStatus(),row.signed()?"已签名":"未签名",
                    row.sealed()?"已盖章":"未盖章",String.valueOf(row.distributedCount()),String.valueOf(row.acknowledgedCount()),
                    row.archiveNumber(),value(row.updatedAt())});ensureLimit(rows);cursor=page.hasMore()?page.nextCursor():"";}while(!cursor.isEmpty());
        return new ExportData("报告台账",List.of("报告编号","版本","报告名称","委托编号","工程编号","工程名称","客户","状态",
                "PDF","签名","机构章","发放份数","签收份数","归档编号","更新时间"),rows);
    }

    private byte[] workbook(ExportData data,String actorName)throws Exception{
        try(SXSSFWorkbook workbook=new SXSSFWorkbook(100);ByteArrayOutputStream output=new ByteArrayOutputStream()){
            workbook.setCompressTempFiles(true);var sheet=workbook.createSheet(data.sheetName());
            sheet.addMergedRegion(new CellRangeAddress(0,0,0,data.headers().size()-1));var watermark=sheet.createRow(0).createCell(0);
            watermark.setCellValue("内部受控导出 · "+actorName+" · "+LOCAL_TIME.format(OffsetDateTime.now()));CellStyle watermarkStyle=workbook.createCellStyle();
            var watermarkFont=workbook.createFont();watermarkFont.setBold(true);watermarkFont.setColor(IndexedColors.RED.getIndex());watermarkStyle.setFont(watermarkFont);watermark.setCellStyle(watermarkStyle);
            var header=sheet.createRow(1);CellStyle headerStyle=workbook.createCellStyle();headerStyle.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            for(int index=0;index<data.headers().size();index++){var cell=header.createCell(index);cell.setCellValue(data.headers().get(index));cell.setCellStyle(headerStyle);sheet.setColumnWidth(index,Math.min(36,Math.max(14,data.headers().get(index).length()+8))*256);}
            for(int rowIndex=0;rowIndex<data.rows().size();rowIndex++){var row=sheet.createRow(rowIndex+2);String[] values=data.rows().get(rowIndex);for(int column=0;column<values.length;column++)row.createCell(column).setCellValue(value(values[column]));}
            workbook.write(output);workbook.dispose();return output.toByteArray();
        }
    }

    private void complete(Job job,Result result){
        int changed=jdbc.sql("""
                UPDATE background_job SET state='COMPLETED',result_file_id=:file,completed_at=now(),
                  last_error=NULL,locked_by=NULL,locked_at=NULL,version=version+1
                WHERE id=:id AND locked_by=:worker AND state='RUNNING'
                """).param("file",result.fileId()).param("id",job.id()).param("worker",workerId).update();
        if(changed!=1)throw new IllegalStateException("domain export job ownership changed before completion");
        try{String details=json.writeValueAsString(Map.of("fileId",result.fileId(),"domain",result.domain(),"rowCount",result.rowCount(),"sha256",result.sha256()));
            jdbc.sql("""
                    INSERT INTO audit_event(id,request_id,actor_id,actor_name,action,resource_type,resource_id,organization_id,after_json)
                    SELECT :audit,:request,user_account.id,user_account.display_name,'DOMAIN_LEDGER_EXPORT_COMPLETED',
                      'BACKGROUND_JOB',:resource,:organization,CAST(:details AS jsonb)
                    FROM app_user user_account WHERE user_account.id=:actor
                    """).param("audit",UUID.randomUUID()).param("request","job:"+job.id()).param("resource",job.id().toString())
                    .param("organization",result.organizationId()).param("details",details).param("actor",result.actorId()).update();
        }catch(Exception exception){throw new IllegalStateException("domain export audit encoding failed",exception);}
    }

    private void fail(UUID id,Exception exception){String message=exception.getClass().getSimpleName()+": "+String.valueOf(exception.getMessage());if(message.length()>1900)message=message.substring(0,1900);jdbc.sql("""
            UPDATE background_job SET state=CASE WHEN attempt_count>=3 THEN 'FAILED' ELSE 'RETRY' END,
              available_at=now()+(attempt_count*interval '1 minute'),last_error=:error,
              locked_by=NULL,locked_at=NULL,version=version+1 WHERE id=:id AND locked_by=:worker
            """).param("error",message).param("id",id).param("worker",workerId).update();}
    private String actorName(UUID actorId,UUID organizationId){return jdbc.sql("SELECT display_name FROM app_user WHERE id=:id AND organization_id=:organization").param("id",actorId).param("organization",organizationId).query(String.class).optional().orElseThrow(()->new IllegalStateException("queued export actor no longer exists"));}
    private void ensureLimit(List<String[]> rows){if(rows.size()>MAX_ROWS)throw new IllegalStateException("导出结果超过50000行，请缩小查询范围");}
    private UUID uuid(Object value){return UUID.fromString(String.valueOf(value));}private UUID optionalUuid(Object value){return value==null||String.valueOf(value).isBlank()||"null".equals(String.valueOf(value))?null:uuid(value);}
    private LocalDate date(Object value){return value==null||String.valueOf(value).isBlank()||"null".equals(String.valueOf(value))?null:LocalDate.parse(String.valueOf(value));}
    private String text(Object value){return value==null||"null".equals(String.valueOf(value))?"":String.valueOf(value);}private String value(Object value){return value==null?"":value instanceof OffsetDateTime time?LOCAL_TIME.format(time):String.valueOf(value);}
    private record Job(UUID id,String payload){}private record Result(UUID fileId,UUID actorId,UUID organizationId,String domain,int rowCount,String sha256){}private record ExportData(String sheetName,List<String> headers,List<String[]> rows){}
}
