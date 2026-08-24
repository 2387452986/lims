CREATE OR REPLACE FUNCTION valid_organization_qualification_details(details jsonb)
RETURNS boolean
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
  SELECT jsonb_typeof(details) = 'array'
    AND jsonb_array_length(details) BETWEEN 1 AND 10
    AND jsonb_array_length(details) = (
      SELECT count(DISTINCT item)
      FROM jsonb_array_elements_text(details) AS entries(item)
    )
    AND NOT EXISTS (
      SELECT 1
      FROM jsonb_array_elements_text(details) AS entries(item)
      WHERE item NOT IN (
        'GEOTECHNICAL_A','CONCRETE_A','METAL_STRUCTURE_A','MECHANICAL_ELECTRICAL_A','MEASUREMENT_A',
        'GEOTECHNICAL_B','CONCRETE_B','METAL_STRUCTURE_B','MECHANICAL_ELECTRICAL_B','MEASUREMENT_B'
      )
    );
$$;

ALTER TABLE organization_qualification_record
  ADD CONSTRAINT organization_qualification_number_nonblank_check
    CHECK (btrim(certificate_number) <> '') NOT VALID,
  ADD CONSTRAINT organization_qualification_authority_nonblank_check
    CHECK (btrim(issuing_authority) <> '') NOT VALID,
  ADD CONSTRAINT organization_qualification_scope_nonblank_check
    CHECK (btrim(testing_scope) <> '') NOT VALID,
  ADD CONSTRAINT organization_qualification_details_controlled_check
    CHECK (valid_organization_qualification_details(qualification_details)) NOT VALID;

ALTER TABLE organization_qualification_record
  VALIDATE CONSTRAINT organization_qualification_number_nonblank_check;
ALTER TABLE organization_qualification_record
  VALIDATE CONSTRAINT organization_qualification_authority_nonblank_check;
ALTER TABLE organization_qualification_record
  VALIDATE CONSTRAINT organization_qualification_scope_nonblank_check;
ALTER TABLE organization_qualification_record
  VALIDATE CONSTRAINT organization_qualification_details_controlled_check;

CREATE UNIQUE INDEX organization_qualification_certificate_ci_ux
  ON organization_qualification_record(organization_id, upper(certificate_number));

CREATE INDEX organization_qualification_issued_on_ix
  ON organization_qualification_record(organization_id, issued_on DESC, id DESC);

CREATE INDEX organization_qualification_details_gin_ix
  ON organization_qualification_record USING gin(qualification_details);

CREATE INDEX organization_qualification_original_file_ix
  ON organization_qualification_record(original_file_id) WHERE original_file_id IS NOT NULL;

CREATE INDEX organization_qualification_copy_file_ix
  ON organization_qualification_record(copy_file_id) WHERE copy_file_id IS NOT NULL;

CREATE INDEX metrology_qualification_certificate_file_ix
  ON metrology_qualification_record(certificate_file_id);

CREATE INDEX metrology_qualification_other_file_ix
  ON metrology_qualification_record(other_file_id) WHERE other_file_id IS NOT NULL;

CREATE OR REPLACE FUNCTION protect_controlled_organization_qualification()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF OLD.state <> 'DRAFT' AND (
    NEW.certificate_number IS DISTINCT FROM OLD.certificate_number OR
    NEW.issued_on IS DISTINCT FROM OLD.issued_on OR
    NEW.valid_until IS DISTINCT FROM OLD.valid_until OR
    NEW.issuing_authority IS DISTINCT FROM OLD.issuing_authority OR
    NEW.testing_scope IS DISTINCT FROM OLD.testing_scope OR
    NEW.qualification_details IS DISTINCT FROM OLD.qualification_details OR
    NEW.original_file_id IS DISTINCT FROM OLD.original_file_id OR
    NEW.copy_file_id IS DISTINCT FROM OLD.copy_file_id OR
    NEW.remark IS DISTINCT FROM OLD.remark
  ) THEN
    RAISE EXCEPTION 'submitted organization qualification content is immutable; recall it first';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER organization_qualification_controlled_content_trg
BEFORE UPDATE ON organization_qualification_record
FOR EACH ROW EXECUTE FUNCTION protect_controlled_organization_qualification();

COMMENT ON FUNCTION valid_organization_qualification_details(jsonb)
  IS 'Validates the controlled, non-empty and duplicate-free unit qualification detail set.';
COMMENT ON FUNCTION protect_controlled_organization_qualification()
  IS 'Prevents submitted or retired organization qualification content from being changed in place.';
