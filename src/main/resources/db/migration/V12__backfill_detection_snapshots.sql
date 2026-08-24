-- Existing commissions and tasks keep their original flat parameter identity through a historical snapshot.
INSERT INTO commission_detection_selection(
    id, commission_id, project_revision_id, project_name_snapshot, project_symbol_snapshot,
    revision_number_snapshot, sort_order, created_at)
SELECT md5('legacy-detection-selection:' || c.id::text)::uuid,
       c.id,
       '00000000-0000-0000-0000-00000000d004',
       '既有平铺参数',
       NULL,
       1,
       1,
       c.created_at
FROM commission c
ON CONFLICT (commission_id, project_revision_id) DO NOTHING;

INSERT INTO commission_sample_group(
    id, commission_id, client_key, name, description, quantity, unit, sort_order, snapshot_json)
SELECT md5('legacy-sample-group:' || s.id::text)::uuid,
       s.commission_id,
       'legacy:' || s.id::text,
       s.name,
       s.specification,
       s.received_quantity,
       s.unit,
       row_number() OVER (PARTITION BY s.commission_id ORDER BY s.created_at, s.id),
       jsonb_build_object(
           'source', 'pre-detection-catalog-backfill',
           'sampleId', s.id,
           'sampleNumber', s.number,
           'state', s.state)
FROM sample s
ON CONFLICT (commission_id, client_key) DO NOTHING;

UPDATE sample s
SET sample_group_id=md5('legacy-sample-group:' || s.id::text)::uuid
WHERE sample_group_id IS NULL;

INSERT INTO commission_parameter_selection(
    id, detection_selection_id, parameter_id, sample_group_id, parameter_code_snapshot,
    parameter_name_snapshot, unit_snapshot, required_snapshot, default_snapshot, sort_order, snapshot_json)
SELECT md5('legacy-parameter-selection:' || t.commission_id::text || ':' || t.parameter_id::text || ':' || t.sample_id::text)::uuid,
       md5('legacy-detection-selection:' || t.commission_id::text)::uuid,
       t.parameter_id,
       md5('legacy-sample-group:' || t.sample_id::text)::uuid,
       p.code,
       p.name,
       p.unit,
       p.required,
       p.default_commissioned,
       row_number() OVER (PARTITION BY t.commission_id ORDER BY t.created_at, t.id),
       jsonb_build_object(
           'source', 'pre-detection-catalog-backfill',
           'parameterId', p.id,
           'code', p.code,
           'name', p.name,
           'versionNumber', p.version_number,
           'valueType', p.value_type,
           'definition', p.definition_json,
           'formula', p.formula,
           'unit', p.unit)
FROM (
    SELECT DISTINCT ON (commission_id, parameter_id, sample_id)
           commission_id, parameter_id, sample_id, created_at, id
    FROM test_task
    ORDER BY commission_id, parameter_id, sample_id, created_at, id
) t
JOIN test_parameter p ON p.id=t.parameter_id
ON CONFLICT (detection_selection_id, parameter_id, sample_group_id) DO NOTHING;

-- Commissions that have not reached task creation still retain a selectable historical parameter snapshot.
INSERT INTO commission_parameter_selection(
    id, detection_selection_id, parameter_id, sample_group_id, parameter_code_snapshot,
    parameter_name_snapshot, unit_snapshot, required_snapshot, default_snapshot, sort_order, snapshot_json)
SELECT md5('legacy-parameter-selection:' || c.id::text || ':' || p.id::text || ':none')::uuid,
       md5('legacy-detection-selection:' || c.id::text)::uuid,
       p.id,
       NULL,
       p.code,
       p.name,
       p.unit,
       p.required,
       p.default_commissioned,
       1,
       jsonb_build_object(
           'source', 'pre-detection-catalog-backfill',
           'parameterId', p.id,
           'code', p.code,
           'name', p.name,
           'versionNumber', p.version_number,
           'valueType', p.value_type,
           'definition', p.definition_json,
           'formula', p.formula,
           'unit', p.unit)
FROM commission c
CROSS JOIN LATERAL (
    SELECT id, code, name, unit, required, default_commissioned, version_number,
           value_type, definition_json, formula
    FROM test_parameter
    WHERE project_revision_id='00000000-0000-0000-0000-00000000d004' AND status='PUBLISHED'
    ORDER BY sort_order, created_at
    LIMIT 1
) p
WHERE NOT EXISTS (SELECT 1 FROM test_task t WHERE t.commission_id=c.id)
ON CONFLICT (detection_selection_id, parameter_id, sample_group_id) DO NOTHING;

UPDATE test_task t
SET selection_id=md5('legacy-parameter-selection:' || t.commission_id::text || ':' || t.parameter_id::text || ':' || t.sample_id::text)::uuid
WHERE selection_id IS NULL;

UPDATE commission
SET selection_frozen_at=COALESCE(selection_frozen_at, updated_at)
WHERE selection_frozen_at IS NULL;
