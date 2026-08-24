CREATE TABLE quality_summary_document (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    document_type varchar(20) NOT NULL CHECK (document_type IN ('STAGE','COMPREHENSIVE')),
    number varchar(80) NOT NULL,
    project_id uuid NOT NULL REFERENCES project(id),
    customer_id uuid NOT NULL REFERENCES customer(id),
    project_number_snapshot varchar(64) NOT NULL,
    project_name_snapshot varchar(240) NOT NULL,
    customer_name_snapshot varchar(240) NOT NULL,
    section_name varchar(240) NOT NULL,
    period_start date,
    period_end date,
    report_on date NOT NULL,
    conclusion varchar(5000),
    state varchar(20) NOT NULL DEFAULT 'DRAFT'
        CHECK (state IN ('DRAFT','PUBLISHED','RETIRED')),
    version bigint NOT NULL DEFAULT 0,
    created_by uuid NOT NULL REFERENCES app_user(id),
    published_by uuid REFERENCES app_user(id),
    published_at timestamptz,
    retired_by uuid REFERENCES app_user(id),
    retired_at timestamptz,
    retirement_reason varchar(1000),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, document_type, number),
    CHECK (period_end IS NULL OR period_start IS NOT NULL),
    CHECK (period_start IS NULL OR period_end IS NULL OR period_end >= period_start),
    CHECK ((document_type='STAGE' AND period_start IS NOT NULL AND period_end IS NOT NULL
            AND report_on=period_end)
        OR (document_type='COMPREHENSIVE' AND period_start IS NULL AND period_end IS NULL)),
    CHECK ((published_at IS NULL)=(published_by IS NULL)),
    CHECK (state<>'DRAFT' OR published_at IS NULL),
    CHECK (state<>'PUBLISHED' OR published_at IS NOT NULL),
    CHECK ((state='RETIRED' AND retired_at IS NOT NULL AND retired_by IS NOT NULL
            AND retirement_reason IS NOT NULL AND btrim(retirement_reason)<>'')
        OR (state<>'RETIRED' AND retired_at IS NULL AND retired_by IS NULL AND retirement_reason IS NULL))
);

CREATE TABLE quality_summary_content (
    document_id uuid NOT NULL REFERENCES quality_summary_document(id) ON DELETE CASCADE,
    content_code varchar(40) NOT NULL CHECK (content_code IN (
        'FOUNDATION_ENGINEERING','EARTHWORK','MASONRY_ENGINEERING','CONCRETE_ENGINEERING',
        'METAL_STRUCTURE','MECHANICAL_ELECTRICAL','AUTOMATION','BRIDGE_ENGINEERING',
        'ROAD_ENGINEERING','BUILDING_ENGINEERING','TEMPORARY_ENGINEERING')),
    sort_order integer NOT NULL DEFAULT 0,
    PRIMARY KEY (document_id, content_code)
);

CREATE TABLE quality_summary_attachment (
    document_id uuid NOT NULL REFERENCES quality_summary_document(id) ON DELETE CASCADE,
    file_id uuid NOT NULL REFERENCES stored_file(id),
    sort_order integer NOT NULL DEFAULT 0,
    attached_by uuid NOT NULL REFERENCES app_user(id),
    attached_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (document_id, file_id)
);

CREATE INDEX quality_summary_org_list_ix
    ON quality_summary_document(organization_id, document_type, updated_at DESC, id DESC);
CREATE INDEX quality_summary_project_ix
    ON quality_summary_document(project_id, report_on DESC, id DESC);
CREATE INDEX quality_summary_customer_ix
    ON quality_summary_document(customer_id, report_on DESC, id DESC);
CREATE INDEX quality_summary_content_lookup_ix
    ON quality_summary_content(content_code, document_id);

CREATE OR REPLACE FUNCTION protect_quality_summary_child() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent_state varchar(20);
BEGIN
    SELECT state INTO parent_state FROM quality_summary_document
    WHERE id=COALESCE(NEW.document_id,OLD.document_id);
    IF parent_state IS DISTINCT FROM 'DRAFT' THEN
        RAISE EXCEPTION 'published quality summary content is immutable';
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER quality_summary_content_immutable
BEFORE INSERT OR UPDATE OR DELETE ON quality_summary_content
FOR EACH ROW EXECUTE FUNCTION protect_quality_summary_child();
CREATE TRIGGER quality_summary_attachment_immutable
BEFORE INSERT OR UPDATE OR DELETE ON quality_summary_attachment
FOR EACH ROW EXECUTE FUNCTION protect_quality_summary_child();

CREATE TABLE concrete_strength_assessment (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    project_id uuid NOT NULL REFERENCES project(id),
    project_number_snapshot varchar(64) NOT NULL,
    project_name_snapshot varchar(240) NOT NULL,
    structure_part_id uuid REFERENCES project_structure_part(id),
    structure_part_snapshot varchar(240) NOT NULL,
    detection_project_revision_id uuid NOT NULL REFERENCES detection_project_revision(id),
    detection_project_snapshot varchar(240) NOT NULL,
    parameter_id uuid NOT NULL REFERENCES test_parameter(id),
    parameter_snapshot varchar(240) NOT NULL,
    standard_id uuid NOT NULL REFERENCES detection_standard(id),
    standard_code_snapshot varchar(240) NOT NULL,
    standard_name_snapshot varchar(500) NOT NULL,
    design_requirement varchar(80) NOT NULL,
    period_start date NOT NULL,
    period_end date NOT NULL,
    concrete_quantity numeric(20,6) NOT NULL CHECK (concrete_quantity > 0),
    standard_deviation_average_level numeric(20,6)
        CHECK (standard_deviation_average_level IS NULL OR standard_deviation_average_level >= 0),
    specimen_group_count integer CHECK (specimen_group_count IS NULL OR specimen_group_count > 0),
    average_strength numeric(20,6),
    standard_deviation numeric(20,6),
    minimum_strength numeric(20,6),
    acceptance_coefficient numeric(20,6),
    acceptance_constant numeric(20,6),
    statistics_text varchar(5000),
    standard_text varchar(5000),
    calculation_text varchar(5000),
    conclusion varchar(2000),
    remark varchar(2000),
    rule_readiness varchar(24) NOT NULL DEFAULT 'FORMAL_RULE_MISSING'
        CHECK (rule_readiness IN ('FORMAL_RULE_MISSING','READY')),
    state varchar(24) NOT NULL DEFAULT 'DRAFT'
        CHECK (state IN ('DRAFT','CALCULATED','IN_REVIEW','REVIEWED','APPROVED','RETIRED')),
    version bigint NOT NULL DEFAULT 0,
    created_by uuid NOT NULL REFERENCES app_user(id),
    tested_by uuid REFERENCES app_user(id),
    reviewed_by uuid REFERENCES app_user(id),
    approved_by uuid REFERENCES app_user(id),
    calculated_at timestamptz,
    reviewed_at timestamptz,
    approved_at timestamptz,
    retired_by uuid REFERENCES app_user(id),
    retired_at timestamptz,
    retirement_reason varchar(1000),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (period_end >= period_start),
    CHECK ((state='DRAFT' AND calculated_at IS NULL)
        OR state='RETIRED'
        OR (calculated_at IS NOT NULL AND tested_by IS NOT NULL)),
    CHECK ((reviewed_at IS NULL)=(reviewed_by IS NULL)),
    CHECK (state NOT IN ('REVIEWED','APPROVED') OR reviewed_at IS NOT NULL),
    CHECK (state NOT IN ('DRAFT','CALCULATED','IN_REVIEW') OR reviewed_at IS NULL),
    CHECK ((approved_at IS NULL)=(approved_by IS NULL)),
    CHECK (state<>'APPROVED' OR (approved_at IS NOT NULL AND rule_readiness='READY'
            AND conclusion IS NOT NULL)),
    CHECK ((state='RETIRED' AND retired_by IS NOT NULL AND retired_at IS NOT NULL AND retirement_reason IS NOT NULL
            AND btrim(retirement_reason)<>'')
        OR (state<>'RETIRED' AND retired_by IS NULL AND retired_at IS NULL AND retirement_reason IS NULL))
);

CREATE TABLE concrete_strength_result_snapshot (
    assessment_id uuid NOT NULL REFERENCES concrete_strength_assessment(id) ON DELETE CASCADE,
    test_record_id uuid NOT NULL REFERENCES test_record(id),
    report_number varchar(80),
    construction_on date,
    strength_value numeric(20,6) NOT NULL CHECK (strength_value > 0),
    sort_order integer NOT NULL,
    PRIMARY KEY (assessment_id, test_record_id),
    UNIQUE (assessment_id, sort_order)
);

CREATE INDEX concrete_strength_org_list_ix
    ON concrete_strength_assessment(organization_id, updated_at DESC, id DESC);
CREATE INDEX concrete_strength_project_period_ix
    ON concrete_strength_assessment(project_id, period_start, period_end, id);
CREATE INDEX concrete_strength_parameter_ix
    ON concrete_strength_assessment(parameter_id, period_end DESC, id);
CREATE INDEX concrete_strength_snapshot_record_ix
    ON concrete_strength_result_snapshot(test_record_id, assessment_id);

CREATE OR REPLACE FUNCTION protect_concrete_strength_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent_state varchar(24);
BEGIN
    SELECT state INTO parent_state FROM concrete_strength_assessment
    WHERE id=COALESCE(NEW.assessment_id,OLD.assessment_id);
    IF parent_state IS DISTINCT FROM 'DRAFT' THEN
        RAISE EXCEPTION 'calculated concrete strength result snapshot is immutable';
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER concrete_strength_snapshot_immutable
BEFORE INSERT OR UPDATE OR DELETE ON concrete_strength_result_snapshot
FOR EACH ROW EXECUTE FUNCTION protect_concrete_strength_snapshot();

COMMENT ON COLUMN concrete_strength_assessment.rule_readiness IS
    'READY only after an approved formal assessment rule is installed from controlled source material; V30 intentionally seeds none.';
