CREATE TABLE business_numbering_rule (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL REFERENCES organization(id),
  series_id uuid NOT NULL,
  predecessor_id uuid REFERENCES business_numbering_rule(id),
  revision_number integer NOT NULL CHECK (revision_number > 0),
  rule_code varchar(6) NOT NULL CHECK (rule_code ~ '^[A-Za-z0-9]{6}$'),
  resource_type varchar(32) NOT NULL CHECK (resource_type IN
    ('PROJECT','CONTRACT','QUALITY_ASSESSMENT','PRECOMMISSION','COMMISSION')),
  name varchar(200) NOT NULL CHECK (btrim(name) <> ''),
  prefix varchar(80) NOT NULL DEFAULT '',
  prefix_position varchar(24) CHECK (prefix_position IN ('NUMBER_START','BEFORE_SEQUENCE')),
  date_pattern varchar(8) NOT NULL CHECK (date_pattern IN ('NONE','Y','y','Ym','ym','Ymd','ymd')),
  suffix varchar(80) NOT NULL DEFAULT '',
  suffix_position varchar(24) CHECK (suffix_position IN ('NUMBER_END','AFTER_DATE')),
  sequence_width integer NOT NULL CHECK (sequence_width BETWEEN 1 AND 12),
  initial_sequence bigint NOT NULL CHECK (initial_sequence BETWEEN 1 AND 999999999999),
  state varchar(16) NOT NULL CHECK (state IN ('DRAFT','PUBLISHED','RETIRED','DISCARDED')),
  lock_version bigint NOT NULL DEFAULT 0,
  source_system varchar(40),
  source_record_id varchar(120),
  source_path varchar(500),
  source_captured_at timestamptz,
  content_sha256 char(64),
  created_by uuid NOT NULL REFERENCES app_user(id),
  updated_by uuid NOT NULL REFERENCES app_user(id),
  published_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (series_id, revision_number),
  FOREIGN KEY (series_id) REFERENCES business_numbering_rule(id) DEFERRABLE INITIALLY DEFERRED
);

CREATE UNIQUE INDEX business_numbering_rule_one_draft_ix
  ON business_numbering_rule(series_id) WHERE state='DRAFT';
CREATE UNIQUE INDEX business_numbering_rule_one_published_series_ix
  ON business_numbering_rule(series_id) WHERE state='PUBLISHED';
CREATE UNIQUE INDEX business_numbering_rule_published_resource_ix
  ON business_numbering_rule(organization_id, resource_type) WHERE state='PUBLISHED';
CREATE UNIQUE INDEX business_numbering_rule_draft_code_ix
  ON business_numbering_rule(organization_id, lower(rule_code)) WHERE state='DRAFT';
CREATE UNIQUE INDEX business_numbering_rule_published_code_ix
  ON business_numbering_rule(organization_id, lower(rule_code)) WHERE state='PUBLISHED';
CREATE INDEX business_numbering_rule_list_ix
  ON business_numbering_rule(organization_id, updated_at DESC, id DESC);

CREATE TABLE business_number_sequence (
  rule_series_id uuid PRIMARY KEY REFERENCES business_numbering_rule(id),
  current_value bigint NOT NULL CHECK (current_value >= 0),
  lock_version bigint NOT NULL DEFAULT 0,
  reset_count bigint NOT NULL DEFAULT 0,
  last_reset_by uuid REFERENCES app_user(id),
  last_reset_at timestamptz,
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE template_numbering_rule (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL REFERENCES organization(id),
  series_id uuid NOT NULL,
  predecessor_id uuid REFERENCES template_numbering_rule(id),
  revision_number integer NOT NULL CHECK (revision_number > 0),
  code varchar(80) NOT NULL CHECK (btrim(code) <> ''),
  template_type varchar(40) NOT NULL CHECK (template_type IN
    ('CONCRETE_ASSESSMENT','REPORT_DISTRIBUTION','EQUIPMENT_BORROW_RETURN','REPORT_ARCHIVE_FLOW')),
  state varchar(16) NOT NULL CHECK (state IN ('DRAFT','PUBLISHED','RETIRED','DISCARDED')),
  lock_version bigint NOT NULL DEFAULT 0,
  source_system varchar(40),
  source_record_id varchar(120),
  source_path varchar(500),
  source_captured_at timestamptz,
  content_sha256 char(64),
  created_by uuid NOT NULL REFERENCES app_user(id),
  updated_by uuid NOT NULL REFERENCES app_user(id),
  published_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (series_id, revision_number),
  FOREIGN KEY (series_id) REFERENCES template_numbering_rule(id) DEFERRABLE INITIALLY DEFERRED
);

CREATE UNIQUE INDEX template_numbering_rule_one_draft_ix
  ON template_numbering_rule(series_id) WHERE state='DRAFT';
CREATE UNIQUE INDEX template_numbering_rule_one_published_series_ix
  ON template_numbering_rule(series_id) WHERE state='PUBLISHED';
CREATE UNIQUE INDEX template_numbering_rule_published_type_ix
  ON template_numbering_rule(organization_id, template_type) WHERE state='PUBLISHED';
CREATE UNIQUE INDEX template_numbering_rule_draft_code_ix
  ON template_numbering_rule(organization_id, lower(code)) WHERE state='DRAFT';
CREATE UNIQUE INDEX template_numbering_rule_published_code_ix
  ON template_numbering_rule(organization_id, lower(code)) WHERE state='PUBLISHED';
CREATE INDEX template_numbering_rule_list_ix
  ON template_numbering_rule(organization_id, updated_at DESC, id DESC);

CREATE TABLE audit_business_category (
  code varchar(16) PRIMARY KEY,
  label varchar(80) NOT NULL UNIQUE,
  sort_order integer NOT NULL UNIQUE,
  resource_types varchar(80)[] NOT NULL DEFAULT '{}'::varchar[],
  source_system varchar(40) NOT NULL,
  source_record_id varchar(120) NOT NULL,
  source_path varchar(500) NOT NULL,
  source_captured_at timestamptz NOT NULL
);

INSERT INTO audit_business_category
  (code,label,sort_order,resource_types,source_system,source_record_id,source_path,source_captured_at)
VALUES
  ('SRC_001','配置',1,ARRAY['CONFIGURATION','DATA_DICTIONARY','SYSTEM_THEME']::varchar[],'showcase_catalog','配置','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_002','委托单',2,ARRAY['COMMISSION','PRECOMMISSION']::varchar[],'showcase_catalog','委托单','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_003','人员账号',3,ARRAY['USER','PERSON']::varchar[],'showcase_catalog','人员账号','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_004','人员角色',4,ARRAY['ROLE','ROLE_POLICY']::varchar[],'showcase_catalog','人员角色','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_005','标准库',5,ARRAY['DETECTION_STANDARD']::varchar[],'showcase_catalog','标准库','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_006','试验原始数据录入',6,ARRAY['TEST_RECORD']::varchar[],'showcase_catalog','试验原始数据录入','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_007','检测任务',7,ARRAY['TEST_TASK']::varchar[],'showcase_catalog','检测任务','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_008','人员职称资质',8,ARRAY['PERSON_TITLE','PERSONNEL_QUALIFICATION','ORGANIZATION_QUALIFICATION','METROLOGY_QUALIFICATION']::varchar[],'showcase_catalog','人员职称资质','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_009','人员教育培训',9,ARRAY['TRAINING']::varchar[],'showcase_catalog','人员教育培训','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_010','人员派驻',10,'{}'::varchar[],'showcase_catalog','人员派驻','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_011','设备',11,ARRAY['EQUIPMENT']::varchar[],'showcase_catalog','设备','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_012','设备领用归还',12,ARRAY['EQUIPMENT_BORROW']::varchar[],'showcase_catalog','设备领用归还','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_013','设备定检',13,ARRAY['EQUIPMENT_CERTIFICATE','EQUIPMENT_INSPECTION']::varchar[],'showcase_catalog','设备定检','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_014','设备维保',14,'{}'::varchar[],'showcase_catalog','设备维保','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_015','设备期间核查',15,'{}'::varchar[],'showcase_catalog','设备期间核查','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_016','报告',16,ARRAY['REPORT','REPORT_VERSION']::varchar[],'showcase_catalog','报告','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_017','编号模板',17,ARRAY['NUMBERING_RULE','TEMPLATE_NUMBERING']::varchar[],'showcase_catalog','编号模板','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_018','报告存档',18,ARRAY['REPORT_ARCHIVE']::varchar[],'showcase_catalog','报告存档','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_019','印章',19,ARRAY['SIGNING_ASSET','SIGNATURE_SCHEME']::varchar[],'showcase_catalog','印章','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_020','项目组',20,ARRAY['PROJECT_TEAM']::varchar[],'showcase_catalog','项目组','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_021','报告版本',21,ARRAY['REPORT_VERSION','DOCUMENT_TEMPLATE_CONTROL']::varchar[],'showcase_catalog','报告版本','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_022','工程',22,ARRAY['PROJECT']::varchar[],'showcase_catalog','工程','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_023','样品货架',23,ARRAY['SAMPLE_SHELF']::varchar[],'showcase_catalog','样品货架','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_024','检测结果',24,ARRAY['TEST_RECORD']::varchar[],'showcase_catalog','检测结果','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_025','委托修改申请',25,ARRAY['COMMISSION_CHANGE_REQUEST']::varchar[],'showcase_catalog','委托修改申请','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_026','委托单任务单打印字段配置',26,'{}'::varchar[],'showcase_catalog','委托单任务单打印字段配置','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_027','样品留样',27,ARRAY['SAMPLE']::varchar[],'showcase_catalog','样品留样','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_028','现场检测app检测记录',28,'{}'::varchar[],'showcase_catalog','现场检测app检测记录','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_029','现场检测人脸对比记录',29,'{}'::varchar[],'showcase_catalog','现场检测人脸对比记录','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_030','工程流水号',30,ARRAY['BUSINESS_NUMBERING_RULE']::varchar[],'showcase_catalog','工程流水号','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_031','分支机构检测项目',31,ARRAY['DEPARTMENT']::varchar[],'showcase_catalog','分支机构检测项目','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_032','工地试验室',32,'{}'::varchar[],'showcase_catalog','工地试验室','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_033','合同',33,ARRAY['CONTRACT']::varchar[],'showcase_catalog','合同','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_034','合同标的交付',34,ARRAY['CONTRACT_ENTRY']::varchar[],'showcase_catalog','合同标的交付','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_035','草拟合同',35,ARRAY['CONTRACT']::varchar[],'showcase_catalog','草拟合同','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_036','合同类别',36,'{}'::varchar[],'showcase_catalog','合同类别','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_037','验样',37,ARRAY['SAMPLE']::varchar[],'showcase_catalog','验样','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_038','考勤',38,'{}'::varchar[],'showcase_catalog','考勤','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_039','异地考勤地点',39,'{}'::varchar[],'showcase_catalog','异地考勤地点','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_040','订单',40,'{}'::varchar[],'showcase_catalog','订单','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_041','业务表单',41,'{}'::varchar[],'showcase_catalog','业务表单','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_042','表单操作记录',42,'{}'::varchar[],'showcase_catalog','表单操作记录','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_043','消息通知配置',43,ARRAY['NOTIFICATION_RULE']::varchar[],'showcase_catalog','消息通知配置','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_044','设备分类',44,ARRAY['EQUIPMENT_CATEGORY']::varchar[],'showcase_catalog','设备分类','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_045','物质',45,'{}'::varchar[],'showcase_catalog','物质','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_046','知识',46,'{}'::varchar[],'showcase_catalog','知识','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_047','经营',47,'{}'::varchar[],'showcase_catalog','经营','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_048','业务编号配置',48,ARRAY['BUSINESS_NUMBERING_RULE']::varchar[],'showcase_catalog','业务编号配置','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_049','财务',49,ARRAY['FEE_STATEMENT','RECEIPT','PAYMENT']::varchar[],'showcase_catalog','财务','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_050','安全责任书',50,ARRAY['SAFETY_BRIEFING']::varchar[],'showcase_catalog','安全责任书','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_051','工程收款登记',51,ARRAY['RECEIPT']::varchar[],'showcase_catalog','工程收款登记','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_052','培训考核',52,ARRAY['TRAINING']::varchar[],'showcase_catalog','培训考核','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_053','供应商',53,'{}'::varchar[],'showcase_catalog','供应商','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_054','子工程',54,ARRAY['PROJECT_PART']::varchar[],'showcase_catalog','子工程','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_055','检测计划',55,ARRAY['PROJECT_PLAN']::varchar[],'showcase_catalog','检测计划','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_056','发票登记',56,'{}'::varchar[],'showcase_catalog','发票登记','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_057','发票申请',57,'{}'::varchar[],'showcase_catalog','发票申请','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_058','专家及评价',58,'{}'::varchar[],'showcase_catalog','专家及评价','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_059','参数设置',59,ARRAY['DETECTION_PROJECT_REVISION','TEST_PARAMETER']::varchar[],'showcase_catalog','参数设置','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_060','现场检测情况记录',60,ARRAY['TEST_RECORD']::varchar[],'showcase_catalog','现场检测情况记录','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_061','业务编号变更规则',61,ARRAY['BUSINESS_NUMBERING_RULE']::varchar[],'showcase_catalog','业务编号变更规则','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_062','二维码管理',62,ARRAY['SAMPLE_SHELF']::varchar[],'showcase_catalog','二维码管理','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_063','模板编号配置',63,ARRAY['TEMPLATE_NUMBERING_RULE']::varchar[],'showcase_catalog','模板编号配置','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_064','流水模板',64,ARRAY['NUMBERING_FLOW_TEMPLATE']::varchar[],'showcase_catalog','流水模板','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_065','流水规则设置',65,ARRAY['NUMBERING_FLOW_RULE']::varchar[],'showcase_catalog','流水规则设置','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_066','样品检测费用',66,ARRAY['FEE_STATEMENT']::varchar[],'showcase_catalog','样品检测费用','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_067','检测流转时间配置',67,ARRAY['TRANSITION_TIME_SETTING']::varchar[],'showcase_catalog','检测流转时间配置','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_068','设备出入库',68,ARRAY['EQUIPMENT_MOVEMENT']::varchar[],'showcase_catalog','设备出入库','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_069','报告通知',69,ARRAY['NOTIFICATION']::varchar[],'showcase_catalog','报告通知','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_070','样本标记设置',70,ARRAY['DETECTION_SAMPLE_MARKER']::varchar[],'showcase_catalog','样本标记设置','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_071','试验阀值设置',71,ARRAY['TEST_PARAMETER']::varchar[],'showcase_catalog','试验阀值设置','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_072','综合委托',72,ARRAY['COMMISSION']::varchar[],'showcase_catalog','综合委托','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_073','无委托报告',73,ARRAY['REPORT']::varchar[],'showcase_catalog','无委托报告','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_074','二维码方案',74,'{}'::varchar[],'showcase_catalog','二维码方案','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_075','手动签字记录',75,ARRAY['SIGNING_ASSET']::varchar[],'showcase_catalog','手动签字记录','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_076','检测参数试验过程影像',76,ARRAY['TEST_RECORD']::varchar[],'showcase_catalog','检测参数试验过程影像','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_077','检测方案制定',77,'{}'::varchar[],'showcase_catalog','检测方案制定','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_078','合同费用',78,ARRAY['CONTRACT_ENTRY','FEE_STATEMENT']::varchar[],'showcase_catalog','合同费用','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_079','检测人员绩效',79,'{}'::varchar[],'showcase_catalog','检测人员绩效','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_080','客户信息',80,ARRAY['CUSTOMER']::varchar[],'showcase_catalog','客户信息','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_081','试剂管理',81,'{}'::varchar[],'showcase_catalog','试剂管理','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_082','房间设置',82,'{}'::varchar[],'showcase_catalog','房间设置','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z'),
  ('SRC_083','电子门牌',83,'{}'::varchar[],'showcase_catalog','电子门牌','系统设置/日志信息/操作日志','2026-08-21T06:40:00Z');

CREATE OR REPLACE FUNCTION audit_operation_type(action_code varchar)
RETURNS varchar LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
  SELECT CASE
    WHEN action_code IN ('LOGIN_SUCCESS','LOGIN_FAILED') THEN 'LOGIN'
    WHEN action_code='LOGOUT' THEN 'LOGOUT'
    WHEN action_code ~ '(VIEWED|DOWNLOADED|EXPORTED|PRINTED|PREVIEWED|READ)$' THEN 'BROWSE'
    WHEN action_code ~ '(CREATED|UPLOADED|OPENED|ADDED|QUEUED)$' THEN 'CREATE'
    WHEN action_code ~ '(DELETED|DISCARDED|RETIRED|ARCHIVED|VOIDED|REVOKED)$' THEN 'DELETE'
    ELSE 'UPDATE'
  END
$$;

CREATE INDEX audit_event_org_time_ix
  ON audit_event(organization_id, occurred_at DESC, id DESC);
CREATE INDEX audit_event_org_resource_time_ix
  ON audit_event(organization_id, resource_type, occurred_at DESC, id DESC);
CREATE INDEX audit_event_org_operation_time_ix
  ON audit_event(organization_id, audit_operation_type(action), occurred_at DESC, id DESC);
