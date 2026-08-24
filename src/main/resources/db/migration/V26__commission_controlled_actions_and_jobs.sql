-- Controlled counterparts for the reference commission "more actions" menu.

ALTER TABLE commission
    ADD COLUMN external_number_bound_at timestamptz,
    ADD COLUMN external_number_bound_by uuid REFERENCES app_user(id),
    ADD COLUMN report_print_restricted boolean NOT NULL DEFAULT false,
    ADD COLUMN report_print_limit integer CHECK (report_print_limit IS NULL OR report_print_limit >= 0),
    ADD COLUMN report_print_restriction_reason varchar(1000);

UPDATE commission commission
SET report_print_restricted = project.report_print_restricted,
    report_print_limit = project.customer_print_limit
FROM project
WHERE project.id = commission.project_id;

CREATE UNIQUE INDEX commission_external_number_v26_ux
    ON commission(organization_id, external_number)
    WHERE external_number IS NOT NULL;

CREATE TABLE commission_sample_group_part (
    sample_group_id uuid NOT NULL REFERENCES commission_sample_group(id) ON DELETE CASCADE,
    project_structure_part_id uuid NOT NULL REFERENCES project_structure_part(id),
    part_name_snapshot varchar(240) NOT NULL,
    created_by uuid NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (sample_group_id, project_structure_part_id)
);
CREATE INDEX commission_sample_group_part_project_v26_ix
    ON commission_sample_group_part(project_structure_part_id, sample_group_id);

CREATE TABLE commission_test_termination (
    id uuid PRIMARY KEY,
    commission_id uuid NOT NULL UNIQUE REFERENCES commission(id),
    reason_code varchar(80) NOT NULL,
    comment varchar(1000) NOT NULL,
    cancelled_task_count integer NOT NULL CHECK (cancelled_task_count >= 0),
    initiated_by uuid NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE commission_electronic_document (
    id uuid PRIMARY KEY,
    commission_id uuid NOT NULL REFERENCES commission(id),
    revision integer NOT NULL CHECK (revision > 0),
    template_version_id uuid NOT NULL REFERENCES configuration_version(id),
    file_id uuid NOT NULL REFERENCES stored_file(id),
    job_id uuid NOT NULL UNIQUE REFERENCES background_job(id),
    created_by uuid NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (commission_id, revision)
);
CREATE INDEX commission_electronic_document_latest_v26_ix
    ON commission_electronic_document(commission_id, revision DESC);

CREATE TABLE report_print_authorization (
    id uuid PRIMARY KEY,
    report_id uuid NOT NULL REFERENCES report(id),
    report_version_id uuid NOT NULL REFERENCES report_version(id),
    file_id uuid NOT NULL REFERENCES stored_file(id),
    token_hash char(64) NOT NULL UNIQUE,
    authorized_by uuid NOT NULL REFERENCES app_user(id),
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (expires_at > created_at),
    CHECK (consumed_at IS NULL OR consumed_at >= created_at)
);
CREATE INDEX report_print_authorization_limit_v26_ix
    ON report_print_authorization(report_id,report_version_id,created_at DESC);
CREATE INDEX report_print_authorization_expiry_v26_ix
    ON report_print_authorization(expires_at) WHERE consumed_at IS NULL;

ALTER TABLE background_job
    ADD COLUMN result_file_id uuid REFERENCES stored_file(id),
    ADD COLUMN created_by uuid REFERENCES app_user(id),
    ADD COLUMN resource_type varchar(60),
    ADD COLUMN resource_id uuid,
    ADD COLUMN version bigint NOT NULL DEFAULT 0;

CREATE INDEX background_job_creator_v26_ix
    ON background_job(created_by, created_at DESC, id DESC)
    WHERE created_by IS NOT NULL;
CREATE INDEX background_job_resource_v26_ix
    ON background_job(resource_type, resource_id, created_at DESC)
    WHERE resource_type IS NOT NULL AND resource_id IS NOT NULL;
