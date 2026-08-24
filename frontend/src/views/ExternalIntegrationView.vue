<script setup lang="ts">
import { Connection, Lock, Refresh, Warning } from '@element-plus/icons-vue'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { api, errorMessage } from '../api'
import { moduleLabel } from '../navigation'
import { integrationStateLabel } from '../presentation'

interface IntegrationStatus {
  key: string; title: string; state: string; configured: boolean; adapterAvailable: boolean
  requiredSettings: string[]; blockers: string[]; safetyPolicy: string
}

const route = useRoute()
const moduleKey = computed(() => String(route.params.moduleKey))
const status = ref<IntegrationStatus>()
const loading = ref(false)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try { status.value = await api<IntegrationStatus>(`/api/external-integrations/${moduleKey.value}`) }
  catch (cause) { error.value = errorMessage(cause, '集成状态读取失败') }
  finally { loading.value = false }
}

watch(moduleKey, load)
onMounted(load)
</script>

<template>
  <section class="page integration-page" v-loading="loading">
    <header class="page-heading">
      <div><h1>{{ moduleLabel(moduleKey) }}</h1><p>协议、证书、测试环境和正式适配器全部就绪后方可启用。</p></div>
      <el-button :icon="Refresh" @click="load">检查配置</el-button>
    </header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <template v-else>
      <section class="integration-hero">
        <div class="integration-icon"><el-icon><Connection /></el-icon></div>
        <div><span>当前状态</span><h2>受控禁用</h2><p>{{ status?.safetyPolicy }}</p></div>
        <el-tag type="warning" effect="plain" size="large">{{ status ? integrationStateLabel(status.state) : '检查中' }}</el-tag>
      </section>
      <section class="integration-grid">
        <article>
          <el-icon><Lock /></el-icon><h3>配置门禁</h3>
          <p>配置只引用部署环境中的密钥，不在仓库或页面保存明文凭据。</p>
          <dl><div v-for="setting in status?.requiredSettings" :key="setting"><dt>{{ setting }}</dt><dd>由运维环境提供</dd></div></dl>
        </article>
        <article>
          <el-icon><Warning /></el-icon><h3>未满足条件</h3>
          <ul><li v-for="blocker in status?.blockers" :key="blocker">{{ blocker }}</li></ul>
        </article>
      </section>
      <el-alert title="外部条件未满足，相关业务操作暂不可用。" type="info" :closable="false" show-icon />
    </template>
  </section>
</template>
