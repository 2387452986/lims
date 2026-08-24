ALTER TABLE person
    ADD COLUMN gender varchar(12) NOT NULL DEFAULT 'UNKNOWN'
        CHECK (gender IN ('UNKNOWN','MALE','FEMALE')),
    ADD COLUMN national_id_cipher bytea,
    ADD COLUMN national_id_nonce bytea,
    ADD COLUMN national_id_digest char(64),
    ADD COLUMN national_id_last4 char(4),
    ADD COLUMN birth_date date,
    ADD COLUMN position varchar(120),
    ADD COLUMN person_type varchar(24) NOT NULL DEFAULT 'EMPLOYEE'
        CHECK (person_type IN ('EMPLOYEE','CONTRACTOR','EXTERNAL_EXPERT','INTERN')),
    ADD COLUMN office_location varchar(240),
    ADD COLUMN specialty varchar(240),
    ADD COLUMN education varchar(80),
    ADD COLUMN testing_started_on date,
    ADD COLUMN labor_relationship varchar(120),
    ADD COLUMN testing_qualification varchar(500),
    ADD COLUMN title_issuer varchar(240),
    ADD COLUMN remark varchar(2000),
    ADD COLUMN application_state varchar(20) NOT NULL DEFAULT 'DRAFT'
        CHECK (application_state IN ('DRAFT','SUBMITTED','VERIFIED','RETURNED','ARCHIVED')),
    ADD COLUMN identity_state varchar(20) NOT NULL DEFAULT 'UNVERIFIED'
        CHECK (identity_state IN ('UNVERIFIED','PENDING','VERIFIED','FAILED')),
    ADD COLUMN electronic_signature_state varchar(20) NOT NULL DEFAULT 'NOT_OPENED'
        CHECK (electronic_signature_state IN ('NOT_OPENED','PENDING','OPENED','FAILED')),
    ADD COLUMN avatar_file_id uuid REFERENCES stored_file(id),
    ADD COLUMN id_front_file_id uuid REFERENCES stored_file(id),
    ADD COLUMN id_back_file_id uuid REFERENCES stored_file(id),
    ADD COLUMN social_security_file_id uuid REFERENCES stored_file(id),
    ADD CONSTRAINT person_national_id_encryption_complete CHECK (
        (national_id_cipher IS NULL AND national_id_nonce IS NULL AND national_id_digest IS NULL AND national_id_last4 IS NULL)
        OR
        (national_id_cipher IS NOT NULL AND national_id_nonce IS NOT NULL AND national_id_digest IS NOT NULL AND national_id_last4 IS NOT NULL)
    );

CREATE UNIQUE INDEX person_national_id_digest_ux
    ON person(national_id_digest) WHERE national_id_digest IS NOT NULL;
CREATE INDEX person_filter_ix
    ON person(person_type, application_state, active, updated_at DESC, id DESC);
CREATE INDEX person_department_cursor_ix
    ON person(department_id, updated_at DESC, id DESC);

-- One controlled account per personnel profile. Service accounts keep person_id NULL.
-- Older development and trial datasets allowed multiple accounts to share one profile. Preserve every
-- account by giving each additional account its own deterministic legacy profile before enforcing the rule.
CREATE TEMP TABLE personnel_account_rebind ON COMMIT DROP AS
SELECT account.id user_id, account.person_id old_person_id,
       md5('personnel-account-profile:' || account.id::text)::uuid new_person_id,
       account.organization_id, COALESCE(account.department_id, source_person.department_id) department_id,
       account.display_name, account.enabled
FROM (
  SELECT app_user.*,
         row_number() OVER (PARTITION BY person_id ORDER BY created_at,id) account_order
  FROM app_user WHERE person_id IS NOT NULL
) account
JOIN person source_person ON source_person.id=account.person_id
WHERE account.account_order>1;

INSERT INTO person(id,department_id,employee_number,name,position,person_type,remark,active)
SELECT new_person_id,department_id,'ACCOUNT-' || upper(substr(md5(user_id::text),1,24)),display_name,
       '历史账号迁移档案','EMPLOYEE','V31 自动拆分：既有多个账号曾共用同一人员档案',enabled
FROM personnel_account_rebind
ON CONFLICT (id) DO NOTHING;

UPDATE app_user account
SET person_id=rebind.new_person_id,version=version+1,session_version=session_version+1,updated_at=now()
FROM personnel_account_rebind rebind
WHERE account.id=rebind.user_id;

INSERT INTO audit_event(id,request_id,actor_id,actor_name,action,resource_type,resource_id,
                        organization_id,before_json,after_json,reason_code,comment)
SELECT md5('personnel-account-rebind-audit:' || user_id::text)::uuid,'flyway:v31',NULL,'Flyway V31',
       'PERSON_ACCOUNT_PROFILE_REBOUND','USER',user_id::text,organization_id,
       jsonb_build_object('personId',old_person_id),jsonb_build_object('personId',new_person_id),
       'LEGACY_DUPLICATE_PROFILE','保留账号并拆分为独立人员档案后启用一人一账号约束'
FROM personnel_account_rebind
ON CONFLICT (id) DO NOTHING;

CREATE UNIQUE INDEX app_user_person_ux ON app_user(person_id) WHERE person_id IS NOT NULL;

CREATE TABLE person_detection_capability (
    person_id uuid NOT NULL REFERENCES person(id) ON DELETE CASCADE,
    project_revision_id uuid NOT NULL REFERENCES detection_project_revision(id),
    created_by uuid NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (person_id, project_revision_id)
);
CREATE INDEX person_detection_capability_project_ix
    ON person_detection_capability(project_revision_id, person_id);

INSERT INTO permission(code, name, category) VALUES
    ('PERSON_ADMIN', '维护人员档案与账号', 'PERSONNEL'),
    ('PERSON_SENSITIVE_READ', '查看人员敏感身份资料', 'PERSONNEL'),
    ('PERSON_IMPORT', '导入受控人员资料', 'PERSONNEL'),
    ('PERSON_EXPORT', '导出受控人员资料', 'PERSONNEL')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permission(role_id, permission_code)
SELECT role.id, permission.code
FROM app_role role
CROSS JOIN permission
WHERE role.code='SYSTEM_ADMIN'
  AND permission.code IN ('PERSON_ADMIN','PERSON_SENSITIVE_READ','PERSON_IMPORT','PERSON_EXPORT')
ON CONFLICT DO NOTHING;
