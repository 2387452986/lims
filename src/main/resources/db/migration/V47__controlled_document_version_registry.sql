-- Report, commission-sheet and task-sheet version pages are typed projections of one
-- upstream field-code keyed registry. The actual template body remains owned by
-- configuration_version and detection_project_configuration.

DROP TRIGGER report_template_control_source_guard_trg ON report_template_control;
DROP INDEX report_template_control_project_ux;
DROP INDEX report_template_control_page_ix;
DROP INDEX report_template_control_search_ix;
DROP INDEX report_template_control_link_status_ix;

ALTER TABLE report_template_control RENAME TO document_template_control;
ALTER TABLE document_template_control
    ADD COLUMN document_type varchar(20) NOT NULL DEFAULT 'REPORT',
    ADD COLUMN source_field_code varchar(80) NOT NULL DEFAULT 'report_template_code';

-- Preserve the former source occurrence while making the source key's value domain explicit.
UPDATE document_template_control
SET source_key='report:' || source_key
WHERE document_type='REPORT' AND source_key NOT LIKE 'report:%';

ALTER TABLE document_template_control
    ALTER COLUMN document_type DROP DEFAULT,
    ALTER COLUMN source_field_code DROP DEFAULT,
    ADD CONSTRAINT document_template_control_type_check
        CHECK (document_type IN ('REPORT','COMMISSION','TASK')),
    ADD CONSTRAINT document_template_control_source_field_check
        CHECK (btrim(source_field_code) <> '');

ALTER TABLE document_template_control
    DROP CONSTRAINT report_template_control_organization_id_source_system_sourc_key,
    ADD CONSTRAINT document_template_control_source_ux
        UNIQUE (organization_id,document_type,source_system,source_key);

CREATE UNIQUE INDEX document_template_control_project_ux
    ON document_template_control(organization_id,document_type,project_revision_id)
    WHERE project_revision_id IS NOT NULL;
CREATE INDEX document_template_control_page_ix
    ON document_template_control(organization_id,document_type,source_occurrence,id);
CREATE INDEX document_template_control_search_ix
    ON document_template_control(organization_id,document_type,
        lower(project_name_snapshot),lower(parent_name_snapshot));
CREATE INDEX document_template_control_link_status_ix
    ON document_template_control(organization_id,document_type,link_status,source_occurrence,id);

ALTER FUNCTION guard_report_template_control_source() RENAME TO guard_document_template_control_source;
CREATE OR REPLACE FUNCTION guard_document_template_control_source() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.organization_id,NEW.document_type,NEW.project_revision_id,NEW.source_system,
           NEW.source_key,NEW.source_occurrence,NEW.source_field_code,NEW.project_name_snapshot,
           NEW.parent_name_snapshot,NEW.link_status,NEW.source_captured_at,NEW.source_manifest_sha256)
       IS DISTINCT FROM
       ROW(OLD.organization_id,OLD.document_type,OLD.project_revision_id,OLD.source_system,
           OLD.source_key,OLD.source_occurrence,OLD.source_field_code,OLD.project_name_snapshot,
           OLD.parent_name_snapshot,OLD.link_status,OLD.source_captured_at,OLD.source_manifest_sha256) THEN
        RAISE EXCEPTION 'document template control source identity is immutable';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER document_template_control_source_guard_trg
BEFORE UPDATE ON document_template_control
FOR EACH ROW EXECUTE FUNCTION guard_document_template_control_source();

-- The source pages expose the exact same 484 ordered project/parent rows. Values stay
-- independent and blank because both additional pages exposed no configured values.
INSERT INTO document_template_control(
  id,organization_id,document_type,project_revision_id,source_system,source_key,
  source_occurrence,source_field_code,project_name_snapshot,parent_name_snapshot,
  controlled_number,template_version_number,link_status,source_captured_at,
  source_manifest_sha256)
SELECT md5(control.id::text || ':COMMISSION')::uuid,control.organization_id,'COMMISSION',
  control.project_revision_id,control.source_system,'commission:' || regexp_replace(control.source_key,'^report:',''),
  control.source_occurrence,'zswt_template_code',control.project_name_snapshot,control.parent_name_snapshot,
  NULL,NULL,control.link_status,'2026-08-21T01:55:51Z'::timestamptz,
  '472e86d0460898967d5ba774d870b0ff7d98f304b2bc63a7bec35109195c0fc1'
FROM document_template_control control
WHERE control.document_type='REPORT' AND control.source_system='showcase_catalog'
ON CONFLICT (organization_id,document_type,source_system,source_key) DO NOTHING;

INSERT INTO document_template_control(
  id,organization_id,document_type,project_revision_id,source_system,source_key,
  source_occurrence,source_field_code,project_name_snapshot,parent_name_snapshot,
  controlled_number,template_version_number,link_status,source_captured_at,
  source_manifest_sha256)
SELECT md5(control.id::text || ':TASK')::uuid,control.organization_id,'TASK',
  control.project_revision_id,control.source_system,'task:' || regexp_replace(control.source_key,'^report:',''),
  control.source_occurrence,'rws_template_code',control.project_name_snapshot,control.parent_name_snapshot,
  NULL,NULL,control.link_status,'2026-08-21T01:55:51Z'::timestamptz,
  '472e86d0460898967d5ba774d870b0ff7d98f304b2bc63a7bec35109195c0fc1'
FROM document_template_control control
WHERE control.document_type='REPORT' AND control.source_system='showcase_catalog'
ON CONFLICT (organization_id,document_type,source_system,source_key) DO NOTHING;

INSERT INTO reference_configuration_import_manifest(
  resource_type,source_system,capture_sha256,source_captured_at,record_count)
VALUES
  ('COMMISSION_TEMPLATE_CONTROL','showcase_catalog',
   '472e86d0460898967d5ba774d870b0ff7d98f304b2bc63a7bec35109195c0fc1',
   '2026-08-21T01:55:51Z',484),
  ('TASK_TEMPLATE_CONTROL','showcase_catalog',
   '472e86d0460898967d5ba774d870b0ff7d98f304b2bc63a7bec35109195c0fc1',
   '2026-08-21T01:55:51Z',484)
ON CONFLICT DO NOTHING;

-- Existing report snapshots keep their identity and data while adopting the shared owner name.
ALTER TABLE report_version_template_control_snapshot
    RENAME TO report_version_document_control_snapshot;
ALTER TABLE report_version_document_control_snapshot
    RENAME COLUMN report_template_control_id TO document_template_control_id;
DROP INDEX report_version_template_control_project_ix;
DROP INDEX report_version_template_control_control_ix;
CREATE INDEX report_version_document_control_project_ix
    ON report_version_document_control_snapshot(project_revision_id,report_version_id);
CREATE INDEX report_version_document_control_control_ix
    ON report_version_document_control_snapshot(document_template_control_id)
    WHERE document_template_control_id IS NOT NULL;

ALTER FUNCTION protect_report_template_control_snapshot() RENAME TO protect_document_control_snapshot;
CREATE OR REPLACE FUNCTION protect_document_control_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'document control snapshots are immutable';
END $$;
ALTER TRIGGER report_template_control_snapshot_immutable_trg
    ON report_version_document_control_snapshot
    RENAME TO report_document_control_snapshot_immutable_trg;

CREATE TABLE commission_document_control_snapshot (
    id uuid PRIMARY KEY,
    commission_id uuid NOT NULL REFERENCES commission(id),
    commission_detection_selection_id uuid NOT NULL REFERENCES commission_detection_selection(id),
    project_revision_id uuid NOT NULL REFERENCES detection_project_revision(id),
    document_template_control_id uuid REFERENCES document_template_control(id),
    project_name_snapshot varchar(240) NOT NULL,
    controlled_number_snapshot varchar(160),
    template_version_number_snapshot varchar(120),
    control_lock_version bigint,
    frozen_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (commission_id,commission_detection_selection_id),
    CHECK ((document_template_control_id IS NULL) = (control_lock_version IS NULL))
);
CREATE INDEX commission_document_control_project_ix
    ON commission_document_control_snapshot(project_revision_id,commission_id);
CREATE INDEX commission_document_control_control_ix
    ON commission_document_control_snapshot(document_template_control_id)
    WHERE document_template_control_id IS NOT NULL;
CREATE TRIGGER commission_document_control_snapshot_immutable_trg
BEFORE UPDATE OR DELETE ON commission_document_control_snapshot
FOR EACH ROW EXECUTE FUNCTION protect_document_control_snapshot();

CREATE TABLE task_document_control_snapshot (
    id uuid PRIMARY KEY,
    test_task_id uuid NOT NULL UNIQUE REFERENCES test_task(id),
    commission_detection_selection_id uuid NOT NULL REFERENCES commission_detection_selection(id),
    project_revision_id uuid NOT NULL REFERENCES detection_project_revision(id),
    document_template_control_id uuid REFERENCES document_template_control(id),
    project_name_snapshot varchar(240) NOT NULL,
    controlled_number_snapshot varchar(160),
    template_version_number_snapshot varchar(120),
    control_lock_version bigint,
    frozen_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((document_template_control_id IS NULL) = (control_lock_version IS NULL))
);
CREATE INDEX task_document_control_project_ix
    ON task_document_control_snapshot(project_revision_id,test_task_id);
CREATE INDEX task_document_control_control_ix
    ON task_document_control_snapshot(document_template_control_id)
    WHERE document_template_control_id IS NOT NULL;
CREATE TRIGGER task_document_control_snapshot_immutable_trg
BEFORE UPDATE OR DELETE ON task_document_control_snapshot
FOR EACH ROW EXECUTE FUNCTION protect_document_control_snapshot();

-- Upgrade existing accepted business records without rewriting their frozen selections.
INSERT INTO commission_document_control_snapshot(
  id,commission_id,commission_detection_selection_id,project_revision_id,
  document_template_control_id,project_name_snapshot,controlled_number_snapshot,
  template_version_number_snapshot,control_lock_version,frozen_at)
SELECT gen_random_uuid(),commission.id,selection.id,selection.project_revision_id,
  control.id,selection.project_name_snapshot,control.controlled_number,
  control.template_version_number,control.lock_version,commission.selection_frozen_at
FROM commission_detection_selection selection
JOIN commission ON commission.id=selection.commission_id
LEFT JOIN document_template_control control
  ON control.organization_id=commission.organization_id
 AND control.document_type='COMMISSION'
 AND control.project_revision_id=selection.project_revision_id
WHERE commission.selection_frozen_at IS NOT NULL
ON CONFLICT (commission_id,commission_detection_selection_id) DO NOTHING;

INSERT INTO task_document_control_snapshot(
  id,test_task_id,commission_detection_selection_id,project_revision_id,
  document_template_control_id,project_name_snapshot,controlled_number_snapshot,
  template_version_number_snapshot,control_lock_version,frozen_at)
SELECT gen_random_uuid(),task.id,selection.id,selection.project_revision_id,
  control.id,selection.project_name_snapshot,control.controlled_number,
  control.template_version_number,control.lock_version,task.created_at
FROM test_task task
JOIN commission_parameter_selection parameter_selection ON parameter_selection.id=task.selection_id
JOIN commission_detection_selection selection ON selection.id=parameter_selection.detection_selection_id
JOIN commission ON commission.id=task.commission_id
LEFT JOIN document_template_control control
  ON control.organization_id=commission.organization_id
 AND control.document_type='TASK'
 AND control.project_revision_id=selection.project_revision_id
ON CONFLICT (test_task_id) DO NOTHING;
