CREATE TABLE training_record (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    person_id uuid NOT NULL REFERENCES person(id),
    start_date date NOT NULL,
    end_date date NOT NULL,
    hours numeric(8,2) NOT NULL CHECK (hours >= 0),
    organizer varchar(200) NOT NULL,
    training_method varchar(80) NOT NULL,
    assessment_result varchar(40),
    location varchar(240) NOT NULL,
    content varchar(2000) NOT NULL,
    certificate_file_id uuid REFERENCES stored_file(id),
    remark varchar(1000),
    state varchar(20) NOT NULL DEFAULT 'ACTIVE' CHECK (state IN ('ACTIVE','VOID')),
    version bigint NOT NULL DEFAULT 0,
    created_by uuid NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (end_date >= start_date)
);
CREATE INDEX training_record_org_cursor_ix ON training_record (organization_id, updated_at DESC, id DESC);
CREATE INDEX training_record_person_date_ix ON training_record (person_id, start_date DESC);

CREATE TABLE project_team (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    name varchar(200) NOT NULL,
    remark varchar(1000),
    state varchar(20) NOT NULL DEFAULT 'ACTIVE' CHECK (state IN ('ACTIVE','INACTIVE')),
    version bigint NOT NULL DEFAULT 0,
    created_by uuid NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, name)
);
CREATE INDEX project_team_org_cursor_ix ON project_team (organization_id, updated_at DESC, id DESC);

CREATE TABLE project_team_member (
    team_id uuid NOT NULL REFERENCES project_team(id) ON DELETE CASCADE,
    person_id uuid NOT NULL REFERENCES person(id),
    duty varchar(120) NOT NULL,
    leader boolean NOT NULL DEFAULT false,
    sort_order integer NOT NULL DEFAULT 0,
    PRIMARY KEY (team_id, person_id)
);
CREATE UNIQUE INDEX project_team_one_leader_ix ON project_team_member (team_id) WHERE leader;

CREATE TABLE person_title_record (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    person_id uuid NOT NULL REFERENCES person(id),
    issued_on date NOT NULL,
    certificate_number varchar(120) NOT NULL,
    specialty_name varchar(200) NOT NULL,
    issuing_authority varchar(200) NOT NULL,
    title_level varchar(40) NOT NULL CHECK (title_level IN ('ASSISTANT_ENGINEER','ENGINEER','SENIOR_ENGINEER','PROFESSORATE_SENIOR_ENGINEER')),
    specialty_category varchar(40) NOT NULL CHECK (specialty_category IN ('WATER_CONSERVANCY','TESTING','TRANSPORTATION','MUNICIPAL','PORT_WATERWAY','CIVIL','GEOTECHNICAL','STRUCTURAL','OTHER')),
    attachment_file_id uuid REFERENCES stored_file(id),
    remark varchar(1000),
    state varchar(20) NOT NULL DEFAULT 'DRAFT' CHECK (state IN ('DRAFT','SUBMITTED','RETIRED')),
    version bigint NOT NULL DEFAULT 0,
    created_by uuid NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, certificate_number)
);
CREATE INDEX person_title_org_cursor_ix ON person_title_record (organization_id, updated_at DESC, id DESC);
CREATE INDEX person_title_filter_ix ON person_title_record (organization_id, state, specialty_category, title_level, issued_on DESC);

CREATE TABLE organization_qualification_record (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    certificate_number varchar(160) NOT NULL,
    issued_on date NOT NULL,
    valid_until date NOT NULL,
    issuing_authority varchar(240) NOT NULL,
    testing_scope varchar(1000) NOT NULL,
    qualification_details jsonb NOT NULL DEFAULT '[]'::jsonb,
    original_file_id uuid REFERENCES stored_file(id),
    copy_file_id uuid REFERENCES stored_file(id),
    remark varchar(1000),
    state varchar(20) NOT NULL DEFAULT 'DRAFT' CHECK (state IN ('DRAFT','SUBMITTED','RETIRED')),
    version bigint NOT NULL DEFAULT 0,
    created_by uuid NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (valid_until >= issued_on),
    CHECK (jsonb_typeof(qualification_details) = 'array'),
    UNIQUE (organization_id, certificate_number)
);
CREATE INDEX organization_qualification_cursor_ix ON organization_qualification_record (organization_id, updated_at DESC, id DESC);
CREATE INDEX organization_qualification_validity_ix ON organization_qualification_record (organization_id, state, valid_until);

CREATE TABLE metrology_qualification_record (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    certificate_number varchar(160) NOT NULL,
    issued_on date NOT NULL,
    valid_until date NOT NULL,
    issuing_authority varchar(240) NOT NULL,
    certificate_file_id uuid NOT NULL REFERENCES stored_file(id),
    other_file_id uuid REFERENCES stored_file(id),
    remark varchar(1000),
    state varchar(20) NOT NULL DEFAULT 'DRAFT' CHECK (state IN ('DRAFT','SUBMITTED','RETIRED')),
    version bigint NOT NULL DEFAULT 0,
    created_by uuid NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (valid_until >= issued_on),
    UNIQUE (organization_id, certificate_number)
);
CREATE INDEX metrology_qualification_cursor_ix ON metrology_qualification_record (organization_id, updated_at DESC, id DESC);
CREATE INDEX metrology_qualification_validity_ix ON metrology_qualification_record (organization_id, state, valid_until);

CREATE TABLE sample_shelf (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    shelf_number varchar(80) NOT NULL,
    sort_order integer NOT NULL DEFAULT 0 CHECK (sort_order >= 0),
    active boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0,
    created_by uuid NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, shelf_number)
);
CREATE INDEX sample_shelf_org_order_ix ON sample_shelf (organization_id, active DESC, sort_order, shelf_number);

COMMENT ON TABLE training_record IS '人员教育培训及证书附件；替代 training 通用目录壳。';
COMMENT ON TABLE project_team IS '检测项目组及职责成员；替代 project-groups 通用目录壳。';
COMMENT ON TABLE person_title_record IS '人员职称证书及提交状态；替代 titles 通用目录壳。';
COMMENT ON TABLE organization_qualification_record IS '单位检测资质及受控附件；替代 organization-qualifications 通用目录壳。';
COMMENT ON TABLE metrology_qualification_record IS '计量认证及受控附件；替代 metrology-qualifications 通用目录壳。';
COMMENT ON TABLE sample_shelf IS '样品库货架配置；替代 shelves 通用目录壳。';
