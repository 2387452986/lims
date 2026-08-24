ALTER TABLE sample_shelf
  ADD COLUMN parent_id uuid REFERENCES sample_shelf(id),
  ADD CONSTRAINT sample_shelf_number_nonblank_check
    CHECK (btrim(shelf_number) <> '') NOT VALID,
  ADD CONSTRAINT sample_shelf_parent_not_self_check
    CHECK (parent_id IS NULL OR parent_id <> id) NOT VALID;

ALTER TABLE sample_shelf VALIDATE CONSTRAINT sample_shelf_number_nonblank_check;
ALTER TABLE sample_shelf VALIDATE CONSTRAINT sample_shelf_parent_not_self_check;

ALTER TABLE sample_shelf DROP CONSTRAINT sample_shelf_organization_id_shelf_number_key;

CREATE UNIQUE INDEX sample_shelf_number_ci_ux
  ON sample_shelf(organization_id, upper(btrim(shelf_number)));

DROP INDEX sample_shelf_org_order_ix;

CREATE INDEX sample_shelf_root_cursor_ix
  ON sample_shelf(organization_id, updated_at DESC, id DESC)
  WHERE parent_id IS NULL;

CREATE INDEX sample_shelf_tree_order_ix
  ON sample_shelf(organization_id, parent_id, active DESC, sort_order, upper(shelf_number), id);

CREATE OR REPLACE FUNCTION guard_sample_shelf_hierarchy()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  parent_organization uuid;
  grandparent_id uuid;
  parent_active boolean;
BEGIN
  IF TG_OP = 'UPDATE' AND NEW.parent_id IS DISTINCT FROM OLD.parent_id THEN
    RAISE EXCEPTION 'sample shelf parent is immutable';
  END IF;

  IF NEW.parent_id IS NOT NULL THEN
    SELECT organization_id, parent_id, active
      INTO parent_organization, grandparent_id, parent_active
      FROM sample_shelf
      WHERE id = NEW.parent_id;

    IF NOT FOUND THEN
      RAISE EXCEPTION 'sample shelf parent does not exist';
    END IF;
    IF parent_organization <> NEW.organization_id THEN
      RAISE EXCEPTION 'sample shelf parent belongs to another organization';
    END IF;
    IF grandparent_id IS NOT NULL THEN
      RAISE EXCEPTION 'sample shelf hierarchy supports exactly two levels';
    END IF;
    IF NEW.active AND NOT parent_active THEN
      RAISE EXCEPTION 'active sample shelf child requires an active parent';
    END IF;
  END IF;

  IF TG_OP = 'UPDATE' AND OLD.parent_id IS NULL AND OLD.active AND NOT NEW.active
     AND EXISTS (SELECT 1 FROM sample_shelf child WHERE child.parent_id = OLD.id AND child.active) THEN
    RAISE EXCEPTION 'sample shelf with active children cannot be deactivated';
  END IF;

  RETURN NEW;
END;
$$;

CREATE TRIGGER sample_shelf_hierarchy_guard_trg
BEFORE INSERT OR UPDATE ON sample_shelf
FOR EACH ROW EXECUTE FUNCTION guard_sample_shelf_hierarchy();

COMMENT ON COLUMN sample_shelf.parent_id
  IS 'Optional root shelf; exactly one child level is allowed and the parent is immutable after creation.';
COMMENT ON FUNCTION guard_sample_shelf_hierarchy()
  IS 'Enforces organization ownership, two-level depth, immutable parent and active parent/child consistency.';
