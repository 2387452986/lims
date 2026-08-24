ALTER TABLE customer
    ADD COLUMN organization_id uuid REFERENCES organization(id),
    ADD COLUMN short_name varchar(120),
    ADD COLUMN unit_type varchar(32) NOT NULL DEFAULT 'OTHER'
        CHECK (unit_type IN ('OWNER','ENTRUSTING','SUPERVISION','CONSTRUCTION','SAMPLING','DESIGN','SURVEY','GENERAL_CONTRACTOR','OTHER')),
    ADD COLUMN commissioning_unit boolean NOT NULL DEFAULT false,
    ADD COLUMN landline varchar(40),
    ADD COLUMN address varchar(500),
    ADD COLUMN fax varchar(40),
    ADD COLUMN email varchar(254),
    ADD COLUMN remark varchar(2000);

UPDATE customer
SET organization_id=(SELECT id FROM organization ORDER BY created_at, id LIMIT 1),
    short_name=name,
    commissioning_unit=EXISTS(SELECT 1 FROM commission c WHERE c.customer_id=customer.id)
       OR EXISTS(SELECT 1 FROM project p WHERE p.customer_id=customer.id);

ALTER TABLE customer ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE customer ALTER COLUMN short_name SET NOT NULL;
ALTER TABLE customer DROP CONSTRAINT IF EXISTS customer_code_key;
ALTER TABLE customer ADD CONSTRAINT customer_org_code_ux UNIQUE (organization_id, code);
CREATE INDEX customer_org_updated_ix ON customer(organization_id, updated_at DESC, id DESC);
CREATE INDEX customer_org_filter_ix ON customer(organization_id, unit_type, active, commissioning_unit);

CREATE TABLE customer_settlement_contact (
    id uuid PRIMARY KEY,
    customer_id uuid NOT NULL REFERENCES customer(id) ON DELETE CASCADE,
    name varchar(120) NOT NULL,
    phone varchar(40),
    other_contact boolean NOT NULL DEFAULT false,
    primary_contact boolean NOT NULL DEFAULT false,
    active boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX customer_one_primary_settlement_ux
    ON customer_settlement_contact(customer_id) WHERE primary_contact AND active;
CREATE INDEX customer_settlement_customer_ix
    ON customer_settlement_contact(customer_id, active, updated_at DESC);

INSERT INTO customer_settlement_contact(id, customer_id, name, phone, primary_contact)
SELECT gen_random_uuid(), id, contact_name, contact_phone, true
FROM customer WHERE contact_name IS NOT NULL AND btrim(contact_name) <> '';

CREATE TABLE customer_person (
    id uuid PRIMARY KEY,
    customer_id uuid NOT NULL REFERENCES customer(id) ON DELETE CASCADE,
    name varchar(120) NOT NULL,
    gender varchar(12) NOT NULL DEFAULT 'UNKNOWN' CHECK (gender IN ('UNKNOWN','MALE','FEMALE')),
    person_type varchar(32) NOT NULL CHECK (person_type IN
        ('CONTACT','ENTRUSTER','SAMPLER','WITNESS','SETTLEMENT','LEGAL_REPRESENTATIVE','OTHER')),
    phone varchar(40),
    certificate_number varchar(120),
    remark varchar(1000),
    avatar_file_id uuid REFERENCES stored_file(id),
    active boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX customer_person_customer_ix ON customer_person(customer_id, active, updated_at DESC);

CREATE TABLE customer_account_link (
    customer_id uuid NOT NULL REFERENCES customer(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    relationship varchar(24) NOT NULL DEFAULT 'MEMBER'
        CHECK (relationship IN ('ADMIN','MEMBER','REPORT_VIEWER')),
    created_by uuid NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (customer_id, user_id)
);

ALTER TABLE project DROP CONSTRAINT IF EXISTS project_status_check;
ALTER TABLE project
    ADD CONSTRAINT project_status_check CHECK (status IN
        ('DRAFT','ACTIVE','SUSPENDED','COMPLETED','CLOSED')),
    ADD COLUMN pile_serial_number varchar(80),
    ADD COLUMN project_type varchar(80),
    ADD COLUMN project_grade varchar(80),
    ADD COLUMN regulatory_organization varchar(240),
    ADD COLUMN regulatory_project_code varchar(120),
    ADD COLUMN regulatory_project_name varchar(240),
    ADD COLUMN regulatory_linked boolean NOT NULL DEFAULT false,
    ADD COLUMN entruster_name varchar(120),
    ADD COLUMN entruster_phone varchar(40),
    ADD COLUMN settlement_contact_name varchar(120),
    ADD COLUMN settlement_contact_phone varchar(40),
    ADD COLUMN sampling_unit_name varchar(240),
    ADD COLUMN sampler_name varchar(120),
    ADD COLUMN sampler_phone varchar(40),
    ADD COLUMN sampler_certificate_number varchar(120),
    ADD COLUMN province varchar(80),
    ADD COLUMN city varchar(80),
    ADD COLUMN district varchar(80),
    ADD COLUMN detailed_address varchar(500),
    ADD COLUMN section_number varchar(120),
    ADD COLUMN region_code varchar(80),
    ADD COLUMN default_detection_category varchar(120),
    ADD COLUMN remark varchar(2000),
    ADD COLUMN owner_name varchar(240),
    ADD COLUMN owner_report_access boolean NOT NULL DEFAULT false,
    ADD COLUMN supervision_name varchar(240),
    ADD COLUMN witness_name varchar(120),
    ADD COLUMN witness_phone varchar(40),
    ADD COLUMN supervision_report_access boolean NOT NULL DEFAULT false,
    ADD COLUMN construction_name varchar(240),
    ADD COLUMN construction_report_access boolean NOT NULL DEFAULT false,
    ADD COLUMN survey_name varchar(240),
    ADD COLUMN design_name varchar(240),
    ADD COLUMN general_contractor_name varchar(240),
    ADD COLUMN customer_report_access boolean NOT NULL DEFAULT false,
    ADD COLUMN cma_seal_policy varchar(24) NOT NULL DEFAULT 'FOLLOW_PROJECT'
        CHECK (cma_seal_policy IN ('FOLLOW_PROJECT','ALWAYS','NEVER')),
    ADD COLUMN qualification_seal_policy varchar(24) NOT NULL DEFAULT 'FOLLOW_PROJECT'
        CHECK (qualification_seal_policy IN ('FOLLOW_PROJECT','ALWAYS','NEVER')),
    ADD COLUMN electronic_seal_policy varchar(24) NOT NULL DEFAULT 'FOLLOW_PROJECT'
        CHECK (electronic_seal_policy IN ('FOLLOW_PROJECT','ALWAYS','NEVER')),
    ADD COLUMN customer_print_allowed boolean NOT NULL DEFAULT false,
    ADD COLUMN customer_print_limit integer CHECK (customer_print_limit IS NULL OR customer_print_limit >= 0),
    ADD COLUMN settlement_method varchar(40),
    ADD COLUMN project_team_id uuid REFERENCES project_team(id),
    ADD COLUMN report_print_restricted boolean NOT NULL DEFAULT false,
    ADD COLUMN external_contract_number varchar(120),
    ADD COLUMN progress_percent numeric(5,2) NOT NULL DEFAULT 0
        CHECK (progress_percent >= 0 AND progress_percent <= 100),
    ADD COLUMN handled_on date NOT NULL DEFAULT CURRENT_DATE,
    ADD COLUMN latitude numeric(10,7) CHECK (latitude IS NULL OR (latitude >= -90 AND latitude <= 90)),
    ADD COLUMN longitude numeric(10,7) CHECK (longitude IS NULL OR (longitude >= -180 AND longitude <= 180));

ALTER TABLE project ADD COLUMN created_by uuid REFERENCES app_user(id);
UPDATE project SET created_by=(SELECT id FROM app_user WHERE app_user.organization_id=project.organization_id ORDER BY created_at,id LIMIT 1);

UPDATE project SET detailed_address=location WHERE detailed_address IS NULL;
ALTER TABLE project DROP CONSTRAINT IF EXISTS project_number_key;
ALTER TABLE project ADD CONSTRAINT project_org_number_ux UNIQUE (organization_id, number);
CREATE UNIQUE INDEX project_org_pile_serial_ux
    ON project(organization_id, pile_serial_number) WHERE pile_serial_number IS NOT NULL;
CREATE INDEX project_org_updated_ix ON project(organization_id, updated_at DESC, id DESC);
CREATE INDEX project_org_filter_ix
    ON project(organization_id, status, regulatory_linked, project_team_id, handled_on DESC);
CREATE INDEX project_customer_updated_ix ON project(customer_id, updated_at DESC, id DESC);
CREATE INDEX project_created_by_ix ON project(created_by, updated_at DESC, id DESC);

CREATE TABLE project_party (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    party_role varchar(32) NOT NULL CHECK (party_role IN
        ('ENTRUSTING','SAMPLING','OWNER','SUPERVISION','CONSTRUCTION','SURVEY','DESIGN','GENERAL_CONTRACTOR')),
    customer_id uuid REFERENCES customer(id),
    name_snapshot varchar(240) NOT NULL,
    contact_name varchar(120),
    contact_phone varchar(40),
    certificate_number varchar(120),
    manual_entry boolean NOT NULL DEFAULT true,
    report_access boolean NOT NULL DEFAULT false,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (project_id, party_role)
);
CREATE INDEX project_party_customer_ix ON project_party(customer_id, party_role);

INSERT INTO project_party(id, project_id, party_role, customer_id, name_snapshot, contact_name,
                          contact_phone, manual_entry, report_access)
SELECT gen_random_uuid(), p.id, 'ENTRUSTING', p.customer_id, c.name, p.responsible_person,
       c.contact_phone, false, false
FROM project p JOIN customer c ON c.id=p.customer_id;

CREATE TABLE project_detection_plan (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    detection_project_revision_id uuid REFERENCES detection_project_revision(id),
    parameter_id uuid REFERENCES test_parameter(id),
    category_name varchar(240),
    project_name varchar(240) NOT NULL,
    parameter_name varchar(240) NOT NULL,
    planned_quantity numeric(20,6) NOT NULL CHECK (planned_quantity > 0),
    unit varchar(40) NOT NULL,
    binding_parameter varchar(240),
    detection_method varchar(500),
    planned_completion_on date,
    unit_price numeric(20,2) NOT NULL DEFAULT 0 CHECK (unit_price >= 0),
    active boolean NOT NULL DEFAULT true,
    sort_order integer NOT NULL DEFAULT 0,
    version bigint NOT NULL DEFAULT 0,
    created_by uuid NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (parameter_id IS NULL OR detection_project_revision_id IS NOT NULL)
);
CREATE INDEX project_detection_plan_project_ix
    ON project_detection_plan(project_id, active, sort_order, updated_at DESC);

CREATE TABLE project_progress_mark (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    progress_percent numeric(5,2) NOT NULL CHECK (progress_percent >= 0 AND progress_percent <= 100),
    progress_label varchar(120) NOT NULL,
    occurred_on date NOT NULL,
    comment varchar(1000),
    created_by uuid NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX project_progress_mark_project_ix
    ON project_progress_mark(project_id, occurred_on DESC, created_at DESC);

CREATE TABLE project_structure_part (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    parent_id uuid,
    code varchar(80) NOT NULL,
    name varchar(240) NOT NULL,
    sort_order integer NOT NULL DEFAULT 0,
    active boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (project_id, code),
    UNIQUE (project_id, id),
    CONSTRAINT project_structure_part_parent_fk FOREIGN KEY (project_id, parent_id)
        REFERENCES project_structure_part(project_id, id)
);
CREATE INDEX project_structure_part_tree_ix
    ON project_structure_part(project_id, parent_id, sort_order, name);

ALTER TABLE commission
    ADD COLUMN precommission_number varchar(80),
    ADD COLUMN intake_source varchar(20) NOT NULL DEFAULT 'MANUAL'
        CHECK (intake_source IN ('MANUAL','SCAN','PORTAL')),
    ADD COLUMN sample_quantity numeric(20,6) CHECK (sample_quantity IS NULL OR sample_quantity > 0),
    ADD COLUMN molding_date date,
    ADD COLUMN expected_test_date date,
    ADD COLUMN sampling_status varchar(24) NOT NULL DEFAULT 'NOT_SAMPLED'
        CHECK (sampling_status IN ('NOT_SAMPLED','SAMPLED','RECEIVED','REJECTED')),
    ADD COLUMN print_status varchar(24) NOT NULL DEFAULT 'NOT_PRINTED'
        CHECK (print_status IN ('NOT_PRINTED','PRINTED')),
    ADD COLUMN submitted_at timestamptz,
    ADD COLUMN accepted_by uuid REFERENCES app_user(id),
    ADD COLUMN accepted_at timestamptz;
CREATE UNIQUE INDEX commission_org_precommission_number_ux
    ON commission(organization_id, precommission_number) WHERE precommission_number IS NOT NULL;
CREATE INDEX commission_precommission_cursor_ix
    ON commission(organization_id, submitted_at DESC, id DESC)
    WHERE precommission_number IS NOT NULL OR state='PRE_ACCEPTED';
CREATE INDEX commission_precommission_effective_cursor_ix
    ON commission(organization_id, (COALESCE(submitted_at, updated_at)) DESC, id DESC)
    WHERE state IN ('PRE_ACCEPTED','ACCEPTED','REJECTED');

CREATE TABLE commission_change_request (
    id uuid PRIMARY KEY,
    commission_id uuid NOT NULL REFERENCES commission(id),
    request_type varchar(32) NOT NULL CHECK (request_type IN
        ('CONTACT','DUE_DATE','REPORT_REQUIREMENT','SAMPLE','DETECTION_SELECTION','OTHER')),
    processing_method varchar(32) NOT NULL CHECK (processing_method IN
        ('UPDATE_CURRENT','CREATE_REVISION','VOID_AND_RECREATE','MANUAL_RECORD')),
    reason varchar(2000) NOT NULL,
    requested_contact_name varchar(120),
    requested_contact_phone varchar(40),
    requested_due_on date,
    requested_summary varchar(4000),
    attachment_file_id uuid REFERENCES stored_file(id),
    commission_version_snapshot bigint NOT NULL,
    state varchar(20) NOT NULL DEFAULT 'DRAFT'
        CHECK (state IN ('DRAFT','SUBMITTED','APPROVED','REJECTED','WITHDRAWN')),
    requested_by uuid NOT NULL REFERENCES app_user(id),
    reviewed_by uuid REFERENCES app_user(id),
    reviewed_at timestamptz,
    review_comment varchar(2000),
    applied_commission_version bigint,
    result_commission_id uuid REFERENCES commission(id),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (requested_by IS DISTINCT FROM reviewed_by),
    CHECK (request_type <> 'CONTACT' OR requested_contact_name IS NOT NULL),
    CHECK (request_type <> 'DUE_DATE' OR requested_due_on IS NOT NULL),
    CHECK (state NOT IN ('APPROVED','REJECTED') OR (reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL)),
    CHECK (state <> 'APPROVED' OR (applied_commission_version IS NOT NULL AND result_commission_id IS NOT NULL)),
    CHECK (state = 'APPROVED' OR (applied_commission_version IS NULL AND result_commission_id IS NULL))
);
CREATE INDEX commission_change_request_cursor_ix
    ON commission_change_request(updated_at DESC, id DESC);
CREATE INDEX commission_change_request_commission_ix
    ON commission_change_request(commission_id, state, updated_at DESC);
CREATE INDEX commission_change_request_filter_ix
    ON commission_change_request(state, processing_method, updated_at DESC, id DESC);
CREATE INDEX commission_change_request_actor_ix
    ON commission_change_request(requested_by, reviewed_by, updated_at DESC);
CREATE UNIQUE INDEX commission_one_open_change_request_ux
    ON commission_change_request(commission_id) WHERE state IN ('DRAFT','SUBMITTED');

CREATE TABLE commission_revision (
    id uuid PRIMARY KEY,
    commission_id uuid NOT NULL REFERENCES commission(id),
    revision_number integer NOT NULL CHECK (revision_number > 0),
    change_request_id uuid NOT NULL REFERENCES commission_change_request(id),
    snapshot_json jsonb NOT NULL,
    created_by uuid NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (commission_id, revision_number),
    UNIQUE (change_request_id)
);
CREATE INDEX commission_revision_commission_ix
    ON commission_revision(commission_id, revision_number DESC);
