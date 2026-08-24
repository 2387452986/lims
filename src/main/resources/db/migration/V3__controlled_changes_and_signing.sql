ALTER TABLE report_distribution
    ADD COLUMN version bigint NOT NULL DEFAULT 0;

ALTER TABLE exception_approval
    ADD COLUMN organization_id uuid REFERENCES organization(id),
    ADD COLUMN version bigint NOT NULL DEFAULT 0,
    ADD COLUMN decision_comment varchar(1000);

UPDATE exception_approval e SET organization_id=u.organization_id
FROM app_user u WHERE u.id=e.requested_by;

ALTER TABLE exception_approval ALTER COLUMN organization_id SET NOT NULL;

CREATE INDEX exception_approval_active_ix
    ON exception_approval(resource_type, resource_id, blocker_code, state, expires_at);

CREATE TABLE data_change_request (
    id uuid PRIMARY KEY,
    resource_type varchar(40) NOT NULL CHECK (resource_type IN ('TEST_RECORD')),
    resource_id uuid NOT NULL,
    reason_code varchar(80) NOT NULL,
    reason varchar(1000) NOT NULL,
    proposed_json jsonb NOT NULL,
    state varchar(20) NOT NULL CHECK (state IN ('REQUESTED','APPROVED','REJECTED','APPLIED','CANCELLED')),
    requested_by uuid NOT NULL REFERENCES app_user(id),
    decided_by uuid REFERENCES app_user(id),
    decision_comment varchar(1000),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    decided_at timestamptz,
    applied_at timestamptz
);
CREATE INDEX data_change_request_resource_ix
    ON data_change_request(resource_type, resource_id, created_at DESC);

CREATE TABLE signing_asset (
    id uuid PRIMARY KEY,
    asset_type varchar(20) NOT NULL CHECK (asset_type IN ('SIGNATURE','SEAL')),
    organization_id uuid NOT NULL REFERENCES organization(id),
    owner_id uuid REFERENCES app_user(id),
    name varchar(160) NOT NULL,
    media_type varchar(80) NOT NULL CHECK (media_type IN ('image/png','image/jpeg')),
    content_sha256 char(64) NOT NULL,
    encrypted_content bytea NOT NULL,
    nonce bytea NOT NULL,
    active boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0,
    created_by uuid NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX signing_asset_active_seal_ix
    ON signing_asset(organization_id) WHERE asset_type='SEAL' AND active;
CREATE UNIQUE INDEX signing_asset_active_signature_ix
    ON signing_asset(owner_id) WHERE asset_type='SIGNATURE' AND active;

INSERT INTO permission(code, name, category) VALUES
('FILE_DOWNLOAD', '下载受控文件', 'DATA'),
('SIGNING_ASSET_MANAGE', '管理签名与印章素材', 'REPORT'),
('DATA_CHANGE_APPROVE', '审批检测数据修改', 'QUALITY')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permission(role_id, permission_code)
SELECT '00000000-0000-0000-0000-000000000101'::uuid, code
FROM permission WHERE code IN ('FILE_DOWNLOAD','SIGNING_ASSET_MANAGE','DATA_CHANGE_APPROVE')
ON CONFLICT DO NOTHING;

INSERT INTO role_permission(role_id, permission_code) VALUES
('00000000-0000-0000-0000-000000000102', 'DATA_CHANGE_APPROVE'),
('00000000-0000-0000-0000-000000000104', 'FILE_DOWNLOAD'),
('00000000-0000-0000-0000-000000000105', 'FILE_DOWNLOAD')
ON CONFLICT DO NOTHING;
