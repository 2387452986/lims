ALTER TABLE person_qualification
  ADD COLUMN issuing_authority varchar(200),
  ADD COLUMN practice_number varchar(120),
  ADD COLUMN practice_organization varchar(200),
  ADD COLUMN specialties varchar(40)[] NOT NULL DEFAULT '{}'::varchar[],
  ADD COLUMN remark varchar(2000),
  ADD COLUMN certificate_file_id uuid REFERENCES stored_file(id),
  ADD COLUMN submitted_at timestamptz,
  ADD COLUMN submitted_by uuid REFERENCES app_user(id),
  ADD COLUMN reviewed_at timestamptz,
  ADD COLUMN reviewed_by uuid REFERENCES app_user(id),
  ADD COLUMN created_by uuid REFERENCES app_user(id),
  ADD COLUMN updated_by uuid REFERENCES app_user(id);

ALTER TABLE person_qualification DROP CONSTRAINT person_qualification_status_check;
ALTER TABLE person_qualification ADD CONSTRAINT person_qualification_status_check CHECK (
  status IN ('DRAFT','SUBMITTED','RETURNED','VALID','SUSPENDED','EXPIRED','REVOKED','ARCHIVED')
);

CREATE INDEX person_qualification_cursor_ix
  ON person_qualification(updated_at DESC,id DESC);
CREATE INDEX person_qualification_status_validity_ix
  ON person_qualification(status,valid_until,id);
CREATE INDEX person_qualification_issuer_ix
  ON person_qualification(issuing_authority,id);
CREATE INDEX person_qualification_specialties_gin_ix
  ON person_qualification USING gin(specialties);
CREATE INDEX person_qualification_file_ix
  ON person_qualification(certificate_file_id) WHERE certificate_file_id IS NOT NULL;
CREATE UNIQUE INDEX person_qualification_certificate_file_ux
  ON person_qualification(certificate_file_id) WHERE certificate_file_id IS NOT NULL;

CREATE OR REPLACE FUNCTION protect_controlled_person_qualification()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.status NOT IN ('DRAFT','RETURNED') AND (
    NEW.person_id IS DISTINCT FROM OLD.person_id OR
    NEW.qualification_code IS DISTINCT FROM OLD.qualification_code OR
    NEW.certificate_number IS DISTINCT FROM OLD.certificate_number OR
    NEW.valid_from IS DISTINCT FROM OLD.valid_from OR
    NEW.valid_until IS DISTINCT FROM OLD.valid_until OR
    NEW.issuing_authority IS DISTINCT FROM OLD.issuing_authority OR
    NEW.practice_number IS DISTINCT FROM OLD.practice_number OR
    NEW.practice_organization IS DISTINCT FROM OLD.practice_organization OR
    NEW.specialties IS DISTINCT FROM OLD.specialties OR
    NEW.remark IS DISTINCT FROM OLD.remark OR
    NEW.certificate_file_id IS DISTINCT FROM OLD.certificate_file_id
  ) THEN
    RAISE EXCEPTION 'submitted personnel qualification content is immutable; return or revise it first';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER person_qualification_controlled_content_trg
BEFORE UPDATE ON person_qualification
FOR EACH ROW EXECUTE FUNCTION protect_controlled_person_qualification();
