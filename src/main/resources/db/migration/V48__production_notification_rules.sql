CREATE TABLE notification_event_type (
    code varchar(48) PRIMARY KEY,
    source_key smallint NOT NULL UNIQUE CHECK (source_key BETWEEN 1 AND 10),
    label varchar(120) NOT NULL UNIQUE,
    selectable_recipient_kind varchar(16) NOT NULL
        CHECK (selectable_recipient_kind IN ('PRIMARY','ADDITIONAL')),
    selectable_recipient_label varchar(40) NOT NULL,
    implicit_recipient_description varchar(240) NOT NULL,
    trigger_description varchar(500) NOT NULL,
    sort_order smallint NOT NULL UNIQUE,
    source_system varchar(40) NOT NULL,
    source_path varchar(500) NOT NULL,
    source_captured_at timestamptz NOT NULL,
    source_manifest_sha256 char(64) NOT NULL
);

INSERT INTO notification_event_type(
    code,source_key,label,selectable_recipient_kind,selectable_recipient_label,
    implicit_recipient_description,trigger_description,sort_order,source_system,source_path,
    source_captured_at,source_manifest_sha256)
VALUES
('COMMISSION_PENDING_SUBMISSION',1,'委托单待提交流转','PRIMARY','通知人员','配置的通知人员','草稿委托达到条件限制天数后提醒提交流转',1,'showcase_catalog','系统设置 / 基础数据 / 消息通知配置','2026-08-21T11:08:51+08:00','c119428eccd49d0ac183f20384a21f01296db3a9f84ecf965c0c35d45bcaa85e'),
('TASK_PENDING_ASSIGNMENT',2,'任务单待派发','PRIMARY','通知人员','配置的通知人员','未派发任务达到条件限制天数后提醒派发',2,'showcase_catalog','系统设置 / 基础数据 / 消息通知配置','2026-08-21T11:08:51+08:00','c119428eccd49d0ac183f20384a21f01296db3a9f84ecf965c0c35d45bcaa85e'),
('TASK_PENDING_ACCEPTANCE',3,'任务单待接收','ADDITIONAL','其他通知人员','任务当前检测人员','已派发任务达到条件限制天数仍未接收时提醒',3,'showcase_catalog','系统设置 / 基础数据 / 消息通知配置','2026-08-21T11:08:51+08:00','c119428eccd49d0ac183f20384a21f01296db3a9f84ecf965c0c35d45bcaa85e'),
('TASK_PENDING_TEST',4,'任务单待检测','ADDITIONAL','其他通知人员','任务当前检测人员','已接收任务达到条件限制天数仍未开始检测时提醒',4,'showcase_catalog','系统设置 / 基础数据 / 消息通知配置','2026-08-21T11:08:51+08:00','c119428eccd49d0ac183f20384a21f01296db3a9f84ecf965c0c35d45bcaa85e'),
('REPORT_PENDING_DRAFT',5,'报告待编制','ADDITIONAL','其他通知人员','委托创建人','委托进入报告阶段且尚无报告时提醒编制',5,'showcase_catalog','系统设置 / 基础数据 / 消息通知配置','2026-08-21T11:08:51+08:00','c119428eccd49d0ac183f20384a21f01296db3a9f84ecf965c0c35d45bcaa85e'),
('REPORT_PENDING_CHECK',6,'报告待校核','ADDITIONAL','其他通知人员','数据范围内具备报告审核权限的人员','报告草稿或退回稿达到条件限制天数时提醒校核',6,'showcase_catalog','系统设置 / 基础数据 / 消息通知配置','2026-08-21T11:08:51+08:00','c119428eccd49d0ac183f20384a21f01296db3a9f84ecf965c0c35d45bcaa85e'),
('REPORT_PENDING_REVIEW',7,'报告待审核','ADDITIONAL','其他通知人员','数据范围内具备报告审核权限的人员','报告进入审核状态并达到条件限制天数时提醒审核',7,'showcase_catalog','系统设置 / 基础数据 / 消息通知配置','2026-08-21T11:08:51+08:00','c119428eccd49d0ac183f20384a21f01296db3a9f84ecf965c0c35d45bcaa85e'),
('REPORT_PENDING_ISSUE',8,'报告待签发','ADDITIONAL','其他通知人员','数据范围内具备报告签发权限的人员','报告审核通过并达到条件限制天数时提醒签发',8,'showcase_catalog','系统设置 / 基础数据 / 消息通知配置','2026-08-21T11:08:51+08:00','c119428eccd49d0ac183f20384a21f01296db3a9f84ecf965c0c35d45bcaa85e'),
('EQUIPMENT_VERIFICATION',9,'设备检定','PRIMARY','通知人员','配置的通知人员','检定证书在条件限制天数内到期或已逾期时提醒',9,'showcase_catalog','系统设置 / 基础数据 / 消息通知配置','2026-08-21T11:08:51+08:00','c119428eccd49d0ac183f20384a21f01296db3a9f84ecf965c0c35d45bcaa85e'),
('SAMPLE_RETENTION_CLEANUP',10,'样品留样清理','ADDITIONAL','其他通知人员','委托创建人','留样在条件限制天数内到期或已逾期时提醒清理',10,'showcase_catalog','系统设置 / 基础数据 / 消息通知配置','2026-08-21T11:08:51+08:00','c119428eccd49d0ac183f20384a21f01296db3a9f84ecf965c0c35d45bcaa85e');

CREATE TABLE notification_rule_series (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    event_type varchar(48) NOT NULL REFERENCES notification_event_type(code),
    created_by uuid NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id,event_type)
);

CREATE TABLE notification_rule_revision (
    id uuid PRIMARY KEY,
    series_id uuid NOT NULL REFERENCES notification_rule_series(id),
    revision_number integer NOT NULL CHECK (revision_number > 0),
    condition_days integer NOT NULL CHECK (condition_days BETWEEN 0 AND 3650),
    state varchar(16) NOT NULL CHECK (state IN ('ACTIVE','RETIRED')),
    lock_version bigint NOT NULL DEFAULT 0,
    source_system varchar(40),
    source_record_id varchar(120),
    source_captured_at timestamptz,
    created_by uuid NOT NULL REFERENCES app_user(id),
    activated_at timestamptz NOT NULL DEFAULT now(),
    retired_by uuid REFERENCES app_user(id),
    retired_at timestamptz,
    retired_reason varchar(1000),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (series_id,revision_number),
    CHECK ((state='ACTIVE' AND retired_at IS NULL AND retired_by IS NULL)
        OR (state='RETIRED' AND retired_at IS NOT NULL))
);
CREATE UNIQUE INDEX notification_rule_one_active_ix
    ON notification_rule_revision(series_id) WHERE state='ACTIVE';
CREATE INDEX notification_rule_revision_history_ix
    ON notification_rule_revision(series_id,revision_number DESC);

CREATE TABLE notification_rule_recipient (
    revision_id uuid NOT NULL REFERENCES notification_rule_revision(id),
    person_id uuid NOT NULL REFERENCES person(id),
    recipient_kind varchar(16) NOT NULL CHECK (recipient_kind IN ('PRIMARY','ADDITIONAL')),
    sort_order integer NOT NULL DEFAULT 0 CHECK (sort_order >= 0),
    PRIMARY KEY (revision_id,person_id),
    UNIQUE (revision_id,recipient_kind,sort_order)
);
CREATE INDEX notification_rule_recipient_person_ix
    ON notification_rule_recipient(person_id,revision_id);

CREATE OR REPLACE FUNCTION protect_notification_rule_revision() RETURNS trigger AS $$
BEGIN
  IF TG_OP='DELETE' THEN
    RAISE EXCEPTION 'notification rule revisions are immutable';
  END IF;
  IF OLD.series_id IS DISTINCT FROM NEW.series_id
     OR OLD.revision_number IS DISTINCT FROM NEW.revision_number
     OR OLD.condition_days IS DISTINCT FROM NEW.condition_days
     OR OLD.source_system IS DISTINCT FROM NEW.source_system
     OR OLD.source_record_id IS DISTINCT FROM NEW.source_record_id
     OR OLD.source_captured_at IS DISTINCT FROM NEW.source_captured_at
     OR OLD.created_by IS DISTINCT FROM NEW.created_by
     OR OLD.activated_at IS DISTINCT FROM NEW.activated_at
     OR OLD.created_at IS DISTINCT FROM NEW.created_at THEN
    RAISE EXCEPTION 'notification rule revision content is immutable';
  END IF;
  IF OLD.state <> 'ACTIVE' OR NEW.state <> 'RETIRED'
     OR NEW.lock_version <> OLD.lock_version + 1
     OR NEW.retired_at IS NULL THEN
    RAISE EXCEPTION 'notification rule revisions only support controlled retirement';
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER notification_rule_revision_immutable_trg
BEFORE UPDATE OR DELETE ON notification_rule_revision
FOR EACH ROW EXECUTE FUNCTION protect_notification_rule_revision();

CREATE OR REPLACE FUNCTION protect_notification_rule_recipient() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'notification rule recipients are immutable';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER notification_rule_recipient_immutable_trg
BEFORE UPDATE OR DELETE ON notification_rule_recipient
FOR EACH ROW EXECUTE FUNCTION protect_notification_rule_recipient();

ALTER TABLE notification
    ADD COLUMN organization_id uuid REFERENCES organization(id),
    ADD COLUMN rule_series_id uuid REFERENCES notification_rule_series(id),
    ADD COLUMN rule_revision_id uuid REFERENCES notification_rule_revision(id),
    ADD COLUMN resource_state_version bigint;

UPDATE notification notification_row
SET organization_id=account.organization_id
FROM app_user account
WHERE account.id=notification_row.recipient_id AND notification_row.organization_id IS NULL;

ALTER TABLE notification ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE notification ADD CONSTRAINT notification_rule_delivery_complete_check CHECK (
    (rule_series_id IS NULL AND rule_revision_id IS NULL AND resource_state_version IS NULL)
    OR
    (rule_series_id IS NOT NULL AND rule_revision_id IS NOT NULL AND resource_state_version IS NOT NULL
      AND resource_type IS NOT NULL AND resource_id IS NOT NULL)
);
CREATE UNIQUE INDEX notification_rule_delivery_ux
    ON notification(rule_series_id,resource_type,resource_id,resource_state_version,recipient_id)
    WHERE rule_series_id IS NOT NULL;
CREATE INDEX notification_rule_revision_delivery_ix
    ON notification(rule_revision_id,created_at DESC) WHERE rule_revision_id IS NOT NULL;

CREATE INDEX commission_notification_candidate_ix
    ON commission(organization_id,state,updated_at,id)
    WHERE state IN ('DRAFT','REPORTING');
CREATE INDEX task_notification_candidate_ix
    ON test_task(state,updated_at,id)
    WHERE state IN ('UNASSIGNED','ASSIGNED','ACCEPTED');
CREATE INDEX report_notification_candidate_ix
    ON report(state,updated_at,id)
    WHERE state IN ('DRAFT','RETURNED','IN_REVIEW','APPROVED');
CREATE INDEX equipment_verification_notification_ix
    ON equipment_certificate(valid_until,equipment_id,id)
    WHERE certificate_type='VERIFICATION' AND result IN ('PASS','LIMITED');
CREATE INDEX sample_retention_notification_ix
    ON sample(state,updated_at,id) WHERE state='RETAINED';

INSERT INTO permission(code,name,category)
VALUES ('NOTIFICATION_RULE_MANAGE','维护站内消息通知规则','SYSTEM')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permission(role_id,permission_code)
SELECT role.id,'NOTIFICATION_RULE_MANAGE' FROM app_role role WHERE role.code='SYSTEM_ADMIN'
ON CONFLICT DO NOTHING;
