INSERT INTO permission(code,name,category)
VALUES ('TEST_CONFIRM','复核确认试验数据','BUSINESS')
ON CONFLICT (code) DO NOTHING;

INSERT INTO permission_catalog_node(
  catalog_key,client_code,parent_key,node_type,origin,label,sort_order,leaf)
VALUES (
  'LOCAL:PC:PERMISSION:TEST_CONFIRM','PC','LOCAL:PC:CATEGORY:BUSINESS',
  'ACTION','LOCAL','复核确认试验数据',10000,true)
ON CONFLICT (catalog_key) DO NOTHING;

INSERT INTO permission_catalog_binding(catalog_key,permission_code,enforcement_state)
VALUES ('LOCAL:PC:PERMISSION:TEST_CONFIRM','TEST_CONFIRM','ENFORCED')
ON CONFLICT (catalog_key) DO UPDATE
SET permission_code=excluded.permission_code,
    enforcement_state=excluded.enforcement_state,
    blocking_reason=NULL;
