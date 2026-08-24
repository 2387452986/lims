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
