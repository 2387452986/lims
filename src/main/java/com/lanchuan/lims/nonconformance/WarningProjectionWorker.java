package com.lanchuan.lims.nonconformance;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Materializes durable warnings from authoritative business facts. Resolved warnings stay resolved;
 * later projection runs never rewrite their handling evidence.
 */
@Component
public class WarningProjectionWorker {
    private final JdbcClient jdbc;

    public WarningProjectionWorker(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Scheduled(fixedDelayString = "${lims.warnings.projection-delay-ms:60000}")
    @Transactional
    public void project() {
        projectEquipmentCertificates();
        projectNonconformances();
        projectOverdueSamples();
    }

    private void projectEquipmentCertificates() {
        jdbc.sql("""
                WITH latest AS (
                  SELECT DISTINCT ON (certificate.equipment_id,certificate.certificate_type)
                    certificate.id,certificate.equipment_id,certificate.certificate_type,
                    certificate.valid_until,equipment.organization_id,equipment.code,equipment.name
                  FROM equipment_certificate certificate JOIN equipment ON equipment.id=certificate.equipment_id
                  WHERE certificate.state='CONFIRMED' AND certificate.result IN ('PASS','LIMITED')
                  ORDER BY certificate.equipment_id,certificate.certificate_type,
                           certificate.valid_until DESC,certificate.created_at DESC,certificate.id DESC
                ), inserted AS (
                  INSERT INTO warning_record(id,organization_id,warning_type,source_type,source_id,content,occurred_at)
                  SELECT gen_random_uuid(),organization_id,
                    CASE certificate_type WHEN 'VERIFICATION' THEN 'EQUIPMENT_VERIFICATION_EXPIRED'
                         ELSE 'EQUIPMENT_CALIBRATION_EXPIRED' END,
                    'EQUIPMENT_CERTIFICATE',id,
                    format('设备 %s · %s 的%s证书已于 %s 到期',code,name,
                      CASE certificate_type WHEN 'VERIFICATION' THEN '检定' ELSE '校准' END,valid_until),
                    (valid_until::timestamp + interval '1 day') AT TIME ZONE 'Asia/Shanghai'
                  FROM latest WHERE valid_until<current_date
                  ON CONFLICT (organization_id,warning_type,source_type,source_id) DO NOTHING
                  RETURNING *
                )
                INSERT INTO audit_event(id,request_id,action,resource_type,resource_id,organization_id,after_json,comment)
                SELECT gen_random_uuid(),'warning-projection','WARNING_PROJECTED','WARNING_RECORD',id::text,
                  organization_id,to_jsonb(inserted),'系统根据已到期设备证书自动生成'
                FROM inserted
                """).update();
    }

    private void projectNonconformances() {
        jdbc.sql("""
                WITH inserted AS (
                  INSERT INTO warning_record(id,organization_id,warning_type,source_type,source_id,
                    project_id,commission_id,content,occurred_at)
                  SELECT gen_random_uuid(),commission.organization_id,'DETECTION_NONCONFORMING',
                    'NONCONFORMANCE',nonconformance.id,commission.project_id,commission.id,
                    format('不符合项 %s：%s',nonconformance.number,nonconformance.description),
                    nonconformance.created_at
                  FROM nonconformance JOIN commission ON commission.id=nonconformance.commission_id
                  WHERE nonconformance.state NOT IN ('CLOSED','REJECTED')
                  ON CONFLICT (organization_id,warning_type,source_type,source_id) DO NOTHING
                  RETURNING *
                )
                INSERT INTO audit_event(id,request_id,action,resource_type,resource_id,organization_id,after_json,comment)
                SELECT gen_random_uuid(),'warning-projection','WARNING_PROJECTED','WARNING_RECORD',id::text,
                  organization_id,to_jsonb(inserted),'系统根据未关闭不符合项自动生成'
                FROM inserted
                """).update();
    }

    private void projectOverdueSamples() {
        jdbc.sql("""
                WITH inserted AS (
                  INSERT INTO warning_record(id,organization_id,warning_type,source_type,source_id,
                    project_id,commission_id,content,occurred_at)
                  SELECT gen_random_uuid(),commission.organization_id,'SAMPLE_TEST_OVERDUE','SAMPLE',sample.id,
                    commission.project_id,commission.id,
                    format('样品 %s · %s 计划检测日期 %s 已逾期',sample.number,sample.name,sample.planned_test_date),
                    (sample.planned_test_date::timestamp + interval '1 day') AT TIME ZONE 'Asia/Shanghai'
                  FROM sample JOIN commission ON commission.id=sample.commission_id
                  WHERE sample.planned_test_date<current_date
                    AND sample.state NOT IN ('RETURNED','DISPOSED')
                    AND NOT EXISTS (
                      SELECT 1 FROM test_task task WHERE task.sample_id=sample.id AND task.state='COMPLETED')
                  ON CONFLICT (organization_id,warning_type,source_type,source_id) DO NOTHING
                  RETURNING *
                )
                INSERT INTO audit_event(id,request_id,action,resource_type,resource_id,organization_id,after_json,comment)
                SELECT gen_random_uuid(),'warning-projection','WARNING_PROJECTED','WARNING_RECORD',id::text,
                  organization_id,to_jsonb(inserted),'系统根据样品计划检测日期自动生成'
                FROM inserted
                """).update();
    }
}
