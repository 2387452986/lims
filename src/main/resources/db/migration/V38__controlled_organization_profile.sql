ALTER TABLE organization
  ADD COLUMN short_name varchar(120),
  ADD COLUMN organization_nature varchar(24),
  ADD COLUMN unified_credit_code varchar(32),
  ADD COLUMN registered_on date,
  ADD COLUMN registration_authority varchar(200),
  ADD COLUMN postal_code varchar(12),
  ADD COLUMN province varchar(80),
  ADD COLUMN city varchar(80),
  ADD COLUMN district varchar(80),
  ADD COLUMN detailed_address varchar(500),
  ADD COLUMN registered_capital numeric(18,2),
  ADD COLUMN telephone varchar(40),
  ADD COLUMN fax varchar(40),
  ADD COLUMN email varchar(200),
  ADD COLUMN legal_representative_id uuid REFERENCES person(id),
  ADD COLUMN frequent_contact_name varchar(120),
  ADD COLUMN frequent_contact_phone varchar(40),
  ADD COLUMN primary_responsible_person_id uuid REFERENCES person(id),
  ADD COLUMN quality_responsible_person_id uuid REFERENCES person(id),
  ADD COLUMN technical_responsible_person_id uuid REFERENCES person(id),
  ADD COLUMN declared_equipment_count integer,
  ADD COLUMN declared_equipment_original_value numeric(18,2),
  ADD COLUMN testing_area numeric(16,2),
  ADD COLUMN workplace_area numeric(16,2),
  ADD COLUMN profile_remark varchar(2000),
  ADD COLUMN business_license_file_id uuid REFERENCES stored_file(id),
  ADD CONSTRAINT organization_nature_check CHECK (
    organization_nature IS NULL OR organization_nature IN ('STATE_OWNED','PRIVATE')),
  ADD CONSTRAINT organization_registered_capital_check CHECK (
    registered_capital IS NULL OR registered_capital >= 0),
  ADD CONSTRAINT organization_equipment_count_check CHECK (
    declared_equipment_count IS NULL OR declared_equipment_count >= 0),
  ADD CONSTRAINT organization_equipment_value_check CHECK (
    declared_equipment_original_value IS NULL OR declared_equipment_original_value >= 0),
  ADD CONSTRAINT organization_testing_area_check CHECK (testing_area IS NULL OR testing_area >= 0),
  ADD CONSTRAINT organization_workplace_area_check CHECK (workplace_area IS NULL OR workplace_area >= 0);

ALTER TABLE organization
  ADD CONSTRAINT organization_unified_credit_code_check CHECK (
    unified_credit_code IS NULL OR unified_credit_code ~ '^[0-9A-Z]{18}$'),
  ADD CONSTRAINT organization_postal_code_check CHECK (
    postal_code IS NULL OR postal_code ~ '^[0-9]{6}$');

CREATE UNIQUE INDEX organization_unified_credit_code_ci_ux
  ON organization(upper(unified_credit_code)) WHERE unified_credit_code IS NOT NULL;

CREATE UNIQUE INDEX organization_business_license_file_ux
  ON organization(business_license_file_id) WHERE business_license_file_id IS NOT NULL;

CREATE TABLE organization_office_address (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL REFERENCES organization(id),
  current_revision_id uuid NOT NULL,
  correspondence_address boolean NOT NULL DEFAULT false,
  active boolean NOT NULL DEFAULT true,
  retired_reason varchar(1000),
  version bigint NOT NULL DEFAULT 0,
  created_by uuid NOT NULL REFERENCES app_user(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE organization_office_address_revision (
  id uuid PRIMARY KEY,
  address_id uuid NOT NULL REFERENCES organization_office_address(id),
  revision_number integer NOT NULL CHECK (revision_number > 0),
  office_name varchar(160) NOT NULL CHECK (btrim(office_name) <> ''),
  province varchar(80) NOT NULL CHECK (btrim(province) <> ''),
  city varchar(80) NOT NULL CHECK (btrim(city) <> ''),
  district varchar(80) NOT NULL CHECK (btrim(district) <> ''),
  detailed_address varchar(500) NOT NULL CHECK (btrim(detailed_address) <> ''),
  postal_code varchar(12) NOT NULL CHECK (postal_code ~ '^[0-9]{6}$'),
  changed_on date NOT NULL CHECK (changed_on <= current_date),
  correspondence_address boolean NOT NULL,
  created_by uuid NOT NULL REFERENCES app_user(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(address_id, revision_number)
);

ALTER TABLE organization_office_address
  ADD CONSTRAINT organization_office_address_current_revision_fk
  FOREIGN KEY (current_revision_id) REFERENCES organization_office_address_revision(id)
  DEFERRABLE INITIALLY DEFERRED;

CREATE UNIQUE INDEX organization_one_correspondence_address_ux
  ON organization_office_address(organization_id)
  WHERE active AND correspondence_address;

CREATE INDEX organization_office_address_list_ix
  ON organization_office_address(organization_id, active DESC, updated_at DESC, id DESC);

CREATE INDEX organization_office_address_history_ix
  ON organization_office_address_revision(address_id, revision_number DESC);

CREATE OR REPLACE FUNCTION protect_organization_address_revision()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'organization office address revisions are immutable';
END;
$$;

CREATE TRIGGER organization_office_address_revision_immutable_trg
BEFORE UPDATE OR DELETE ON organization_office_address_revision
FOR EACH ROW EXECUTE FUNCTION protect_organization_address_revision();

CREATE OR REPLACE FUNCTION validate_organization_address_current_revision()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM organization_office_address_revision revision
    WHERE revision.id=NEW.current_revision_id AND revision.address_id=NEW.id
      AND revision.correspondence_address=NEW.correspondence_address
  ) THEN
    RAISE EXCEPTION 'organization office address current revision mismatch';
  END IF;
  RETURN NEW;
END;
$$;

CREATE CONSTRAINT TRIGGER organization_office_address_current_revision_trg
AFTER INSERT OR UPDATE OF current_revision_id,correspondence_address ON organization_office_address
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION validate_organization_address_current_revision();
