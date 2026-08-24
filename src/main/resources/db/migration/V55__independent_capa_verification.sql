INSERT INTO permission(code,name,category)
VALUES ('CAPA_VERIFY','独立验证纠正措施','QUALITY')
ON CONFLICT (code) DO NOTHING;

INSERT INTO permission_catalog_node(
  catalog_key,client_code,parent_key,node_type,origin,label,sort_order,leaf)
VALUES (
  'LOCAL:PC:PERMISSION:CAPA_VERIFY','PC','LOCAL:PC:CATEGORY:QUALITY',
  'ACTION','LOCAL','独立验证纠正措施',10010,true)
ON CONFLICT (catalog_key) DO NOTHING;

INSERT INTO permission_catalog_binding(catalog_key,permission_code,enforcement_state)
VALUES ('LOCAL:PC:PERMISSION:CAPA_VERIFY','CAPA_VERIFY','ENFORCED')
ON CONFLICT (catalog_key) DO UPDATE
SET permission_code=excluded.permission_code,
    enforcement_state=excluded.enforcement_state,
    blocking_reason=NULL;
