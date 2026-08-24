package com.lanchuan.lims.configuration;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class DocumentControlSnapshotService {
    private final JdbcClient jdbc;

    public DocumentControlSnapshotService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void snapshotCommission(UUID commissionId) {
        jdbc.sql("""
                INSERT INTO commission_document_control_snapshot(
                  id,commission_id,commission_detection_selection_id,project_revision_id,
                  document_template_control_id,project_name_snapshot,controlled_number_snapshot,
                  template_version_number_snapshot,control_lock_version)
                SELECT gen_random_uuid(),commission.id,selection.id,selection.project_revision_id,
                  control.id,selection.project_name_snapshot,control.controlled_number,
                  control.template_version_number,control.lock_version
                FROM commission_detection_selection selection
                JOIN commission ON commission.id=selection.commission_id
                LEFT JOIN document_template_control control
                  ON control.organization_id=commission.organization_id
                 AND control.document_type='COMMISSION'
                 AND control.project_revision_id=selection.project_revision_id
                WHERE commission.id=:commission
                ON CONFLICT (commission_id,commission_detection_selection_id) DO NOTHING
                """).param("commission", commissionId).update();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void snapshotTask(UUID taskId) {
        int inserted = jdbc.sql("""
                INSERT INTO task_document_control_snapshot(
                  id,test_task_id,commission_detection_selection_id,project_revision_id,
                  document_template_control_id,project_name_snapshot,controlled_number_snapshot,
                  template_version_number_snapshot,control_lock_version)
                SELECT gen_random_uuid(),task.id,selection.id,selection.project_revision_id,
                  control.id,selection.project_name_snapshot,control.controlled_number,
                  control.template_version_number,control.lock_version
                FROM test_task task
                JOIN commission_parameter_selection parameter_selection
                  ON parameter_selection.id=task.selection_id
                JOIN commission_detection_selection selection
                  ON selection.id=parameter_selection.detection_selection_id
                JOIN commission ON commission.id=task.commission_id
                LEFT JOIN document_template_control control
                  ON control.organization_id=commission.organization_id
                 AND control.document_type='TASK'
                 AND control.project_revision_id=selection.project_revision_id
                WHERE task.id=:task
                ON CONFLICT (test_task_id) DO NOTHING
                """).param("task", taskId).update();
        if (inserted != 1) {
            throw new IllegalStateException("task document control snapshot was not created");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void snapshotReport(UUID reportVersionId, UUID commissionId) {
        jdbc.sql("""
                INSERT INTO report_version_document_control_snapshot(
                  id,report_version_id,commission_detection_selection_id,project_revision_id,
                  document_template_control_id,project_name_snapshot,controlled_number_snapshot,
                  template_version_number_snapshot,control_lock_version)
                SELECT gen_random_uuid(),:version,selection.id,selection.project_revision_id,
                  control.id,selection.project_name_snapshot,control.controlled_number,
                  control.template_version_number,control.lock_version
                FROM commission_detection_selection selection
                JOIN commission ON commission.id=selection.commission_id
                LEFT JOIN document_template_control control
                  ON control.organization_id=commission.organization_id
                 AND control.document_type='REPORT'
                 AND control.project_revision_id=selection.project_revision_id
                WHERE selection.commission_id=:commission
                ON CONFLICT (report_version_id,commission_detection_selection_id) DO NOTHING
                """).param("version", reportVersionId).param("commission", commissionId).update();
    }
}
