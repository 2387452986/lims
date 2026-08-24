-- Generic DEV rows made empty modules look populated without carrying real business meaning.
-- Keep the production CRUD/audit contract, but remove only the deterministic fixture rows.
DELETE FROM catalog_entry
WHERE code LIKE 'DEV-%'
  AND name LIKE '%受控样例'
  AND attributes ->> 'source' = 'development fixture';
