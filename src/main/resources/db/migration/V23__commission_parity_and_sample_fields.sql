-- Reference-parity commission intake fields and versioned project-level sample schemas.
-- Values copied from the visible trial UI are source-labelled; unavailable values remain absent.

CREATE SEQUENCE commission_management_sequence_seq;
SELECT setval('commission_management_sequence_seq',
              GREATEST((SELECT count(*) FROM commission), 1), true);

ALTER TABLE commission
    ADD COLUMN management_sequence bigint NOT NULL DEFAULT nextval('commission_management_sequence_seq'),
    ADD COLUMN external_number varchar(120),
    ADD COLUMN received_department_id uuid REFERENCES department(id),
    ADD COLUMN project_progress varchar(40) NOT NULL DEFAULT 'FOUNDATION_FACILITIES'
        CHECK (project_progress IN ('FOUNDATION_FACILITIES','MAIN_COMPLETED','DECORATION_COMPLETED',
          'PENDING_COMPLETION','COMPLETED','TERMINATED','TEMPORARY_WORK','MAIN_WORK',
          'AUXILIARY_FACILITIES','OTHER_FACILITIES','NOT_APPLICABLE')),
    ADD COLUMN testing_type varchar(40) NOT NULL DEFAULT 'PARALLEL_SUPERVISION'
        CHECK (testing_type IN ('PARALLEL_SUPERVISION','PROJECT_OWNER','CONSTRUCTION_SELF_TEST',
          'ENTRUSTED','WITNESSED_ENTRUSTED','ENTITY','SUPERVISION','WITNESSED_SAMPLING',
          'WITNESSED','SAMPLING','ARBITRATION','WITNESSED_RETEST','ENTRUSTED_RETEST','COMPARISON')),
    ADD COLUMN testing_standard varchar(32) NOT NULL DEFAULT 'NATIONAL'
        CHECK (testing_standard IN ('NATIONAL','INDUSTRY','WATER_CONSERVANCY','HIGHWAY',
          'WATER_TRANSPORT','CONSTRUCTION','ELECTRIC_POWER','ENERGY','OTHER_INDUSTRY')),
    ADD COLUMN testing_place varchar(16) NOT NULL DEFAULT 'LAB'
        CHECK (testing_place IN ('LAB','FIELD')),
    ADD COLUMN sample_handling varchar(32) NOT NULL DEFAULT 'CLIENT_DISPOSE'
        CHECK (sample_handling IN ('CLIENT_DISPOSE','CLIENT_RETRIEVE')),
    ADD COLUMN report_delivery varchar(24) NOT NULL DEFAULT 'PICKUP'
        CHECK (report_delivery IN ('PICKUP','MAIL','DELIVERED','EMAIL')),
    ADD COLUMN report_delivery_address varchar(500),
    ADD COLUMN report_copies integer NOT NULL DEFAULT 0 CHECK (report_copies >= 0),
    ADD COLUMN project_part varchar(500),
    ADD COLUMN monomer varchar(240),
    ADD COLUMN settlement_contact varchar(120),
    ADD COLUMN witness_unit varchar(240),
    ADD COLUMN witness_name varchar(120),
    ADD COLUMN witness_phone varchar(40),
    ADD COLUMN witness_certificate varchar(120),
    ADD COLUMN sampling_unit varchar(240),
    ADD COLUMN sampling_person varchar(120),
    ADD COLUMN sampling_phone varchar(40),
    ADD COLUMN sampling_certificate varchar(120),
    ADD COLUMN construction_unit varchar(240),
    ADD COLUMN need_sampling boolean NOT NULL DEFAULT false,
    ADD COLUMN electronic_signature boolean NOT NULL DEFAULT false,
    ADD COLUMN cma_seal boolean NOT NULL DEFAULT false,
    ADD COLUMN qualification_seal boolean NOT NULL DEFAULT false,
    ADD COLUMN external_commission boolean NOT NULL DEFAULT false,
    ADD COLUMN external_organization varchar(240),
    ADD COLUMN sample_receiving_address varchar(500),
    ADD COLUMN expected_report_on date,
    ADD COLUMN remark varchar(2000),
    ADD COLUMN source_remark varchar(2000);

CREATE UNIQUE INDEX commission_org_management_sequence_ux
    ON commission(organization_id, management_sequence);
CREATE INDEX commission_cursor_v23_ix
    ON commission(organization_id, updated_at DESC, id DESC);
CREATE INDEX commission_filter_v23_ix
    ON commission(organization_id, state, testing_place, external_commission, commissioned_on DESC, id DESC);
CREATE INDEX commission_project_customer_v23_ix
    ON commission(organization_id, project_id, customer_id, updated_at DESC);
CREATE INDEX commission_received_department_v23_ix
    ON commission(received_department_id, updated_at DESC) WHERE received_department_id IS NOT NULL;

CREATE TABLE detection_sample_field_definition (
    id uuid PRIMARY KEY,
    project_revision_id uuid NOT NULL REFERENCES detection_project_revision(id) ON DELETE CASCADE,
    source_system varchar(80),
    source_key varchar(160),
    code varchar(80) NOT NULL,
    label varchar(240) NOT NULL,
    field_type varchar(32) NOT NULL CHECK (field_type IN
        ('TEXT','DECIMAL','DATE','SINGLE_SELECT','MULTI_SELECT','STANDARD_SELECT','STANDARD_MULTI')),
    required boolean NOT NULL DEFAULT false,
    unit varchar(40),
    placeholder varchar(240),
    options_json jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(options_json)='array'),
    core_binding varchar(24) NOT NULL DEFAULT 'NONE'
        CHECK (core_binding IN ('NONE','NAME','DESCRIPTION','QUANTITY','UNIT')),
    sort_order integer NOT NULL DEFAULT 0,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (project_revision_id, code)
);
CREATE INDEX detection_sample_field_revision_ix
    ON detection_sample_field_definition(project_revision_id, sort_order, id);

CREATE TABLE detection_sample_field_standard (
    field_definition_id uuid NOT NULL REFERENCES detection_sample_field_definition(id) ON DELETE CASCADE,
    standard_id uuid NOT NULL REFERENCES detection_standard(id),
    sort_order integer NOT NULL DEFAULT 0,
    PRIMARY KEY (field_definition_id, standard_id)
);

CREATE TABLE detection_age_report_option (
    project_revision_id uuid NOT NULL REFERENCES detection_project_revision(id) ON DELETE CASCADE,
    value varchar(40) NOT NULL,
    label varchar(120) NOT NULL,
    default_selected boolean NOT NULL DEFAULT false,
    sort_order integer NOT NULL DEFAULT 0,
    PRIMARY KEY (project_revision_id, value)
);

CREATE TABLE commission_age_report_selection (
    commission_id uuid NOT NULL REFERENCES commission(id) ON DELETE CASCADE,
    value varchar(40) NOT NULL,
    sort_order integer NOT NULL DEFAULT 0,
    PRIMARY KEY (commission_id, value)
);

CREATE TABLE commission_sample_field_value (
    id uuid PRIMARY KEY,
    sample_group_id uuid NOT NULL REFERENCES commission_sample_group(id) ON DELETE CASCADE,
    field_definition_id uuid NOT NULL REFERENCES detection_sample_field_definition(id),
    text_value varchar(2000),
    decimal_value numeric(30,10),
    date_value date,
    option_value varchar(500),
    standard_id uuid REFERENCES detection_standard(id),
    sort_order integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (num_nonnulls(text_value, decimal_value, date_value, option_value, standard_id) = 1),
    UNIQUE (sample_group_id, field_definition_id, sort_order)
);
CREATE INDEX commission_sample_field_group_ix
    ON commission_sample_field_value(sample_group_id, field_definition_id, sort_order);

CREATE TABLE commission_attachment (
    commission_id uuid NOT NULL REFERENCES commission(id) ON DELETE CASCADE,
    file_id uuid NOT NULL REFERENCES stored_file(id),
    purpose varchar(32) NOT NULL DEFAULT 'INTAKE' CHECK (purpose IN ('INTAKE','MODIFICATION','OTHER')),
    sort_order integer NOT NULL DEFAULT 0,
    created_by uuid NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (commission_id, file_id, purpose)
);

CREATE TABLE commission_relation (
    id uuid PRIMARY KEY,
    source_commission_id uuid NOT NULL REFERENCES commission(id) ON DELETE CASCADE,
    target_commission_id uuid NOT NULL REFERENCES commission(id) ON DELETE CASCADE,
    relation_type varchar(24) NOT NULL CHECK (relation_type IN ('RELATED','COPIED_FROM','SPLIT_FROM')),
    comment varchar(1000),
    created_by uuid NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (source_commission_id <> target_commission_id),
    UNIQUE (source_commission_id, target_commission_id, relation_type)
);
CREATE INDEX commission_relation_target_ix ON commission_relation(target_commission_id, created_at DESC);

CREATE TABLE commission_transfer (
    id uuid PRIMARY KEY,
    commission_id uuid NOT NULL REFERENCES commission(id),
    from_department_id uuid REFERENCES department(id),
    to_department_id uuid NOT NULL REFERENCES department(id),
    state varchar(24) NOT NULL DEFAULT 'PENDING'
        CHECK (state IN ('PENDING','ACCEPTED','REJECTED','CANCELLED')),
    comment varchar(1000),
    version bigint NOT NULL DEFAULT 0,
    created_by uuid NOT NULL REFERENCES app_user(id),
    reviewed_by uuid REFERENCES app_user(id),
    reviewed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX commission_one_pending_transfer_ux
    ON commission_transfer(commission_id) WHERE state='PENDING';
CREATE INDEX commission_transfer_department_ix
    ON commission_transfer(to_department_id, state, updated_at DESC, id DESC);

-- Visible sample-field configuration for 检测业务 / 委托管理 / 水泥.
-- Source capture: 2026-08-20, trial UI commission WNBA-BG-C-26-0005.
INSERT INTO detection_sample_field_definition
    (id, project_revision_id, source_system, source_key, code, label, field_type, required,
     unit, placeholder, options_json, core_binding, sort_order)
VALUES
    (md5('sample-field:636b2dcc-dde3-507d-b06d-923a69b9777a:sample_name')::uuid,
     '636b2dcc-dde3-507d-b06d-923a69b9777a','showcase_catalog','142788:sample_name','sample_name','样品名称','TEXT',true,NULL,'样品名称','[]','NAME',10),
    (md5('sample-field:636b2dcc-dde3-507d-b06d-923a69b9777a:money')::uuid,
     '636b2dcc-dde3-507d-b06d-923a69b9777a','showcase_catalog','142788:money','money','检测费用','DECIMAL',false,'元','检测费用','[]','NONE',20),
    (md5('sample-field:636b2dcc-dde3-507d-b06d-923a69b9777a:design_grade')::uuid,
     '636b2dcc-dde3-507d-b06d-923a69b9777a','showcase_catalog','142788:design_grade','design_grade','品种及强度等级','SINGLE_SELECT',true,NULL,'品种及强度等级',
     '["P·Ⅰ42.5","P·Ⅰ42.5R","P·Ⅰ52.5","P·Ⅰ52.5R","P·Ⅰ62.5","P·Ⅰ62.5R","P·Ⅱ42.5","P·Ⅱ42.5R","P·Ⅱ52.5","P·Ⅱ52.5R","P·Ⅱ62.5","P·Ⅱ62.5R","P·O42.5","P·O42.5R","P·O52.5","P·O52.5R","P·S·A32.5","P·S·A32.5R","P·S·A42.5","P·S·A42.5R","P·S·A52.5","P·S·A52.5R","P·S·B32.5","P·S·B32.5R","P·S·B42.5","P·S·B42.5R","P·S·B52.5","P·S·B52.5R","P·MSR42.5","P·HSR42.5","P·P32.5"]','NONE',30),
    (md5('sample-field:636b2dcc-dde3-507d-b06d-923a69b9777a:produce_batch')::uuid,
     '636b2dcc-dde3-507d-b06d-923a69b9777a','showcase_catalog','142788:produce_batch','produce_batch','批号','TEXT',false,NULL,'批号','[]','NONE',40),
    (md5('sample-field:636b2dcc-dde3-507d-b06d-923a69b9777a:project_part')::uuid,
     '636b2dcc-dde3-507d-b06d-923a69b9777a','showcase_catalog','142788:project_part','project_part','结构部位','TEXT',true,NULL,'结构部位','[]','NONE',50),
    (md5('sample-field:636b2dcc-dde3-507d-b06d-923a69b9777a:producer_name')::uuid,
     '636b2dcc-dde3-507d-b06d-923a69b9777a','showcase_catalog','142788:producer_name','producer_name','生产厂家','TEXT',false,NULL,'生产厂家','[]','NONE',60),
    (md5('sample-field:636b2dcc-dde3-507d-b06d-923a69b9777a:sampling_time')::uuid,
     '636b2dcc-dde3-507d-b06d-923a69b9777a','showcase_catalog','142788:sampling_time','sampling_time','取样日期','DATE',false,NULL,'取样日期','[]','NONE',70),
    (md5('sample-field:636b2dcc-dde3-507d-b06d-923a69b9777a:forming_date')::uuid,
     '636b2dcc-dde3-507d-b06d-923a69b9777a','showcase_catalog','142788:forming_date','forming_date','进场日期','DATE',true,NULL,'进场日期','[]','NONE',80),
    (md5('sample-field:636b2dcc-dde3-507d-b06d-923a69b9777a:smaple_number')::uuid,
     '636b2dcc-dde3-507d-b06d-923a69b9777a','showcase_catalog','142788:smaple_number','smaple_number','样品数量','DECIMAL',false,'kg','样品数量(kg)','[]','NONE',90),
    (md5('sample-field:636b2dcc-dde3-507d-b06d-923a69b9777a:represent_number')::uuid,
     '636b2dcc-dde3-507d-b06d-923a69b9777a','showcase_catalog','142788:represent_number','represent_number','代表数量','DECIMAL',false,'t','代表数量（t）','[]','NONE',100),
    (md5('sample-field:636b2dcc-dde3-507d-b06d-923a69b9777a:sampling_location')::uuid,
     '636b2dcc-dde3-507d-b06d-923a69b9777a','showcase_catalog','142788:sampling_location','sampling_location','取样地点','TEXT',false,NULL,'取样地点','[]','NONE',110),
    (md5('sample-field:636b2dcc-dde3-507d-b06d-923a69b9777a:sample_status')::uuid,
     '636b2dcc-dde3-507d-b06d-923a69b9777a','showcase_catalog','142788:sample_status','sample_status','样品状态','SINGLE_SELECT',true,NULL,'样品状态','["正常","异常"]','NONE',120),
    (md5('sample-field:636b2dcc-dde3-507d-b06d-923a69b9777a:sample_desc')::uuid,
     '636b2dcc-dde3-507d-b06d-923a69b9777a','showcase_catalog','142788:sample_desc','sample_desc','样品描述','TEXT',false,NULL,'样品描述','[]','DESCRIPTION',130),
    (md5('sample-field:636b2dcc-dde3-507d-b06d-923a69b9777a:remark')::uuid,
     '636b2dcc-dde3-507d-b06d-923a69b9777a','showcase_catalog','142788:remark','remark','附加说明','TEXT',false,NULL,'附加说明','[]','NONE',140),
    (md5('sample-field:636b2dcc-dde3-507d-b06d-923a69b9777a:sample_person')::uuid,
     '636b2dcc-dde3-507d-b06d-923a69b9777a','showcase_catalog','142788:sample_person','sample_person','抽样人','TEXT',false,NULL,'抽样人','[]','NONE',150),
    (md5('sample-field:636b2dcc-dde3-507d-b06d-923a69b9777a:sg_unit_person')::uuid,
     '636b2dcc-dde3-507d-b06d-923a69b9777a','showcase_catalog','142788:sg_unit_person','sg_unit_person','施工单位代表人','TEXT',false,NULL,'施工单位代表人','[]','NONE',160),
    (md5('sample-field:636b2dcc-dde3-507d-b06d-923a69b9777a:from_sample_sn')::uuid,
     '636b2dcc-dde3-507d-b06d-923a69b9777a','showcase_catalog','142788:from_sample_sn','from_sample_sn','来样编号','TEXT',false,NULL,'来样编号','[]','NONE',170),
    (md5('sample-field:636b2dcc-dde3-507d-b06d-923a69b9777a:judge_standard')::uuid,
     '636b2dcc-dde3-507d-b06d-923a69b9777a','showcase_catalog','142788:judge_standard','judge_standard','判定依据','STANDARD_MULTI',true,NULL,'判定依据','[]','NONE',180),
    (md5('sample-field:636b2dcc-dde3-507d-b06d-923a69b9777a:test_standard')::uuid,
     '636b2dcc-dde3-507d-b06d-923a69b9777a','showcase_catalog','142788:test_standard','test_standard','检测依据','STANDARD_MULTI',true,NULL,'检测依据','[]','NONE',190);

INSERT INTO detection_sample_field_standard(field_definition_id, standard_id, sort_order)
SELECT md5('sample-field:636b2dcc-dde3-507d-b06d-923a69b9777a:judge_standard')::uuid, s.id,
       CASE s.code WHEN 'GB/T 1346-2011' THEN 1 WHEN 'SL/T 62-2020' THEN 2
         WHEN 'GB175-2023' THEN 3 ELSE 4 END
FROM detection_standard s
WHERE s.state='PUBLISHED' AND s.status='CURRENT'
  AND s.code IN ('GB/T 1346-2011','SL/T 62-2020','GB175-2023','GB/T 748-2023');

INSERT INTO detection_sample_field_standard(field_definition_id, standard_id, sort_order)
SELECT md5('sample-field:636b2dcc-dde3-507d-b06d-923a69b9777a:test_standard')::uuid, s.id,
       CASE s.code WHEN 'GB/T 1346-2024' THEN 1 WHEN 'GB/T 8074-2008' THEN 2
         WHEN 'GB/T 208-2014' THEN 3 WHEN 'GB/T 17671-2021' THEN 4
         WHEN 'GB/T 2419-2005' THEN 5 WHEN 'GB/T 1346-2011' THEN 6
         WHEN 'SL/T 62-2020' THEN 7 WHEN 'GB/T 176-2017' THEN 8
         WHEN 'GB175-2023' THEN 9 WHEN 'GB/T 748-2023' THEN 10 ELSE 11 END
FROM detection_standard s
WHERE s.state='PUBLISHED' AND s.status='CURRENT'
  AND s.code IN ('GB/T 1346-2024','GB/T 8074-2008','GB/T 208-2014','GB/T 17671-2021',
                 'GB/T 2419-2005','GB/T 1346-2011','SL/T 62-2020','GB/T 176-2017',
                 'GB175-2023','GB/T 748-2023','GB/T 1345-2005');

INSERT INTO detection_age_report_option(project_revision_id,value,label,default_selected,sort_order)
VALUES ('636b2dcc-dde3-507d-b06d-923a69b9777a','3','3',true,1);
