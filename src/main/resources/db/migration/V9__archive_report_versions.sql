ALTER TABLE archive_package
    ADD COLUMN report_version_id uuid REFERENCES report_version(id);

UPDATE archive_package archive
SET report_version_id = (
    SELECT version.id
    FROM report_version version
    WHERE version.report_id = archive.report_id
    ORDER BY (version.signed_at IS NOT NULL) DESC, version.revision DESC
    LIMIT 1
)
WHERE archive.report_version_id IS NULL;

ALTER TABLE archive_package
    ALTER COLUMN report_version_id SET NOT NULL;

ALTER TABLE archive_package
    DROP CONSTRAINT IF EXISTS archive_package_report_id_key;

ALTER TABLE archive_package
    ADD CONSTRAINT archive_package_report_version_key UNIQUE (report_version_id);

CREATE INDEX archive_package_report_id_idx ON archive_package(report_id);
