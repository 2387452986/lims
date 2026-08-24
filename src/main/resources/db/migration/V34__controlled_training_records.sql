UPDATE training_record
SET training_method = CASE
  WHEN training_method IN ('现场培训','线下培训','线下') THEN '现场授课'
  WHEN training_method IN ('线上培训','在线培训','线上') THEN '在线学习'
  ELSE training_method
END;

UPDATE training_record
SET assessment_result = CASE assessment_result
  WHEN 'QUALIFIED' THEN '合格'
  WHEN 'PASSED' THEN '通过'
  WHEN 'EXCELLENT' THEN '优秀'
  ELSE assessment_result
END
WHERE assessment_result IS NOT NULL;

ALTER TABLE training_record
  ADD CONSTRAINT training_record_method_check
    CHECK (training_method IN ('现场授课','在线学习')),
  ADD CONSTRAINT training_record_assessment_check
    CHECK (assessment_result IS NULL OR assessment_result IN ('通过','合格','优秀'));

CREATE INDEX training_record_method_date_ix
  ON training_record(organization_id,training_method,start_date DESC,id DESC);

CREATE UNIQUE INDEX training_record_certificate_file_ux
  ON training_record(certificate_file_id) WHERE certificate_file_id IS NOT NULL;

CREATE OR REPLACE FUNCTION protect_void_training_record()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.state = 'VOID' AND (
    NEW.person_id IS DISTINCT FROM OLD.person_id OR
    NEW.start_date IS DISTINCT FROM OLD.start_date OR
    NEW.end_date IS DISTINCT FROM OLD.end_date OR
    NEW.hours IS DISTINCT FROM OLD.hours OR
    NEW.organizer IS DISTINCT FROM OLD.organizer OR
    NEW.training_method IS DISTINCT FROM OLD.training_method OR
    NEW.assessment_result IS DISTINCT FROM OLD.assessment_result OR
    NEW.location IS DISTINCT FROM OLD.location OR
    NEW.content IS DISTINCT FROM OLD.content OR
    NEW.certificate_file_id IS DISTINCT FROM OLD.certificate_file_id OR
    NEW.remark IS DISTINCT FROM OLD.remark
  ) THEN
    RAISE EXCEPTION 'void training record content is immutable; restore it first';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER training_record_void_content_trg
BEFORE UPDATE ON training_record
FOR EACH ROW EXECUTE FUNCTION protect_void_training_record();
