-- Keep the already-applied V44 checksum stable and tighten the publish gate forward-only.
CREATE OR REPLACE FUNCTION guard_data_dictionary_revision()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  series_parent uuid;
BEGIN
  IF TG_OP='UPDATE' AND OLD.state<>'DRAFT'
     AND (NEW.name IS DISTINCT FROM OLD.name OR NEW.allows_children IS DISTINCT FROM OLD.allows_children) THEN
    RAISE EXCEPTION 'published data dictionary content is immutable';
  END IF;

  IF NEW.state='PUBLISHED' AND (TG_OP='INSERT' OR OLD.state IS DISTINCT FROM NEW.state) THEN
    SELECT parent_id INTO series_parent FROM data_dictionary_series WHERE id=NEW.series_id;
    IF series_parent IS NOT NULL AND NEW.allows_children THEN
      RAISE EXCEPTION 'second-level data dictionary cannot allow children';
    END IF;
    IF NEW.allows_children AND EXISTS (
      SELECT 1 FROM data_dictionary_value WHERE revision_id=NEW.id
    ) THEN
      RAISE EXCEPTION 'branch data dictionary cannot contain values';
    END IF;
    IF NOT NEW.allows_children AND EXISTS (
      SELECT 1 FROM data_dictionary_series child WHERE child.parent_id=NEW.series_id
    ) THEN
      RAISE EXCEPTION 'data dictionary with children must allow children';
    END IF;
    IF series_parent IS NOT NULL AND NOT EXISTS (
      SELECT 1
      FROM data_dictionary_revision parent_revision
      WHERE parent_revision.series_id=series_parent
        AND parent_revision.state='PUBLISHED'
        AND parent_revision.allows_children
    ) THEN
      RAISE EXCEPTION 'data dictionary parent is unavailable';
    END IF;
  END IF;

  IF TG_OP='UPDATE' AND OLD.state='PUBLISHED' AND NEW.state='RETIRED'
     AND NOT EXISTS (
       SELECT 1 FROM data_dictionary_revision replacement
       WHERE replacement.series_id=NEW.series_id AND replacement.id<>NEW.id
         AND replacement.state='DRAFT'
     )
     AND EXISTS (
       SELECT 1
       FROM data_dictionary_series child
       JOIN data_dictionary_revision child_revision ON child_revision.series_id=child.id
       WHERE child.parent_id=NEW.series_id
         AND child_revision.state IN ('DRAFT','PUBLISHED')
     ) THEN
    RAISE EXCEPTION 'active child dictionaries must be retired first';
  END IF;
  RETURN NEW;
END $$;
