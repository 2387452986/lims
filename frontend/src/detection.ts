export interface CatalogNode {
  id: string
  parentId?: string
  type: 'DOMAIN' | 'CATEGORY' | 'PROJECT'
  name: string
  symbol?: string
  sortOrder: number
  status: 'ACTIVE' | 'INACTIVE' | 'HISTORICAL'
  legacy: boolean
  version: number
  projectRevisionId?: string
  revisionNumber?: number
  revisionStatus?: 'DRAFT' | 'PUBLISHED' | 'RETIRED'
  parameterCount: number
  path: string
  children: CatalogNode[]
}

export interface DetectionStandard {
  id: string
  code: string
  name: string
  status: string
}

export interface DetectionParameter {
  id: string
  projectRevisionId: string
  code: string
  name: string
  versionNumber: number
  valueType: string
  definition: Record<string, unknown>
  formula?: string
  unit?: string
  status: string
  enabled: boolean
  version: number
  sortOrder: number
  conclusion?: string
  defaultNote?: string
  pricingMethod?: string
  price?: string
  sampleName?: string
  promisedDays?: number
  sampleDescription?: string
  sampleQuantity?: string
  postTestTreatment?: string
  retentionDays?: number
  retentionQuantity?: string
  retentionNature?: string
  defaultCommissioned: boolean
  required: boolean
  remark?: string
  sourceSystem?: string
  sourceKey?: string
  standards: DetectionStandard[]
}

export interface DetectionConfiguration {
  id: string
  type: string
  code: string
  versionNumber: number
  name: string
  state: string
  role: string
}

export interface DetectionGroup {
  id: string
  code: string
  name: string
  sortOrder: number
  status: string
  version: number
  parameterIds: string[]
}

export interface DetectionMarker {
  id: string
  code: string
  name: string
  content: Record<string, unknown>
  status: string
  version: number
}

export interface DetectionSampleField {
  id: string
  code: string
  label: string
  fieldType: 'TEXT' | 'DECIMAL' | 'DATE' | 'SINGLE_SELECT' | 'MULTI_SELECT' | 'STANDARD_SELECT' | 'STANDARD_MULTI'
  required: boolean
  unit?: string
  placeholder?: string
  options: string[]
  coreBinding: 'NONE' | 'NAME' | 'DESCRIPTION' | 'QUANTITY' | 'UNIT'
  sortOrder: number
  version: number
  sourceSystem?: string
  sourceKey?: string
  standards: DetectionStandard[]
}

export interface DetectionAgeReportOption {
  value: string
  label: string
  defaultSelected: boolean
  sortOrder: number
}

export interface ProjectRevision {
  id: string
  projectNodeId: string
  projectName: string
  symbol?: string
  path: string
  revisionNumber: number
  status: 'DRAFT' | 'PUBLISHED' | 'RETIRED'
  version: number
  conclusion?: string
  defaultNote?: string
  sourceManifestSha256?: string
  sourceCapturedAt?: string
  publishedAt?: string
  allowedActions: string[]
  parameters: DetectionParameter[]
  configurations: DetectionConfiguration[]
  groups: DetectionGroup[]
  markers: DetectionMarker[]
  sampleFields: DetectionSampleField[]
  ageReportOptions: DetectionAgeReportOption[]
}

export function flattenCatalog(nodes: CatalogNode[]): CatalogNode[] {
  return nodes.flatMap((node) => [node, ...flattenCatalog(node.children ?? [])])
}

export function firstProject(nodes: CatalogNode[]): CatalogNode | undefined {
  return flattenCatalog(nodes).find((node) => node.type === 'PROJECT' && node.projectRevisionId)
}
