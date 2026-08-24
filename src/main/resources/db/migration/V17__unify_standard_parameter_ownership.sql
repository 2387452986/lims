-- test_parameter_standard already owns the version-specific standard/parameter relationship.
-- Preserve any V16 writes, then remove the duplicate table so both directions share one source of truth.
WITH missing AS (
  SELECT scope.parameter_id,
         scope.standard_id,
         COALESCE((
           SELECT max(existing.sort_order) + 1
           FROM test_parameter_standard existing
           WHERE existing.parameter_id = scope.parameter_id
         ), 0)
         + row_number() OVER (PARTITION BY scope.parameter_id ORDER BY scope.standard_id) - 1 AS sort_order
  FROM detection_standard_parameter_scope scope
  LEFT JOIN test_parameter_standard existing
    ON existing.parameter_id = scope.parameter_id
   AND existing.standard_id = scope.standard_id
  WHERE existing.parameter_id IS NULL
)
INSERT INTO test_parameter_standard(parameter_id, standard_id, sort_order)
SELECT parameter_id, standard_id, sort_order::integer
FROM missing
ON CONFLICT (parameter_id, standard_id) DO NOTHING;

DROP TABLE detection_standard_parameter_scope;
