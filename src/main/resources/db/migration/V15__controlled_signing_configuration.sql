ALTER TABLE signing_asset
    ADD COLUMN owner_person_id uuid REFERENCES person(id),
    ADD COLUMN engineering_type varchar(80),
    ADD COLUMN seal_type varchar(40),
    ADD COLUMN valid_from date,
    ADD COLUMN valid_until date,
    ADD COLUMN verification_state varchar(20),
    ADD COLUMN verified_by uuid REFERENCES app_user(id),
    ADD COLUMN verified_at timestamptz,
    ADD COLUMN verification_comment varchar(1000),
    ADD COLUMN pixel_width integer,
    ADD COLUMN pixel_height integer;

UPDATE signing_asset asset
SET owner_person_id = account.person_id
FROM app_user account
WHERE asset.owner_id = account.id AND asset.asset_type = 'SIGNATURE';

UPDATE signing_asset SET verification_state = CASE WHEN active THEN 'VERIFIED' ELSE 'DRAFT' END;

ALTER TABLE signing_asset
    ALTER COLUMN verification_state SET NOT NULL,
    ALTER COLUMN verification_state SET DEFAULT 'DRAFT',
    ADD CONSTRAINT signing_asset_verification_state_ck
        CHECK (verification_state IN ('DRAFT','VERIFIED','REJECTED')),
    ADD CONSTRAINT signing_asset_validity_ck
        CHECK (valid_from IS NULL OR valid_until IS NULL OR valid_until >= valid_from),
    ADD CONSTRAINT signing_asset_dimensions_ck
        CHECK ((pixel_width IS NULL AND pixel_height IS NULL) OR
               (pixel_width BETWEEN 1 AND 4096 AND pixel_height BETWEEN 1 AND 4096));

DROP INDEX signing_asset_active_seal_ix;
DROP INDEX signing_asset_active_signature_ix;

CREATE INDEX signing_asset_active_seal_ix
    ON signing_asset(organization_id, seal_type, valid_until)
    WHERE asset_type='SEAL' AND active AND verification_state='VERIFIED';
CREATE UNIQUE INDEX signing_asset_active_signature_ix
    ON signing_asset(owner_person_id)
    WHERE asset_type='SIGNATURE' AND active AND verification_state='VERIFIED';
CREATE INDEX signing_asset_filter_ix
    ON signing_asset(organization_id, asset_type, verification_state, active, created_at DESC);

CREATE TABLE signature_scheme (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    series_id uuid NOT NULL,
    predecessor_id uuid REFERENCES signature_scheme(id),
    revision_number integer NOT NULL CHECK (revision_number > 0),
    name varchar(160) NOT NULL,
    remark varchar(1000),
    state varchar(20) NOT NULL CHECK (state IN ('DRAFT','ACTIVE','INACTIVE')),
    version bigint NOT NULL DEFAULT 0,
    created_by uuid NOT NULL REFERENCES app_user(id),
    updated_by uuid NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, series_id, revision_number)
);

CREATE UNIQUE INDEX signature_scheme_one_active_ix
    ON signature_scheme(organization_id) WHERE state='ACTIVE';
CREATE INDEX signature_scheme_list_ix
    ON signature_scheme(organization_id, updated_at DESC, id DESC);

CREATE TABLE signature_scheme_position (
    id uuid PRIMARY KEY,
    scheme_id uuid NOT NULL REFERENCES signature_scheme(id) ON DELETE CASCADE,
    position_type varchar(20) NOT NULL CHECK (position_type IN ('SIGNATURE','SEAL')),
    signing_asset_id uuid NOT NULL REFERENCES signing_asset(id),
    label varchar(120) NOT NULL,
    page_rule varchar(20) NOT NULL CHECK (page_rule IN ('FIRST','LAST','EVERY')),
    x_mm numeric(6,2) NOT NULL CHECK (x_mm >= 0 AND x_mm <= 180),
    y_mm numeric(6,2) NOT NULL CHECK (y_mm >= 0 AND y_mm <= 250),
    width_mm numeric(6,2) NOT NULL CHECK (width_mm >= 10 AND width_mm <= 80),
    height_mm numeric(6,2) NOT NULL CHECK (height_mm >= 10 AND height_mm <= 80),
    sort_order integer NOT NULL DEFAULT 0,
    UNIQUE (scheme_id, position_type, sort_order)
);
CREATE INDEX signature_scheme_position_scheme_ix
    ON signature_scheme_position(scheme_id, sort_order);

ALTER TABLE report_version
    ADD COLUMN signature_scheme_id uuid REFERENCES signature_scheme(id);

CREATE OR REPLACE FUNCTION protect_signed_report_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' AND OLD.signed_at IS NOT NULL THEN
        RAISE EXCEPTION 'signed report versions are immutable';
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.signed_at IS NOT NULL THEN
        IF NEW.report_id IS DISTINCT FROM OLD.report_id OR
           NEW.revision IS DISTINCT FROM OLD.revision OR
           NEW.revision_label IS DISTINCT FROM OLD.revision_label OR
           NEW.content_json IS DISTINCT FROM OLD.content_json OR
           NEW.content_sha256 IS DISTINCT FROM OLD.content_sha256 OR
           NEW.signed_at IS DISTINCT FROM OLD.signed_at OR
           NEW.signed_by IS DISTINCT FROM OLD.signed_by OR
           (OLD.sealed_at IS NOT NULL AND NEW.sealed_at IS DISTINCT FROM OLD.sealed_at) OR
           (OLD.sealed_at IS NULL AND NEW.sealed_at IS NOT NULL AND NEW.sealed_at < OLD.signed_at) THEN
            RAISE EXCEPTION 'signed report versions are immutable';
        END IF;
        IF NEW.signature_scheme_id IS DISTINCT FROM OLD.signature_scheme_id AND NOT
           (OLD.signature_scheme_id IS NULL AND NEW.signature_scheme_id IS NOT NULL AND
            OLD.sealed_at IS NULL AND NEW.sealed_at IS NOT NULL) THEN
            RAISE EXCEPTION 'report signing scheme can only be snapshotted during sealing';
        END IF;
        IF NEW.pdf_file_id IS DISTINCT FROM OLD.pdf_file_id OR
           NEW.final_pdf_at IS DISTINCT FROM OLD.final_pdf_at THEN
            IF NOT (OLD.final_pdf_at IS NULL AND NEW.final_pdf_at IS NOT NULL AND
                    OLD.sealed_at IS NOT NULL AND NEW.pdf_file_id IS NOT NULL) THEN
                RAISE EXCEPTION 'final report PDF can only be packaged once after sealing';
            END IF;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
