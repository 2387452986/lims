ALTER TABLE project_team_member
  ADD CONSTRAINT project_team_member_duty_check CHECK (btrim(duty) <> ''),
  ADD CONSTRAINT project_team_member_sort_order_check CHECK (sort_order >= 0);

CREATE INDEX project_team_member_person_ix
  ON project_team_member(person_id,team_id);

CREATE INDEX project_team_state_name_ix
  ON project_team(organization_id,state,name,id);

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

  IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END;
$$;

CREATE CONSTRAINT TRIGGER project_team_membership_team_trg
AFTER INSERT OR UPDATE ON project_team
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION validate_project_team_membership();

CREATE CONSTRAINT TRIGGER project_team_membership_member_trg
AFTER INSERT OR UPDATE OR DELETE ON project_team_member
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION validate_project_team_membership();
