ALTER TABLE department
    ADD COLUMN sort_order integer NOT NULL DEFAULT 50,
    ADD COLUMN responsible_person_id uuid REFERENCES person(id),
    ADD COLUMN supervising_person_id uuid REFERENCES person(id),
    ADD COLUMN retired_at timestamptz,
    ADD COLUMN retired_reason varchar(1000),
    ADD CONSTRAINT department_sort_order_check CHECK (sort_order BETWEEN 0 AND 999999),
    ADD CONSTRAINT department_retirement_content_check CHECK (
        (active AND retired_at IS NULL AND retired_reason IS NULL)
        OR (NOT active AND retired_at IS NOT NULL AND retired_reason IS NOT NULL AND btrim(retired_reason) <> '')
    );

CREATE INDEX department_tree_ix
    ON department(organization_id, active, parent_id, sort_order, name, id);
CREATE UNIQUE INDEX department_active_sibling_name_ci_ux
    ON department(organization_id, parent_id, lower(name)) NULLS NOT DISTINCT
    WHERE active;
CREATE INDEX department_responsible_person_ix
    ON department(responsible_person_id) WHERE responsible_person_id IS NOT NULL;
CREATE INDEX department_supervising_person_ix
    ON department(supervising_person_id) WHERE supervising_person_id IS NOT NULL;

CREATE TABLE department_detection_project (
    department_id uuid NOT NULL REFERENCES department(id) ON DELETE CASCADE,
    project_node_id uuid NOT NULL REFERENCES detection_catalog_node(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (department_id, project_node_id)
);

CREATE INDEX department_detection_project_project_ix
    ON department_detection_project(project_node_id, department_id);

-- Preserve every existing assignment path when the new scope becomes authoritative.
-- Administrators can narrow these bindings explicitly after migration.
INSERT INTO department_detection_project(department_id, project_node_id)
SELECT department.id, revision.project_node_id
FROM department
CROSS JOIN detection_project_revision revision
JOIN detection_catalog_node node ON node.id=revision.project_node_id
WHERE department.active
  AND revision.status='PUBLISHED'
  AND node.node_type='PROJECT'
  AND node.status='ACTIVE'
ON CONFLICT DO NOTHING;
