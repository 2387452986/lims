<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import SampleLedgerView from './SampleLedgerView.vue'
import TaskQueueView from './TaskQueueView.vue'
import TestLedgerView from './TestLedgerView.vue'
import ReportQueueView from './ReportQueueView.vue'
import EquipmentView from './EquipmentView.vue'
import MasterDataView from './MasterDataView.vue'
import QualityView from './QualityView.vue'
import DetectionCatalogView from './DetectionCatalogView.vue'
import ExternalIntegrationView from './ExternalIntegrationView.vue'
import FoundationView from './FoundationView.vue'
import SigningAssetsView from './SigningAssetsView.vue'
import SignatureSchemesView from './SignatureSchemesView.vue'
import ParameterPricingView from './ParameterPricingView.vue'
import StandardsView from './StandardsView.vue'
import NumberingFlowRulesView from './NumberingFlowRulesView.vue'
import BusinessDirectoryView from './BusinessDirectoryView.vue'
import PrecommissionView from './PrecommissionView.vue'
import CommissionChangeRequestView from './CommissionChangeRequestView.vue'
import ContractFinanceView from './ContractFinanceView.vue'
import SystemExperienceView from './SystemExperienceView.vue'
import QualityLedgerView from './QualityLedgerView.vue'
import QualityDocumentView from './QualityDocumentView.vue'
import UnsupportedModuleView from './UnsupportedModuleView.vue'
import PersonnelView from './PersonnelView.vue'
import PersonnelQualificationView from './PersonnelQualificationView.vue'
import OrganizationProfileView from './OrganizationProfileView.vue'
import DataDictionaryView from './DataDictionaryView.vue'
import DocumentTemplateControlView from './DocumentTemplateControlView.vue'
import NotificationRuleView from './NotificationRuleView.vue'
import AccessControlView from './AccessControlView.vue'
import NumberingConfigurationView from './NumberingConfigurationView.vue'
import AuditLogView from './AuditLogView.vue'

const route = useRoute()
const key = computed(() => String(route.params.moduleKey))
const sampleLedgers = new Set(['samples', 'sample-ledger', 'concrete-sample-ledger', 'management-ledger',
  'retained-sample-ledger', 'sample-age-schedule'])
const taskQueues = new Set(['tasks', 'task-return-records'])
const testLedgers = new Set(['tests', 'test-ledger', 'test-return-records'])
const reportQueues = new Set(['report-editing', 'report-review', 'report-issue', 'report-seal',
  'report-archive', 'report-revision', 'report-ledger', 'report-distribution', 'report-print', 'void-reports',
  'report-change-requests', 'report-return-records', 'report-search'])
const equipment = new Set([
  'equipment', 'equipment-certificates', 'equipment-service', 'equipment-borrow',
  'equipment-usage', 'equipment-calibration', 'equipment-movements',
])
const businessDirectory = new Set(['projects', 'customers'])
const masterData = new Set(['organizations', 'departments'])
const quality = new Set([
  'nonconforming-samples', 'nonconforming-reports', 'quality-exceptions', 'data-change-requests',
  'nonconforming-concrete-ledger', 'nonconforming-field-tests', 'nonconforming-report-handling', 'nonconformance-notices',
])
const detectionConfiguration = new Set(['parameters'])
const foundation = new Set([
  'training', 'project-groups', 'titles', 'organization-qualifications',
  'metrology-qualifications', 'shelves',
])
const signingAssets = new Set(['institution-seals', 'person-signatures'])
const external = new Set([
  'iot-temperature-humidity', 'iot-video', 'iot-pressure-machine', 'iot-universal-machine', 'iot-rebound-tester',
  'static-load-monitoring', 'static-load-management', 'static-load-unknown', 'static-load-devices', 'payment-order-records',
  'identity-intent',
])
const contractFinance = new Set([
  'contracts', 'my-projects', 'contract-price-templates', 'fee-statements',
  'receipts', 'payments', 'contract-analysis',
])
const systemExperience = new Set(['desktop-themes', 'safety-briefings', 'transition-time-settings'])
const qualityLedgers = new Set(['overdue-specimen-ledger', 'due-specimen-ledger', 'rebound-assessment',
  'warning-records', 'field-test-records'])
const qualityDocuments = new Set(['stage-summary', 'comprehensive-reports', 'concrete-strength-assessment'])
const component = computed(() => external.has(key.value) ? ExternalIntegrationView
  : key.value === 'permissions' ? AccessControlView
  : ['numbering-rules', 'template-numbering'].includes(key.value) ? NumberingConfigurationView
  : key.value === 'audit' ? AuditLogView
  : systemExperience.has(key.value) ? SystemExperienceView
  : qualityDocuments.has(key.value) ? QualityDocumentView
  : key.value === 'organizations' ? OrganizationProfileView
  : key.value === 'people' ? PersonnelView
  : key.value === 'qualifications' ? PersonnelQualificationView
  : key.value === 'dictionaries' ? DataDictionaryView
  : ['report-templates', 'commission-templates', 'task-templates'].includes(key.value) ? DocumentTemplateControlView
  : key.value === 'notifications' ? NotificationRuleView
  : qualityLedgers.has(key.value) ? QualityLedgerView
  : contractFinance.has(key.value) ? ContractFinanceView
  : key.value === 'precommissions' ? PrecommissionView
  : key.value === 'commission-change-requests' ? CommissionChangeRequestView
  : key.value === 'signature-schemes' ? SignatureSchemesView
  : key.value === 'parameter-prices' ? ParameterPricingView
  : key.value === 'standards' ? StandardsView
  : key.value === 'numbering-flow-rules' ? NumberingFlowRulesView
  : signingAssets.has(key.value) ? SigningAssetsView
  : foundation.has(key.value) ? FoundationView
  : businessDirectory.has(key.value) ? BusinessDirectoryView
  : detectionConfiguration.has(key.value) ? DetectionCatalogView
  : equipment.has(key.value) ? EquipmentView
      : quality.has(key.value) ? QualityView
        : sampleLedgers.has(key.value) ? SampleLedgerView
          : taskQueues.has(key.value) ? TaskQueueView
            : testLedgers.has(key.value) ? TestLedgerView
              : reportQueues.has(key.value) ? ReportQueueView
          : masterData.has(key.value) ? MasterDataView : UnsupportedModuleView)
</script>

<template><component :is="component" :key="key" /></template>
