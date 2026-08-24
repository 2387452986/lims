CREATE TABLE project_member (
    project_id uuid NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    access_level varchar(20) NOT NULL DEFAULT 'MEMBER' CHECK (access_level IN ('MEMBER','MANAGER')),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, user_id)
);

CREATE INDEX project_member_user_ix ON project_member(user_id, project_id);
