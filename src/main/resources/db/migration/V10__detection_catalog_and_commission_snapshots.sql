CREATE TABLE detection_catalog_node (
    id uuid PRIMARY KEY,
    parent_id uuid REFERENCES detection_catalog_node(id),
    node_type varchar(16) NOT NULL CHECK (node_type IN ('DOMAIN','CATEGORY','PROJECT')),
    source_system varchar(40),
    source_key varchar(160),
    source_record_id varchar(80),
    name varchar(240) NOT NULL,
    symbol varchar(80),
    sort_order integer NOT NULL DEFAULT 0,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE','HISTORICAL')),
    legacy boolean NOT NULL DEFAULT false,
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (source_system, source_key),
    CHECK (parent_id IS NOT NULL OR node_type='DOMAIN')
);
CREATE INDEX detection_catalog_parent_sort_ix ON detection_catalog_node(parent_id, sort_order, name);
CREATE INDEX detection_catalog_name_ix ON detection_catalog_node(lower(name));

CREATE TABLE reference_import_manifest (
    source_system varchar(40) NOT NULL,
    manifest_sha256 char(64) NOT NULL,
    details_sha256 char(64) NOT NULL,
    source_captured_at timestamptz NOT NULL,
    imported_at timestamptz NOT NULL DEFAULT now(),
    catalog_node_count integer NOT NULL,
    project_count integer NOT NULL,
    parameter_count integer NOT NULL,
    source_error_count integer NOT NULL,
    PRIMARY KEY (source_system, manifest_sha256)
);

CREATE TABLE detection_project_revision (
    id uuid PRIMARY KEY,
    project_node_id uuid NOT NULL REFERENCES detection_catalog_node(id),
    revision_number integer NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('DRAFT','PUBLISHED','RETIRED')),
    conclusion varchar(1000),
    default_note varchar(2000),
    source_manifest_sha256 char(64),
    source_captured_at timestamptz,
    lock_version bigint NOT NULL DEFAULT 0,
    published_at timestamptz,
    created_by uuid REFERENCES app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (project_node_id, revision_number)
);
CREATE UNIQUE INDEX detection_project_one_published_ix
    ON detection_project_revision(project_node_id) WHERE status='PUBLISHED';
CREATE UNIQUE INDEX detection_project_one_draft_ix
    ON detection_project_revision(project_node_id) WHERE status='DRAFT';

ALTER TABLE test_parameter DROP CONSTRAINT test_parameter_value_type_check;
ALTER TABLE test_parameter ADD CONSTRAINT test_parameter_value_type_check CHECK (
    value_type IN ('UNSPECIFIED','NUMBER','TEXT','DATE','CHOICE','TABLE','REPEATING_GROUP','ATTACHMENT')
);
ALTER TABLE test_parameter
    ADD COLUMN project_revision_id uuid REFERENCES detection_project_revision(id),
    ADD COLUMN source_system varchar(40),
    ADD COLUMN source_key varchar(160),
    ADD COLUMN source_record_id varchar(80),
    ADD COLUMN source_occurrence integer,
    ADD COLUMN sort_order integer NOT NULL DEFAULT 0,
    ADD COLUMN conclusion varchar(1000),
    ADD COLUMN default_note varchar(2000),
    ADD COLUMN pricing_method varchar(80),
    ADD COLUMN price numeric(20,6),
    ADD COLUMN sample_name varchar(240),
    ADD COLUMN promised_days integer,
    ADD COLUMN sample_description varchar(1000),
    ADD COLUMN sample_quantity numeric(20,6),
    ADD COLUMN post_test_treatment varchar(500),
    ADD COLUMN retention_days integer,
    ADD COLUMN retention_quantity numeric(20,6),
    ADD COLUMN retention_nature varchar(120),
    ADD COLUMN default_commissioned boolean NOT NULL DEFAULT false,
    ADD COLUMN required boolean NOT NULL DEFAULT false,
    ADD COLUMN enabled boolean NOT NULL DEFAULT true,
    ADD COLUMN remark varchar(2000);
CREATE UNIQUE INDEX test_parameter_source_key_ix ON test_parameter(source_system, source_key)
    WHERE source_key IS NOT NULL;
CREATE INDEX test_parameter_project_sort_ix ON test_parameter(project_revision_id, sort_order, name);

CREATE TABLE detection_standard (
    id uuid PRIMARY KEY,
    source_system varchar(40),
    source_key varchar(500),
    code varchar(240) NOT NULL,
    name varchar(500) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE','HISTORICAL')),
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (source_system, source_key)
);

CREATE TABLE test_parameter_standard (
    parameter_id uuid NOT NULL REFERENCES test_parameter(id) ON DELETE CASCADE,
    standard_id uuid NOT NULL REFERENCES detection_standard(id),
    sort_order integer NOT NULL DEFAULT 0,
    PRIMARY KEY (parameter_id, standard_id)
);

CREATE TABLE detection_project_configuration (
    project_revision_id uuid NOT NULL REFERENCES detection_project_revision(id) ON DELETE CASCADE,
    configuration_id uuid NOT NULL REFERENCES configuration_version(id),
    role varchar(32) NOT NULL CHECK (role IN ('REPORT_TEMPLATE','COMMISSION_TEMPLATE','TASK_TEMPLATE','NUMBERING_RULE','TEMPLATE_NUMBERING')),
    PRIMARY KEY (project_revision_id, configuration_id, role)
);

CREATE TABLE detection_parameter_group (
    id uuid PRIMARY KEY,
    project_revision_id uuid NOT NULL REFERENCES detection_project_revision(id) ON DELETE CASCADE,
    code varchar(80) NOT NULL,
    name varchar(240) NOT NULL,
    sort_order integer NOT NULL DEFAULT 0,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE')),
    lock_version bigint NOT NULL DEFAULT 0,
    UNIQUE (project_revision_id, code)
);

CREATE TABLE detection_parameter_group_item (
    group_id uuid NOT NULL REFERENCES detection_parameter_group(id) ON DELETE CASCADE,
    parameter_id uuid NOT NULL REFERENCES test_parameter(id),
    sort_order integer NOT NULL DEFAULT 0,
    PRIMARY KEY (group_id, parameter_id)
);

CREATE TABLE detection_sample_marker (
    id uuid PRIMARY KEY,
    project_revision_id uuid NOT NULL REFERENCES detection_project_revision(id) ON DELETE CASCADE,
    code varchar(80) NOT NULL,
    name varchar(240) NOT NULL,
    content_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE')),
    lock_version bigint NOT NULL DEFAULT 0,
    UNIQUE (project_revision_id, code)
);

ALTER TABLE commission ADD COLUMN selection_frozen_at timestamptz;

CREATE TABLE commission_detection_selection (
    id uuid PRIMARY KEY,
    commission_id uuid NOT NULL REFERENCES commission(id) ON DELETE CASCADE,
    project_revision_id uuid NOT NULL REFERENCES detection_project_revision(id),
    project_name_snapshot varchar(240) NOT NULL,
    project_symbol_snapshot varchar(80),
    revision_number_snapshot integer NOT NULL,
    sort_order integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (commission_id, project_revision_id)
);

CREATE TABLE commission_sample_group (
    id uuid PRIMARY KEY,
    commission_id uuid NOT NULL REFERENCES commission(id) ON DELETE CASCADE,
    client_key varchar(80) NOT NULL,
    name varchar(240) NOT NULL,
    description varchar(1000),
    quantity numeric(20,6) NOT NULL CHECK (quantity > 0),
    unit varchar(40) NOT NULL,
    sort_order integer NOT NULL DEFAULT 0,
    snapshot_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE (commission_id, client_key)
);

CREATE TABLE commission_parameter_selection (
    id uuid PRIMARY KEY,
    detection_selection_id uuid NOT NULL REFERENCES commission_detection_selection(id) ON DELETE CASCADE,
    parameter_id uuid NOT NULL REFERENCES test_parameter(id),
    sample_group_id uuid REFERENCES commission_sample_group(id),
    parameter_code_snapshot varchar(80) NOT NULL,
    parameter_name_snapshot varchar(240) NOT NULL,
    unit_snapshot varchar(40),
    required_snapshot boolean NOT NULL,
    default_snapshot boolean NOT NULL,
    sort_order integer NOT NULL DEFAULT 0,
    snapshot_json jsonb NOT NULL,
    UNIQUE NULLS NOT DISTINCT (detection_selection_id, parameter_id, sample_group_id)
);
CREATE INDEX commission_parameter_selection_parameter_ix
    ON commission_parameter_selection(parameter_id, detection_selection_id);

ALTER TABLE sample ADD COLUMN sample_group_id uuid REFERENCES commission_sample_group(id);
ALTER TABLE test_task ADD COLUMN selection_id uuid REFERENCES commission_parameter_selection(id);

-- Preserve the existing flat parameter and all current business records without exposing it in the new default tree.
INSERT INTO detection_catalog_node(id, node_type, source_system, source_key, name, sort_order, status, legacy)
VALUES ('00000000-0000-0000-0000-00000000d001', 'DOMAIN', 'lims', 'legacy-domain', '本系统既有数据', 999999, 'HISTORICAL', true);
INSERT INTO detection_catalog_node(id, parent_id, node_type, source_system, source_key, name, sort_order, status, legacy)
VALUES ('00000000-0000-0000-0000-00000000d002', '00000000-0000-0000-0000-00000000d001',
        'CATEGORY', 'lims', 'legacy-category', '历史参数', 1, 'HISTORICAL', true);
INSERT INTO detection_catalog_node(id, parent_id, node_type, source_system, source_key, name, sort_order, status, legacy)
VALUES ('00000000-0000-0000-0000-00000000d003', '00000000-0000-0000-0000-00000000d002',
        'PROJECT', 'lims', 'legacy-project', '既有平铺参数', 1, 'HISTORICAL', true);
INSERT INTO detection_project_revision(id, project_node_id, revision_number, status, published_at, source_manifest_sha256)
VALUES ('00000000-0000-0000-0000-00000000d004', '00000000-0000-0000-0000-00000000d003',
        1, 'PUBLISHED', now(), repeat('0', 64));

UPDATE test_parameter
SET project_revision_id='00000000-0000-0000-0000-00000000d004',
    source_system=COALESCE(source_system, 'lims'),
    source_key=COALESCE(source_key, 'legacy:' || id::text),
    source_occurrence=COALESCE(source_occurrence, 1),
    sort_order=COALESCE(sort_order, 1)
WHERE project_revision_id IS NULL;

UPDATE organization
SET name='澜川智检科技有限公司', updated_at=now(), version=version+1
WHERE name='澜川智检科技有限公司';
