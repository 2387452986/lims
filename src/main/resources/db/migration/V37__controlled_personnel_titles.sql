ALTER TABLE person_title_record
  ADD CONSTRAINT person_title_certificate_number_check CHECK (btrim(certificate_number) <> ''),
  ADD CONSTRAINT person_title_specialty_name_check CHECK (btrim(specialty_name) <> ''),
  ADD CONSTRAINT person_title_issuing_authority_check CHECK (btrim(issuing_authority) <> '');

CREATE UNIQUE INDEX person_title_certificate_ci_ux
  ON person_title_record(organization_id,upper(certificate_number));

CREATE UNIQUE INDEX person_title_attachment_file_ux
  ON person_title_record(attachment_file_id) WHERE attachment_file_id IS NOT NULL;

CREATE INDEX person_title_person_cursor_ix
  ON person_title_record(person_id,updated_at DESC,id DESC);

CREATE OR REPLACE FUNCTION protect_controlled_person_title()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.state <> 'DRAFT' AND (
    NEW.person_id IS DISTINCT FROM OLD.person_id OR
    NEW.issued_on IS DISTINCT FROM OLD.issued_on OR
    NEW.certificate_number IS DISTINCT FROM OLD.certificate_number OR
    NEW.specialty_name IS DISTINCT FROM OLD.specialty_name OR
    NEW.issuing_authority IS DISTINCT FROM OLD.issuing_authority OR
    NEW.title_level IS DISTINCT FROM OLD.title_level OR
    NEW.specialty_category IS DISTINCT FROM OLD.specialty_category OR
    NEW.attachment_file_id IS DISTINCT FROM OLD.attachment_file_id OR
    NEW.remark IS DISTINCT FROM OLD.remark
  ) THEN
    RAISE EXCEPTION 'submitted personnel title content is immutable; recall it first';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER person_title_controlled_content_trg
BEFORE UPDATE ON person_title_record
FOR EACH ROW EXECUTE FUNCTION protect_controlled_person_title();
