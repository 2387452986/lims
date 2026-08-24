ALTER TABLE report_approval
    ADD COLUMN signing_asset_id uuid REFERENCES signing_asset(id);

ALTER TABLE report_version
    ADD COLUMN final_pdf_at timestamptz;
