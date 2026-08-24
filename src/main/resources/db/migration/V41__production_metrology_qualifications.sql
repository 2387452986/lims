ALTER TABLE metrology_qualification_record
  ADD CONSTRAINT metrology_qualification_number_nonblank_check
    CHECK (btrim(certificate_number) <> '') NOT VALID,
  ADD CONSTRAINT metrology_qualification_authority_nonblank_check
    CHECK (btrim(issuing_authority) <> '') NOT VALID;

ALTER TABLE metrology_qualification_record
  VALIDATE CONSTRAINT metrology_qualification_number_nonblank_check;
ALTER TABLE metrology_qualification_record
  VALIDATE CONSTRAINT metrology_qualification_authority_nonblank_check;

CREATE UNIQUE INDEX metrology_qualification_certificate_ci_ux
  ON metrology_qualification_record(organization_id, upper(certificate_number));

CREATE INDEX metrology_qualification_issued_on_ix
  ON metrology_qualification_record(organization_id, issued_on DESC, id DESC);

DROP INDEX metrology_qualification_certificate_file_ix;
DROP INDEX metrology_qualification_other_file_ix;

CREATE UNIQUE INDEX metrology_qualification_certificate_file_ix
  ON metrology_qualification_record(certificate_file_id);

CREATE UNIQUE INDEX metrology_qualification_other_file_ix
  ON metrology_qualification_record(other_file_id) WHERE other_file_id IS NOT NULL;

CREATE OR REPLACE FUNCTION protect_controlled_metrology_qualification()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF OLD.state <> 'DRAFT' AND (
    NEW.certificate_number IS DISTINCT FROM OLD.certificate_number OR
    NEW.issued_on IS DISTINCT FROM OLD.issued_on OR
    NEW.valid_until IS DISTINCT FROM OLD.valid_until OR
    NEW.issuing_authority IS DISTINCT FROM OLD.issuing_authority OR
    NEW.certificate_file_id IS DISTINCT FROM OLD.certificate_file_id OR
    NEW.other_file_id IS DISTINCT FROM OLD.other_file_id OR
    NEW.remark IS DISTINCT FROM OLD.remark
  ) THEN
    RAISE EXCEPTION 'submitted metrology qualification content is immutable; recall it first';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER metrology_qualification_controlled_content_trg
BEFORE UPDATE ON metrology_qualification_record
FOR EACH ROW EXECUTE FUNCTION protect_controlled_metrology_qualification();

COMMENT ON FUNCTION protect_controlled_metrology_qualification()
  IS 'Prevents submitted or retired metrology qualification content from being changed in place.';
