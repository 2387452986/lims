-- One immutable content object may be referenced by multiple business purposes and owners.
-- Keep the SHA-256 path de-duplicated while isolating authorization metadata per reference.
ALTER TABLE stored_file DROP CONSTRAINT stored_file_sha256_size_bytes_key;
CREATE INDEX stored_file_content_ix ON stored_file(sha256, size_bytes);
