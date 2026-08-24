CREATE OR REPLACE FUNCTION validate_project_team_membership()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  target_team_id uuid;
  member_count integer;
  leader_count integer;
BEGIN
  IF TG_TABLE_NAME = 'project_team' THEN
    target_team_id := CASE WHEN TG_OP = 'DELETE' THEN OLD.id ELSE NEW.id END;
  ELSE
    target_team_id := CASE WHEN TG_OP = 'DELETE' THEN OLD.team_id ELSE NEW.team_id END;
  END IF;

  IF EXISTS (SELECT 1 FROM project_team WHERE id=target_team_id) THEN
    SELECT count(*),count(*) FILTER (WHERE leader)
      INTO member_count,leader_count
    FROM project_team_member
    WHERE team_id=target_team_id;

    IF member_count < 1 OR leader_count <> 1 THEN
      RAISE EXCEPTION 'project team must contain members and exactly one leader';
    END IF;
  END IF;

  IF TG_TABLE_NAME = 'project_team_member' THEN
    IF TG_OP = 'UPDATE'
        AND OLD.team_id IS DISTINCT FROM NEW.team_id
        AND EXISTS (SELECT 1 FROM project_team WHERE id=OLD.team_id) THEN
      SELECT count(*),count(*) FILTER (WHERE leader)
        INTO member_count,leader_count
      FROM project_team_member
      WHERE team_id=OLD.team_id;

      IF member_count < 1 OR leader_count <> 1 THEN
        RAISE EXCEPTION 'project team must contain members and exactly one leader';
      END IF;
    END IF;
  END IF;

  IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END;
$$;
