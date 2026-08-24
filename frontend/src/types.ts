export interface CurrentUser {
  id: string
  username: string
  displayName: string
  departmentId?: string
  departmentName: string
  roles: string[]
  permissions: string[]
  forcePasswordChange: boolean
}

export type StepState = 'COMPLETED' | 'ACTIVE' | 'PENDING' | 'BLOCKED'

export interface WorkflowStep {
  key: string
  label: string
  state: StepState
  count: number
  total: number
  date?: string
}

export interface WorkbenchData {
  quickActions: Array<{ key: string; label: string; description: string; path: string; tone: string }>
  stageCounts: Array<{ key: string; label: string; total: number; pending: number; path: string }>
  alerts: Array<{
    key: string
    label: string
    total: number
    warning: number
    description: string
    path: string
    severity: 'NORMAL' | 'WARNING'
  }>
  pendingCommissions: Array<{
    id: string
    number: string
    projectName: string
    customerName: string
    detectionProjects: string
    state: string
    flowStatus: string
    dueOn: string
  }>
  pendingReports: Array<{
    id: string
    commissionId: string
    number: string
    title: string
    commissionNumber: string
    projectName: string
    state: string
    actionLabel: string
    path: string
    updatedAt: string
  }>
  roleTodos: Array<{
    key: string
    label: string
    total: number
    path: string
    items: Array<{
      id: string
      businessNumber: string
      title: string
      secondary: string
      state: string
      stateLabel: string
      allowedAction: string
      path: string
      dueAt: string
    }>
  }>
  notifications: Array<{
    id: string
    category: string
    title: string
    body: string
    resourceType?: string
    resourceId?: string
    read: boolean
    version: number
    createdAt: string
  }>
  recentEvents: Array<{ id: string; title: string; resourceType: string; actor: string; occurredAt: string; comment?: string }>
  spotlightCommission: {
    id: string
    number: string
    projectName: string
    customerName: string
    commissionedOn: string
    dueOn: string
    contact: string
  } | null
  steps: WorkflowStep[]
  summary: { total: number; completed: number; active: number; pending: number; blocked: number }
  evidence: Array<{ name: string; status: 'COMPLETE' | 'PARTIAL' | 'MISSING'; collected: number; required: number }>
  tasks: Array<{ id: string; project: string; sampleNumber: string; assignee: string; dueOn: string; status: string }>
  timeline: Array<{ title: string; actor: string; occurredAt: string; active?: boolean }>
  transitionWarnings: Array<{
    stageCode: string
    stageLabel: string
    resourceType: string
    resourceId: string
    businessNumber: string
    summary: string
    deadline: string
    status: 'OVERDUE' | 'DUE_SOON'
    overdueDays: number
  }>
}

export interface ModulePageData {
  key: string
  title: string
  description: string
  columns: Array<{ key: string; label: string; width?: number }>
  rows: Array<Record<string, string | number | boolean | null>>
  total: number
  permissions: { create: boolean; edit: boolean; export: boolean }
}
