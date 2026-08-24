ALTER TABLE app_user DROP COLUMN password_verified_at;

ALTER TABLE test_parameter
    ADD COLUMN lock_version bigint NOT NULL DEFAULT 0,
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN created_by uuid REFERENCES app_user(id);

CREATE UNIQUE INDEX test_parameter_one_published_ix
    ON test_parameter(code) WHERE status='PUBLISHED';

CREATE INDEX person_qualification_validity_ix
    ON person_qualification(person_id, qualification_code, status, valid_until);
