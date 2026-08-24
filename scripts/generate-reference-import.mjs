import { createHash } from 'node:crypto'
import { readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'

const root = resolve(import.meta.dirname, '..')
const auditDir = resolve(root, 'audit/reference-parity/2026-08-19')
const catalogPath = resolve(auditDir, 'detection-catalog-visible.json')
const manifestPath = resolve(auditDir, 'detection-parameters-visible-verified.json')
const detailsPath = resolve(auditDir, 'detection-parameter-details-visible-verified.json')
const outputPath = resolve(root, 'src/main/resources/db/migration/V11__import_reference_detection_configuration.sql')

const [catalogRaw, manifestRaw, detailsRaw] = await Promise.all([
  readFile(catalogPath, 'utf8'), readFile(manifestPath, 'utf8'), readFile(detailsPath, 'utf8'),
])
const catalog = JSON.parse(catalogRaw)
const manifest = JSON.parse(manifestRaw)
const details = JSON.parse(detailsRaw)
const sha256 = (value) => createHash('sha256').update(value).digest('hex')
const manifestSha = sha256(manifestRaw)
const detailsSha = sha256(detailsRaw)

if (manifestSha !== '2cc35e1f9b308f3f335f1ca1259f4063cf9a9ab3607f7b2c526d45c96d2c8f70') {
  throw new Error(`Unexpected parameter manifest SHA-256: ${manifestSha}`)
}
if (detailsSha !== 'cf72ae8879edfd5027d4fa59ad3efe6ca699626eb437f2e07b1f0a6bc94e3a89') {
  throw new Error(`Unexpected parameter detail SHA-256: ${detailsSha}`)
}
if (catalog.nodes.length !== 513 || manifest.projects.length !== 448 || details.records.length !== 3080 || details.errors.length !== 0) {
  throw new Error('Reference capture counts do not match the frozen audit baseline')
}

function uuid(seed) {
  const bytes = createHash('sha256').update(seed).digest().subarray(0, 16)
  bytes[6] = (bytes[6] & 0x0f) | 0x50
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = bytes.toString('hex')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

const sqlString = (value) => `'${String(value).replaceAll("'", "''")}'`
const nullable = (value) => value === null || value === undefined || value === '' ? 'NULL' : sqlString(value)
const numeric = (value) => value === null || value === undefined || value === '' ? 'NULL' : String(value)
const integer = (value) => value === null || value === undefined || value === '' ? 'NULL' : String(Number.parseInt(value, 10))
const json = (value) => `${sqlString(JSON.stringify(value))}::jsonb`
const legacyName = (value) => /[（(]老[)）]/.test(value)
const chunks = []
const addInsert = (table, columns, rows, conflict) => {
  const chunkSize = 250
  for (let offset = 0; offset < rows.length; offset += chunkSize) {
    const values = rows.slice(offset, offset + chunkSize).map((row) => `  (${row.join(', ')})`).join(',\n')
    chunks.push(`INSERT INTO ${table} (${columns.join(', ')}) VALUES\n${values}\n${conflict};`)
  }
}

const projectsByOrder = new Map(manifest.projects.map((project) => [project.sourceOrder, project]))
const detailByKey = new Map(details.records.map((record) => [record.parameterSourceKey, record]))
const expectedKeys = manifest.projects.flatMap((project) => project.parameterNames.map((parameterName, index) => ({
  key: `${project.sourceRecordId ?? project.sourceOrder}:${index + 1}`,
  projectName: project.projectName,
  parameterName,
})))
if (detailByKey.size !== expectedKeys.length || expectedKeys.some((item) => {
  const detail = detailByKey.get(item.key)
  return !detail || detail.projectName !== item.projectName || detail.parameterName !== item.parameterName
})) {
  throw new Error('Reference parameter details do not match the verified manifest')
}

const stack = new Map()
const catalogRows = []
const projectRevisionRows = []
const projectRevisionBySourceOrder = new Map()
for (const source of catalog.nodes) {
  const parent = source.depth === 1 ? null : stack.get(source.depth - 1)
  if (source.depth > 1 && !parent) throw new Error(`Missing parent for catalog source order ${source.sourceOrder}`)
  const project = source.depth === 3 ? projectsByOrder.get(source.sourceOrder) : undefined
  if (source.depth === 3 && !project) throw new Error(`Missing project capture for catalog source order ${source.sourceOrder}`)
  const id = uuid(`showcase_catalog:catalog:${source.sourceOrder}`)
  const inheritedLegacy = Boolean(source.legacy || parent?.legacy || legacyName(source.name))
  const node = { id, legacy: inheritedLegacy }
  stack.set(source.depth, node)
  for (const depth of [...stack.keys()]) if (depth > source.depth) stack.delete(depth)
  const type = source.depth === 1 ? 'DOMAIN' : source.depth === 2 ? 'CATEGORY' : 'PROJECT'
  const status = inheritedLegacy ? 'HISTORICAL' : 'ACTIVE'
  catalogRows.push([
    sqlString(id), nullable(parent?.id), sqlString(type), sqlString('showcase_catalog'), sqlString(`catalog:${source.sourceOrder}`),
    nullable(project?.sourceRecordId), sqlString(source.name), nullable(project?.symbol), String(source.sourceOrder),
    sqlString(status), String(inheritedLegacy),
  ])
  if (project) {
    const revisionId = uuid(`showcase_catalog:project:${project.sourceRecordId}:revision:1`)
    projectRevisionBySourceOrder.set(source.sourceOrder, revisionId)
    projectRevisionRows.push([
      sqlString(revisionId), sqlString(id), '1', sqlString('PUBLISHED'), sqlString(manifestSha),
      sqlString(manifest.capturedAt), sqlString(manifest.capturedAt),
    ])
  }
}
if (projectRevisionRows.length !== 448) throw new Error('Generated project revision count is not 448')

const standardByValue = new Map()
for (const record of details.records) {
  for (const standard of record.standards) {
    if (!standardByValue.has(standard)) standardByValue.set(standard, uuid(`showcase_catalog:standard:${standard}`))
  }
}
const standardRows = [...standardByValue.entries()].map(([standard, id]) => [
  sqlString(id), sqlString('showcase_catalog'), sqlString(standard), sqlString(standard), sqlString(standard), sqlString('ACTIVE'),
])

const parameterRows = []
const parameterStandardRows = []
for (const project of manifest.projects) {
  const revisionId = projectRevisionBySourceOrder.get(project.sourceOrder)
  for (let index = 0; index < project.parameterNames.length; index += 1) {
    const occurrence = index + 1
    const sourceKey = `${project.sourceRecordId ?? project.sourceOrder}:${occurrence}`
    const record = detailByKey.get(sourceKey)
    const id = uuid(`showcase_catalog:parameter:${sourceKey}`)
    const definition = {
      captureStatus: 'not_exposed_in_reference_editor',
      fields: [],
      source: 'showcase_catalog-visible-ui',
    }
    parameterRows.push([
      sqlString(id), sqlString(`VN-${project.sourceRecordId ?? project.sourceOrder}-${occurrence}`), sqlString(record.parameterName),
      '1', sqlString('UNSPECIFIED'), json(definition), 'NULL', nullable(record.unit), sqlString('PUBLISHED'),
      sqlString(record.capturedAt), '0', sqlString(record.capturedAt), 'NULL', sqlString(revisionId), sqlString('showcase_catalog'),
      sqlString(sourceKey), 'NULL', String(occurrence), String(occurrence), nullable(record.conclusion),
      nullable(record.defaultNote), nullable(record.pricingMethod), numeric(record.price), nullable(record.sampleName),
      integer(record.promisedDays), nullable(record.sampleDescription), numeric(record.sampleQuantity),
      nullable(record.postTestTreatment), integer(record.retentionDays), numeric(record.retentionQuantity),
      nullable(record.retentionNature), String(record.defaultCommissioned), String(record.required),
      String(record.status === '启用'), nullable(record.remark),
    ])
    record.standards.forEach((standard, standardIndex) => parameterStandardRows.push([
      sqlString(id), sqlString(standardByValue.get(standard)), String(standardIndex + 1),
    ]))
  }
}

chunks.push('-- Generated only from the two frozen, current-account-visible verified captures. Do not hand edit.')
chunks.push(`INSERT INTO reference_import_manifest(source_system, manifest_sha256, details_sha256, source_captured_at,
  catalog_node_count, project_count, parameter_count, source_error_count)
VALUES ('showcase_catalog', '${manifestSha}', '${detailsSha}', ${sqlString(manifest.capturedAt)}, 513, 448, 3080, 0)
ON CONFLICT (source_system, manifest_sha256) DO UPDATE SET
  details_sha256=EXCLUDED.details_sha256, source_captured_at=EXCLUDED.source_captured_at,
  catalog_node_count=EXCLUDED.catalog_node_count, project_count=EXCLUDED.project_count,
  parameter_count=EXCLUDED.parameter_count, source_error_count=EXCLUDED.source_error_count;`)
addInsert('detection_catalog_node', [
  'id','parent_id','node_type','source_system','source_key','source_record_id','name','symbol','sort_order','status','legacy',
], catalogRows, `ON CONFLICT (id) DO UPDATE SET parent_id=EXCLUDED.parent_id, name=EXCLUDED.name,
  symbol=EXCLUDED.symbol, sort_order=EXCLUDED.sort_order, status=EXCLUDED.status, legacy=EXCLUDED.legacy, updated_at=now()`)
addInsert('detection_project_revision', [
  'id','project_node_id','revision_number','status','source_manifest_sha256','source_captured_at','published_at',
], projectRevisionRows, `ON CONFLICT (id) DO UPDATE SET source_manifest_sha256=EXCLUDED.source_manifest_sha256,
  source_captured_at=EXCLUDED.source_captured_at, updated_at=now()`)
addInsert('detection_standard', ['id','source_system','source_key','code','name','status'], standardRows,
  `ON CONFLICT (id) DO UPDATE SET code=EXCLUDED.code, name=EXCLUDED.name, status=EXCLUDED.status, updated_at=now()`)
addInsert('test_parameter', [
  'id','code','name','version_number','value_type','definition_json','formula','unit','status','published_at','lock_version',
  'updated_at','created_by','project_revision_id','source_system','source_key','source_record_id','source_occurrence','sort_order',
  'conclusion','default_note','pricing_method','price','sample_name','promised_days','sample_description','sample_quantity',
  'post_test_treatment','retention_days','retention_quantity','retention_nature','default_commissioned','required','enabled','remark',
], parameterRows, `ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, definition_json=EXCLUDED.definition_json,
  unit=EXCLUDED.unit, project_revision_id=EXCLUDED.project_revision_id, sort_order=EXCLUDED.sort_order,
  conclusion=EXCLUDED.conclusion, default_note=EXCLUDED.default_note, pricing_method=EXCLUDED.pricing_method,
  price=EXCLUDED.price, sample_name=EXCLUDED.sample_name, promised_days=EXCLUDED.promised_days,
  sample_description=EXCLUDED.sample_description, sample_quantity=EXCLUDED.sample_quantity,
  post_test_treatment=EXCLUDED.post_test_treatment, retention_days=EXCLUDED.retention_days,
  retention_quantity=EXCLUDED.retention_quantity, retention_nature=EXCLUDED.retention_nature,
  default_commissioned=EXCLUDED.default_commissioned, required=EXCLUDED.required, enabled=EXCLUDED.enabled,
  remark=EXCLUDED.remark, updated_at=EXCLUDED.updated_at`)
addInsert('test_parameter_standard', ['parameter_id','standard_id','sort_order'], parameterStandardRows,
  'ON CONFLICT (parameter_id, standard_id) DO UPDATE SET sort_order=EXCLUDED.sort_order')

await writeFile(outputPath, `${chunks.join('\n\n')}\n`, 'utf8')
console.log(JSON.stringify({
  outputPath,
  manifestSha,
  detailsSha,
  catalogNodes: catalogRows.length,
  projects: projectRevisionRows.length,
  parameters: parameterRows.length,
  standards: standardRows.length,
  parameterStandards: parameterStandardRows.length,
}, null, 2))
