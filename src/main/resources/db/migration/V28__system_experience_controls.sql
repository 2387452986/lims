CREATE TABLE desktop_theme (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    code varchar(80) NOT NULL,
    name varchar(160) NOT NULL,
    remark varchar(1000),
    brand_900 char(7) NOT NULL CHECK (brand_900 ~ '^#[0-9A-Fa-f]{6}$'),
    brand_600 char(7) NOT NULL CHECK (brand_600 ~ '^#[0-9A-Fa-f]{6}$'),
    brand_100 char(7) NOT NULL CHECK (brand_100 ~ '^#[0-9A-Fa-f]{6}$'),
    surface_background char(7) NOT NULL CHECK (surface_background ~ '^#[0-9A-Fa-f]{6}$'),
    density varchar(16) NOT NULL CHECK (density IN ('COMPACT','COMFORTABLE')),
    radius_px smallint NOT NULL CHECK (radius_px BETWEEN 4 AND 16),
    revision integer NOT NULL DEFAULT 1 CHECK (revision > 0),
    supersedes_id uuid REFERENCES desktop_theme(id),
    state varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK (state IN ('DRAFT','ACTIVE','RETIRED')),
    source_system varchar(120),
    source_record_id varchar(160),
    source_path varchar(1000),
    source_captured_at timestamptz,
    source_content_sha256 char(64),
    version bigint NOT NULL DEFAULT 0,
    created_by uuid REFERENCES app_user(id),
    activated_by uuid REFERENCES app_user(id),
    activated_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, code, revision),
    UNIQUE (organization_id, source_system, source_record_id),
    CHECK (state<>'ACTIVE' OR activated_at IS NOT NULL)
);
CREATE UNIQUE INDEX desktop_theme_one_active_ux
    ON desktop_theme(organization_id) WHERE state='ACTIVE';
CREATE INDEX desktop_theme_list_ix
    ON desktop_theme(organization_id, updated_at DESC, id DESC);

INSERT INTO desktop_theme(
    id, organization_id, code, name, remark, brand_900, brand_600, brand_100,
    surface_background, density, radius_px, state, activated_at)
SELECT gen_random_uuid(), organization.id, 'OPTION-3', '现代专业蓝',
       '方案 3 生产设计令牌；系统只接受受控颜色、密度和圆角，不执行任意 CSS。',
       '#07377F', '#1769E0', '#EAF2FF', '#F4F7FB', 'COMPACT', 10, 'ACTIVE', now()
FROM organization
WHERE NOT EXISTS (
    SELECT 1 FROM desktop_theme theme
    WHERE theme.organization_id=organization.id AND theme.state='ACTIVE'
);

CREATE TABLE safety_briefing_rule (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    detection_project_revision_id uuid NOT NULL REFERENCES detection_project_revision(id),
    code varchar(80) NOT NULL,
    title varchar(240) NOT NULL,
    briefing_type varchar(32) NOT NULL CHECK (briefing_type IN
        ('PROJECT_SAFETY','EQUIPMENT_OPERATION','SITE_SAFETY','OTHER')),
    content varchar(10000) NOT NULL,
    read_seconds integer NOT NULL CHECK (read_seconds BETWEEN 5 AND 1800),
    revision integer NOT NULL DEFAULT 1 CHECK (revision > 0),
    supersedes_id uuid REFERENCES safety_briefing_rule(id),
    state varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK (state IN ('DRAFT','ACTIVE','RETIRED')),
    source_system varchar(120),
    source_record_id varchar(160),
    source_path varchar(1000),
    source_captured_at timestamptz,
    source_content_sha256 char(64),
    version bigint NOT NULL DEFAULT 0,
    created_by uuid REFERENCES app_user(id),
    activated_by uuid REFERENCES app_user(id),
    activated_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, code, revision),
    UNIQUE (organization_id, source_system, source_record_id),
    CHECK (state<>'ACTIVE' OR activated_at IS NOT NULL)
);
CREATE UNIQUE INDEX safety_briefing_one_active_ux
    ON safety_briefing_rule(organization_id, detection_project_revision_id, briefing_type)
    WHERE state='ACTIVE';
CREATE INDEX safety_briefing_list_ix
    ON safety_briefing_rule(organization_id, updated_at DESC, id DESC);
CREATE INDEX safety_briefing_project_ix
    ON safety_briefing_rule(detection_project_revision_id, state, id);

CREATE TABLE task_safety_briefing_read (
    id uuid PRIMARY KEY,
    task_id uuid NOT NULL REFERENCES test_task(id),
    rule_id uuid NOT NULL REFERENCES safety_briefing_rule(id),
    rule_version bigint NOT NULL CHECK (rule_version >= 0),
    reader_id uuid NOT NULL REFERENCES app_user(id),
    started_at timestamptz NOT NULL DEFAULT now(),
    acknowledged_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (task_id, rule_id, rule_version, reader_id),
    CHECK (acknowledged_at IS NULL OR acknowledged_at >= started_at)
);
CREATE INDEX task_safety_briefing_reader_ix
    ON task_safety_briefing_read(reader_id, acknowledged_at, started_at DESC);

CREATE TABLE transition_time_setting (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    stage_code varchar(40) NOT NULL CHECK (stage_code IN
        ('SAMPLE_INBOUND','SAMPLE_OUTBOUND','REPORT_EDIT','REPORT_REVIEW','REPORT_ISSUE',
         'REPORT_SEAL','REPORT_ARCHIVE','NONCONFORMANCE_NOTICE')),
    enabled boolean NOT NULL DEFAULT true,
    allowed_days integer NOT NULL CHECK (allowed_days BETWEEN 1 AND 3650),
    version bigint NOT NULL DEFAULT 0,
    updated_by uuid REFERENCES app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, stage_code)
);
CREATE INDEX transition_time_setting_list_ix
    ON transition_time_setting(organization_id, stage_code);

INSERT INTO transition_time_setting(id, organization_id, stage_code, enabled, allowed_days)
SELECT gen_random_uuid(), organization.id, defaults.stage_code, true, defaults.allowed_days
FROM organization
CROSS JOIN (VALUES
    ('SAMPLE_INBOUND', 2), ('SAMPLE_OUTBOUND', 30), ('REPORT_EDIT', 3), ('REPORT_REVIEW', 2),
    ('REPORT_ISSUE', 2), ('REPORT_SEAL', 2), ('REPORT_ARCHIVE', 7), ('NONCONFORMANCE_NOTICE', 1)
) AS defaults(stage_code, allowed_days)
ON CONFLICT (organization_id, stage_code) DO NOTHING;
