-- Production read models for specimen due/overdue, rebound and field-test ledgers,
-- plus persistent warning handling. These are facts/projections over existing owners;
-- no ledger row is independently editable.

ALTER TABLE sample
    ADD COLUMN sampling_date date,
    ADD COLUMN molding_date date,
    ADD COLUMN planned_test_date date,
    ADD COLUMN age_days integer CHECK (age_days IS NULL OR age_days BETWEEN 1 AND 3650),
    ADD COLUMN curing_method varchar(80),
    ADD COLUMN strength_grade varchar(80),
    ADD COLUMN structure_part varchar(240),
    ADD COLUMN chip_code varchar(160),
    ADD COLUMN represented_quantity numeric(20,6)
        CHECK (represented_quantity IS NULL OR represented_quantity > 0),
    ADD COLUMN represented_unit varchar(24);

UPDATE sample sample
SET molding_date=commission.molding_date,
    planned_test_date=commission.expected_test_date,
    age_days=CASE
      WHEN commission.molding_date IS NOT NULL AND commission.expected_test_date IS NOT NULL
        AND commission.expected_test_date>commission.molding_date
      THEN commission.expected_test_date-commission.molding_date
      ELSE NULL END,
    structure_part=NULLIF(commission.project_part,'')
FROM commission
WHERE commission.id=sample.commission_id
  AND (commission.molding_date IS NOT NULL OR commission.expected_test_date IS NOT NULL
       OR NULLIF(commission.project_part,'') IS NOT NULL);

CREATE INDEX sample_planned_test_v29_ix
    ON sample(planned_test_date, state, id)
    WHERE planned_test_date IS NOT NULL AND state NOT IN ('RETURNED','DISPOSED');
CREATE INDEX sample_commission_molding_v29_ix
    ON sample(commission_id, molding_date, id);
CREATE INDEX sample_chip_code_v29_ix
    ON sample(chip_code) WHERE chip_code IS NOT NULL;

ALTER TABLE test_task
    ADD COLUMN accepted_at timestamptz,
    ADD COLUMN started_at timestamptz,
    ADD COLUMN completed_at timestamptz;

UPDATE test_task
SET accepted_at=CASE WHEN state IN ('ACCEPTED','IN_PROGRESS','SUBMITTED','RETURNED','COMPLETED')
                     THEN updated_at ELSE NULL END,
    started_at=CASE WHEN state IN ('IN_PROGRESS','SUBMITTED','RETURNED','COMPLETED')
                    THEN updated_at ELSE NULL END,
    completed_at=CASE WHEN state='COMPLETED' THEN updated_at ELSE NULL END;

ALTER TABLE test_task
    ADD CONSTRAINT test_task_execution_time_v29_ck CHECK (
      (started_at IS NULL OR accepted_at IS NULL OR started_at>=accepted_at)
      AND (completed_at IS NULL OR started_at IS NULL OR completed_at>=started_at));
CREATE INDEX test_task_field_ledger_v29_ix
    ON test_task(state, started_at DESC, id DESC);

CREATE TABLE warning_record (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    warning_type varchar(48) NOT NULL CHECK (warning_type IN (
        'EQUIPMENT_VERIFICATION_EXPIRED','EQUIPMENT_CALIBRATION_EXPIRED',
        'DETECTION_NONCONFORMING','SAMPLE_TEST_OVERDUE')),
    source_type varchar(40) NOT NULL,
    source_id uuid NOT NULL,
    project_id uuid REFERENCES project(id),
    commission_id uuid REFERENCES commission(id),
    content varchar(2000) NOT NULL,
    occurred_at timestamptz NOT NULL,
    state varchar(16) NOT NULL DEFAULT 'OPEN' CHECK (state IN ('OPEN','RESOLVED')),
    handled_by uuid REFERENCES app_user(id),
    handled_at timestamptz,
    handling_description varchar(2000),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, warning_type, source_type, source_id),
    CHECK (state<>'RESOLVED' OR
      (handled_by IS NOT NULL AND handled_at IS NOT NULL AND handling_description IS NOT NULL)),
    CHECK (handled_at IS NULL OR handled_at>=occurred_at)
);
CREATE INDEX warning_record_list_v29_ix
    ON warning_record(organization_id, state, occurred_at DESC, id DESC);
CREATE INDEX warning_record_project_v29_ix
    ON warning_record(project_id, occurred_at DESC, id DESC) WHERE project_id IS NOT NULL;
CREATE INDEX warning_record_commission_v29_ix
    ON warning_record(commission_id, occurred_at DESC, id DESC) WHERE commission_id IS NOT NULL;

CREATE TABLE warning_record_attachment (
    warning_id uuid NOT NULL REFERENCES warning_record(id) ON DELETE CASCADE,
    file_id uuid NOT NULL REFERENCES stored_file(id),
    sort_order integer NOT NULL DEFAULT 0,
    attached_by uuid NOT NULL REFERENCES app_user(id),
    attached_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (warning_id, file_id)
);

INSERT INTO permission(code,name,category) VALUES
    ('WARNING_RESOLVE','处置预警记录','QUALITY')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permission(role_id,permission_code)
SELECT role.id,'WARNING_RESOLVE' FROM app_role role
WHERE role.code IN ('SYSTEM_ADMIN','QUALITY_MANAGER')
ON CONFLICT DO NOTHING;
