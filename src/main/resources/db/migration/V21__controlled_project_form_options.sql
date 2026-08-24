CREATE UNIQUE INDEX configuration_one_published_ux
  ON configuration_version(configuration_type, code) WHERE state='PUBLISHED';

INSERT INTO configuration_version(id, configuration_type, code, version_number, name, content_json,
  state, published_at)
SELECT '20260820-0000-4000-8000-000000000001', 'DICTIONARY', 'PROJECT_TYPE',
  COALESCE((SELECT max(version_number) FROM configuration_version
    WHERE configuration_type='DICTIONARY' AND code='PROJECT_TYPE'), 0) + 1,
  '工程类型',
  '{"values":["水库工程","水电站（含航运水电枢纽）工程","拦河闸坝工程","防洪、排涝、排水工程","灌溉工程","供水工程"],"sourceSystem":"showcase_catalog","sourcePath":"检测业务/业务管理/工程信息/添加工程","sourceCapturedAt":"2026-08-20T00:37:50Z"}'::jsonb,
  'PUBLISHED', '2026-08-20T00:37:50Z'
WHERE NOT EXISTS (SELECT 1 FROM configuration_version
  WHERE configuration_type='DICTIONARY' AND code='PROJECT_TYPE' AND state='PUBLISHED');

INSERT INTO configuration_version(id, configuration_type, code, version_number, name, content_json,
  state, published_at)
SELECT '20260820-0000-4000-8000-000000000002', 'DICTIONARY', 'PROJECT_GRADE',
  COALESCE((SELECT max(version_number) FROM configuration_version
    WHERE configuration_type='DICTIONARY' AND code='PROJECT_GRADE'), 0) + 1,
  '工程等级',
  '{"values":["重大工程","重点工程","一般工程"],"sourceSystem":"showcase_catalog","sourcePath":"检测业务/业务管理/工程信息/添加工程","sourceCapturedAt":"2026-08-20T00:37:50Z"}'::jsonb,
  'PUBLISHED', '2026-08-20T00:37:50Z'
WHERE NOT EXISTS (SELECT 1 FROM configuration_version
  WHERE configuration_type='DICTIONARY' AND code='PROJECT_GRADE' AND state='PUBLISHED');

INSERT INTO configuration_version(id, configuration_type, code, version_number, name, content_json,
  state, published_at)
SELECT '20260820-0000-4000-8000-000000000003', 'DICTIONARY', 'DEFAULT_DETECTION_CATEGORY',
  COALESCE((SELECT max(version_number) FROM configuration_version
    WHERE configuration_type='DICTIONARY' AND code='DEFAULT_DETECTION_CATEGORY'), 0) + 1,
  '默认检测类别',
  '{"values":["监理平行检测","项目法人委托检测","施工自检","委托检测","见证委托","实体检测","监督检测","见证（取）送样","见证检测","抽样检测","仲裁检测","见证复检","委托复检","对比检测"],"sourceSystem":"showcase_catalog","sourcePath":"检测业务/业务管理/工程信息/添加工程","sourceCapturedAt":"2026-08-20T00:37:50Z"}'::jsonb,
  'PUBLISHED', '2026-08-20T00:37:50Z'
WHERE NOT EXISTS (SELECT 1 FROM configuration_version
  WHERE configuration_type='DICTIONARY' AND code='DEFAULT_DETECTION_CATEGORY' AND state='PUBLISHED');

INSERT INTO reference_configuration_import_manifest(resource_type, source_system, capture_sha256,
  source_captured_at, record_count)
VALUES ('PROJECT_FORM_DICTIONARY', 'showcase_catalog',
  '4e15deb67119e82829f6cfeeb4de3fc0c78748aa1b87173b5fc26fa36dd5e45b',
  '2026-08-20T00:37:50Z', 23)
ON CONFLICT DO NOTHING;
