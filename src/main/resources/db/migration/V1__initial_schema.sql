CREATE TABLE organization (
    id uuid PRIMARY KEY,
    code varchar(40) NOT NULL UNIQUE,
    name varchar(200) NOT NULL,
    kind varchar(24) NOT NULL DEFAULT 'INSTITUTION',
    active boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE department (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    parent_id uuid REFERENCES department(id),
    code varchar(40) NOT NULL,
    name varchar(160) NOT NULL,
    active boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, code)
);

CREATE TABLE person (
    id uuid PRIMARY KEY,
    department_id uuid REFERENCES department(id),
    employee_number varchar(40) NOT NULL UNIQUE,
    name varchar(120) NOT NULL,
    title varchar(120),
    phone varchar(40),
    active boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE person_qualification (
    id uuid PRIMARY KEY,
    person_id uuid NOT NULL REFERENCES person(id),
    qualification_code varchar(80) NOT NULL,
    certificate_number varchar(100) NOT NULL,
    valid_from date NOT NULL,
    valid_until date NOT NULL,
    status varchar(20) NOT NULL CHECK (status IN ('DRAFT','VALID','SUSPENDED','EXPIRED','REVOKED')),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (person_id, qualification_code, certificate_number),
    CHECK (valid_until >= valid_from)
);

CREATE TABLE app_user (
    id uuid PRIMARY KEY,
    person_id uuid REFERENCES person(id),
    organization_id uuid NOT NULL REFERENCES organization(id),
    department_id uuid REFERENCES department(id),
    username varchar(80) NOT NULL UNIQUE,
    password_hash varchar(200) NOT NULL,
    display_name varchar(120) NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    must_change_password boolean NOT NULL DEFAULT true,
    failed_login_count integer NOT NULL DEFAULT 0,
    locked_until timestamptz,
    password_verified_at timestamptz,
    session_version bigint NOT NULL DEFAULT 0,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    last_login_at timestamptz
);

CREATE TABLE app_role (
    id uuid PRIMARY KEY,
    code varchar(60) NOT NULL UNIQUE,
    name varchar(120) NOT NULL,
    data_scope varchar(24) NOT NULL CHECK (data_scope IN ('ALL','ORGANIZATION','DEPARTMENT','PROJECT','SELF')),
    system_role boolean NOT NULL DEFAULT false,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE permission (
    code varchar(100) PRIMARY KEY,
    name varchar(160) NOT NULL,
    category varchar(40) NOT NULL
);

CREATE TABLE user_role (
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    role_id uuid NOT NULL REFERENCES app_role(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE role_permission (
    role_id uuid NOT NULL REFERENCES app_role(id) ON DELETE CASCADE,
    permission_code varchar(100) NOT NULL REFERENCES permission(code) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_code)
);

CREATE TABLE login_attempt (
    username varchar(80) NOT NULL,
    remote_address varchar(64) NOT NULL,
    window_started_at timestamptz NOT NULL,
    failure_count integer NOT NULL DEFAULT 0,
    locked_until timestamptz,
    PRIMARY KEY (username, remote_address)
);

CREATE TABLE spring_session (
    primary_id char(36) NOT NULL,
    session_id char(36) NOT NULL,
    creation_time bigint NOT NULL,
    last_access_time bigint NOT NULL,
    max_inactive_interval integer NOT NULL,
    expiry_time bigint NOT NULL,
    principal_name varchar(100),
    CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);
CREATE UNIQUE INDEX spring_session_ix1 ON spring_session (session_id);
CREATE INDEX spring_session_ix2 ON spring_session (expiry_time);
CREATE INDEX spring_session_ix3 ON spring_session (principal_name);

CREATE TABLE spring_session_attributes (
    session_primary_id char(36) NOT NULL,
    attribute_name varchar(200) NOT NULL,
    attribute_bytes bytea NOT NULL,
    CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id)
        REFERENCES spring_session(primary_id) ON DELETE CASCADE
);

CREATE TABLE customer (
    id uuid PRIMARY KEY,
    code varchar(40) NOT NULL UNIQUE,
    name varchar(200) NOT NULL,
    unified_credit_code varchar(32),
    contact_name varchar(120),
    contact_phone varchar(40),
    active boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE project (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    customer_id uuid REFERENCES customer(id),
    number varchar(64) NOT NULL UNIQUE,
    name varchar(240) NOT NULL,
    location varchar(240),
    responsible_person varchar(120),
    status varchar(20) NOT NULL CHECK (status IN ('ACTIVE','SUSPENDED','CLOSED')),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE commission (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    project_id uuid NOT NULL REFERENCES project(id),
    customer_id uuid NOT NULL REFERENCES customer(id),
    number varchar(64) NOT NULL UNIQUE,
    contact_name varchar(120) NOT NULL,
    contact_phone varchar(40),
    commissioned_on date NOT NULL,
    due_on date,
    state varchar(32) NOT NULL CHECK (state IN ('DRAFT','PRE_ACCEPTED','ACCEPTED','SAMPLING','ASSIGNED','TESTING','REPORTING','ISSUED','ARCHIVED','REJECTED','CANCELLED')),
    version bigint NOT NULL DEFAULT 0,
    created_by uuid NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (due_on IS NULL OR due_on >= commissioned_on)
);
CREATE INDEX commission_state_due_ix ON commission (state, due_on);
CREATE INDEX commission_org_date_ix ON commission (organization_id, commissioned_on DESC);

CREATE TABLE sample (
    id uuid PRIMARY KEY,
    commission_id uuid NOT NULL REFERENCES commission(id),
    parent_id uuid REFERENCES sample(id),
    number varchar(80) NOT NULL UNIQUE,
    name varchar(200) NOT NULL,
    specification varchar(200),
    received_quantity numeric(20,6) NOT NULL CHECK (received_quantity > 0),
    available_quantity numeric(20,6) NOT NULL CHECK (available_quantity >= 0),
    unit varchar(24) NOT NULL,
    received_at timestamptz,
    state varchar(24) NOT NULL CHECK (state IN ('EXPECTED','RECEIVED','HANDED_OVER','IN_TEST','RETAINED','RETURNED','DISPOSED')),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (available_quantity <= received_quantity)
);
CREATE INDEX sample_commission_ix ON sample (commission_id);

CREATE TABLE sample_movement (
    id uuid PRIMARY KEY,
    sample_id uuid NOT NULL REFERENCES sample(id),
    movement_type varchar(24) NOT NULL CHECK (movement_type IN ('RECEIVE','SPLIT','HANDOVER','CONSUME','RETAIN','RETURN','DISPOSE','CORRECTION')),
    quantity numeric(20,6) NOT NULL CHECK (quantity > 0),
    from_location varchar(160),
    to_location varchar(160),
    reason varchar(500),
    occurred_at timestamptz NOT NULL DEFAULT now(),
    actor_id uuid NOT NULL REFERENCES app_user(id)
);

CREATE TABLE test_parameter (
    id uuid PRIMARY KEY,
    code varchar(80) NOT NULL,
    name varchar(200) NOT NULL,
    version_number integer NOT NULL,
    value_type varchar(20) NOT NULL CHECK (value_type IN ('NUMBER','TEXT','DATE','CHOICE','TABLE','REPEATING_GROUP','ATTACHMENT')),
    definition_json jsonb NOT NULL,
    formula varchar(1000),
    unit varchar(40),
    status varchar(20) NOT NULL CHECK (status IN ('DRAFT','PUBLISHED','RETIRED')),
    published_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (code, version_number)
);

CREATE TABLE test_task (
    id uuid PRIMARY KEY,
    commission_id uuid NOT NULL REFERENCES commission(id),
    sample_id uuid NOT NULL REFERENCES sample(id),
    parameter_id uuid NOT NULL REFERENCES test_parameter(id),
    number varchar(80) NOT NULL UNIQUE,
    assigned_to uuid REFERENCES person(id),
    assigned_by uuid REFERENCES app_user(id),
    due_at timestamptz,
    state varchar(24) NOT NULL CHECK (state IN ('UNASSIGNED','ASSIGNED','ACCEPTED','IN_PROGRESS','SUBMITTED','RETURNED','COMPLETED','CANCELLED')),
    rejection_reason varchar(500),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX test_task_state_assignee_ix ON test_task (state, assigned_to);

CREATE TABLE equipment (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    code varchar(64) NOT NULL UNIQUE,
    name varchar(200) NOT NULL,
    model varchar(120),
    serial_number varchar(120),
    location varchar(160),
    custodian_id uuid REFERENCES person(id),
    state varchar(24) NOT NULL CHECK (state IN ('AVAILABLE','IN_USE','BORROWED','UNDER_INSPECTION','UNDER_CALIBRATION','MAINTENANCE','OUT_OF_SERVICE','SCRAPPED')),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE equipment_certificate (
    id uuid PRIMARY KEY,
    equipment_id uuid NOT NULL REFERENCES equipment(id),
    certificate_type varchar(24) NOT NULL CHECK (certificate_type IN ('VERIFICATION','CALIBRATION')),
    certificate_number varchar(120) NOT NULL,
    issued_on date NOT NULL,
    valid_until date NOT NULL,
    result varchar(20) NOT NULL CHECK (result IN ('PASS','LIMITED','FAIL')),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (equipment_id, certificate_type, certificate_number),
    CHECK (valid_until >= issued_on)
);

CREATE TABLE equipment_event (
    id uuid PRIMARY KEY,
    equipment_id uuid NOT NULL REFERENCES equipment(id),
    task_id uuid REFERENCES test_task(id),
    event_type varchar(28) NOT NULL CHECK (event_type IN ('STOCK_IN','STOCK_OUT','BORROW','RETURN','USE_START','USE_END','SEND_INSPECTION','SEND_CALIBRATION','VERIFIED','CALIBRATED','MAINTENANCE','SCRAP')),
    occurred_at timestamptz NOT NULL DEFAULT now(),
    actor_id uuid NOT NULL REFERENCES app_user(id),
    details jsonb NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE test_record (
    id uuid PRIMARY KEY,
    task_id uuid NOT NULL REFERENCES test_task(id),
    revision integer NOT NULL DEFAULT 0,
    values_json jsonb NOT NULL,
    result varchar(24) NOT NULL CHECK (result IN ('PENDING','CONFORMING','NONCONFORMING','INVALID')),
    state varchar(24) NOT NULL CHECK (state IN ('DRAFT','SUBMITTED','RETURNED','CONFIRMED','SUPERSEDED')),
    recorded_by uuid NOT NULL REFERENCES person(id),
    equipment_id uuid REFERENCES equipment(id),
    version bigint NOT NULL DEFAULT 0,
    submitted_at timestamptz,
    confirmed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (task_id, revision)
);

CREATE TABLE exception_approval (
    id uuid PRIMARY KEY,
    resource_type varchar(40) NOT NULL,
    resource_id uuid NOT NULL,
    blocker_code varchar(80) NOT NULL,
    reason varchar(1000) NOT NULL,
    expires_at timestamptz NOT NULL,
    requested_by uuid NOT NULL REFERENCES app_user(id),
    approved_by uuid REFERENCES app_user(id),
    state varchar(20) NOT NULL CHECK (state IN ('REQUESTED','APPROVED','REJECTED','EXPIRED','REVOKED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    decided_at timestamptz
);

CREATE TABLE nonconformance (
    id uuid PRIMARY KEY,
    number varchar(80) NOT NULL UNIQUE,
    source_type varchar(32) NOT NULL CHECK (source_type IN ('SAMPLE','TEST_RECORD','REPORT','EQUIPMENT','PROCESS')),
    source_id uuid NOT NULL,
    commission_id uuid REFERENCES commission(id),
    severity varchar(20) NOT NULL CHECK (severity IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    description varchar(2000) NOT NULL,
    state varchar(28) NOT NULL CHECK (state IN ('OPEN','QUALITY_CONFIRMED','DISPOSITION_IN_PROGRESS','PENDING_VERIFICATION','CLOSED','REJECTED')),
    owner_id uuid REFERENCES person(id),
    opened_by uuid NOT NULL REFERENCES app_user(id),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    closed_at timestamptz
);

CREATE TABLE corrective_action (
    id uuid PRIMARY KEY,
    nonconformance_id uuid NOT NULL REFERENCES nonconformance(id),
    action_type varchar(20) NOT NULL CHECK (action_type IN ('CORRECTION','CORRECTIVE','PREVENTIVE')),
    description varchar(2000) NOT NULL,
    owner_id uuid NOT NULL REFERENCES person(id),
    due_on date NOT NULL,
    state varchar(24) NOT NULL CHECK (state IN ('PLANNED','IN_PROGRESS','COMPLETED','VERIFIED','REJECTED')),
    evidence_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    verified_by uuid REFERENCES person(id),
    verified_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE report (
    id uuid PRIMARY KEY,
    commission_id uuid NOT NULL REFERENCES commission(id),
    number varchar(80) NOT NULL UNIQUE,
    title varchar(240) NOT NULL,
    current_revision integer NOT NULL DEFAULT 0,
    state varchar(28) NOT NULL CHECK (state IN ('DRAFT','IN_REVIEW','RETURNED','APPROVED','ISSUED','DISTRIBUTED','ARCHIVED','VOID')),
    version bigint NOT NULL DEFAULT 0,
    created_by uuid NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE report_version (
    id uuid PRIMARY KEY,
    report_id uuid NOT NULL REFERENCES report(id),
    revision integer NOT NULL,
    revision_label varchar(16) NOT NULL,
    content_json jsonb NOT NULL,
    pdf_file_id uuid,
    content_sha256 char(64),
    signed_at timestamptz,
    signed_by uuid REFERENCES app_user(id),
    sealed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (report_id, revision),
    UNIQUE (report_id, revision_label)
);

CREATE TABLE report_approval (
    id uuid PRIMARY KEY,
    report_version_id uuid NOT NULL REFERENCES report_version(id),
    step varchar(24) NOT NULL CHECK (step IN ('REVIEW','APPROVE','ISSUE','SEAL')),
    decision varchar(20) NOT NULL CHECK (decision IN ('PENDING','APPROVED','RETURNED','REJECTED')),
    actor_id uuid REFERENCES app_user(id),
    comment varchar(1000),
    decided_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE report_distribution (
    id uuid PRIMARY KEY,
    report_version_id uuid NOT NULL REFERENCES report_version(id),
    recipient varchar(240) NOT NULL,
    method varchar(20) NOT NULL CHECK (method IN ('IN_PERSON','INTERNAL_DOWNLOAD','CONTROLLED_COPY')),
    copy_number varchar(64),
    distributed_by uuid NOT NULL REFERENCES app_user(id),
    distributed_at timestamptz NOT NULL DEFAULT now(),
    acknowledged_by varchar(120),
    acknowledged_at timestamptz
);

CREATE TABLE archive_package (
    id uuid PRIMARY KEY,
    report_id uuid NOT NULL UNIQUE REFERENCES report(id),
    archive_number varchar(80) NOT NULL UNIQUE,
    manifest_sha256 char(64) NOT NULL,
    archived_by uuid NOT NULL REFERENCES app_user(id),
    archived_at timestamptz NOT NULL DEFAULT now(),
    retention_until date
);

CREATE TABLE stored_file (
    id uuid PRIMARY KEY,
    sha256 char(64) NOT NULL,
    size_bytes bigint NOT NULL CHECK (size_bytes > 0),
    media_type varchar(160) NOT NULL,
    original_name varchar(255) NOT NULL,
    storage_path varchar(500) NOT NULL,
    purpose varchar(40) NOT NULL,
    immutable boolean NOT NULL DEFAULT true,
    uploaded_by uuid NOT NULL REFERENCES app_user(id),
    uploaded_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (sha256, size_bytes)
);
ALTER TABLE report_version ADD CONSTRAINT report_version_pdf_fk FOREIGN KEY (pdf_file_id) REFERENCES stored_file(id);

CREATE TABLE configuration_version (
    id uuid PRIMARY KEY,
    configuration_type varchar(40) NOT NULL CHECK (configuration_type IN ('DICTIONARY','REPORT_TEMPLATE','COMMISSION_TEMPLATE','TASK_TEMPLATE','NOTIFICATION','NUMBERING_RULE','TEMPLATE_NUMBERING')),
    code varchar(80) NOT NULL,
    version_number integer NOT NULL,
    name varchar(200) NOT NULL,
    content_json jsonb NOT NULL,
    state varchar(20) NOT NULL CHECK (state IN ('DRAFT','PUBLISHED','RETIRED')),
    lock_version bigint NOT NULL DEFAULT 0,
    published_at timestamptz,
    created_by uuid REFERENCES app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (configuration_type, code, version_number)
);

CREATE TABLE notification (
    id uuid PRIMARY KEY,
    recipient_id uuid NOT NULL REFERENCES app_user(id),
    category varchar(40) NOT NULL,
    title varchar(200) NOT NULL,
    body varchar(2000) NOT NULL,
    resource_type varchar(40),
    resource_id uuid,
    read_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX notification_recipient_ix ON notification (recipient_id, read_at, created_at DESC);

CREATE TABLE catalog_entry (
    id uuid PRIMARY KEY,
    module_key varchar(80) NOT NULL,
    code varchar(80) NOT NULL,
    name varchar(240) NOT NULL,
    status varchar(40) NOT NULL DEFAULT 'ACTIVE',
    organization_id uuid REFERENCES organization(id),
    department_id uuid REFERENCES department(id),
    attributes jsonb NOT NULL DEFAULT '{}'::jsonb,
    version bigint NOT NULL DEFAULT 0,
    created_by uuid REFERENCES app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (module_key, code)
);
CREATE INDEX catalog_entry_module_name_ix ON catalog_entry (module_key, name);

CREATE TABLE number_sequence (
    rule_code varchar(80) NOT NULL,
    period_key varchar(20) NOT NULL,
    current_value bigint NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (rule_code, period_key)
);

CREATE TABLE idempotency_record (
    idempotency_key varchar(100) PRIMARY KEY,
    actor_id uuid NOT NULL REFERENCES app_user(id),
    request_fingerprint char(64) NOT NULL,
    response_status integer,
    response_body jsonb,
    state varchar(16) NOT NULL CHECK (state IN ('PROCESSING','COMPLETED','FAILED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL
);

CREATE TABLE background_job (
    id uuid PRIMARY KEY,
    job_type varchar(40) NOT NULL,
    payload jsonb NOT NULL,
    idempotency_key varchar(100) NOT NULL UNIQUE,
    state varchar(20) NOT NULL CHECK (state IN ('READY','RUNNING','RETRY','COMPLETED','FAILED')),
    attempt_count integer NOT NULL DEFAULT 0,
    available_at timestamptz NOT NULL DEFAULT now(),
    locked_by varchar(120),
    locked_at timestamptz,
    last_error varchar(2000),
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz
);
CREATE INDEX background_job_ready_ix ON background_job (state, available_at);

CREATE TABLE audit_event (
    id uuid PRIMARY KEY,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    request_id varchar(80) NOT NULL,
    actor_id uuid REFERENCES app_user(id),
    actor_name varchar(120),
    action varchar(100) NOT NULL,
    resource_type varchar(60) NOT NULL,
    resource_id varchar(100),
    organization_id uuid REFERENCES organization(id),
    before_json jsonb,
    after_json jsonb,
    reason_code varchar(80),
    comment varchar(1000),
    remote_address varchar(64),
    user_agent varchar(500)
);
CREATE INDEX audit_event_resource_ix ON audit_event (resource_type, resource_id, occurred_at DESC);
CREATE INDEX audit_event_actor_ix ON audit_event (actor_id, occurred_at DESC);

CREATE OR REPLACE FUNCTION reject_audit_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit events are immutable';
END;
$$;
CREATE TRIGGER audit_event_no_update BEFORE UPDATE OR DELETE ON audit_event
FOR EACH ROW EXECUTE FUNCTION reject_audit_mutation();

CREATE OR REPLACE FUNCTION protect_signed_report_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' AND OLD.signed_at IS NOT NULL THEN
        RAISE EXCEPTION 'signed report versions are immutable';
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.signed_at IS NOT NULL AND (
        NEW.report_id IS DISTINCT FROM OLD.report_id OR
        NEW.revision IS DISTINCT FROM OLD.revision OR
        NEW.revision_label IS DISTINCT FROM OLD.revision_label OR
        NEW.content_json IS DISTINCT FROM OLD.content_json OR
        NEW.pdf_file_id IS DISTINCT FROM OLD.pdf_file_id OR
        NEW.content_sha256 IS DISTINCT FROM OLD.content_sha256 OR
        NEW.signed_at IS DISTINCT FROM OLD.signed_at OR
        NEW.signed_by IS DISTINCT FROM OLD.signed_by
    ) THEN
        RAISE EXCEPTION 'signed report versions are immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER signed_report_version_no_mutation BEFORE UPDATE OR DELETE ON report_version
FOR EACH ROW EXECUTE FUNCTION protect_signed_report_version();
