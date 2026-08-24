<script setup lang="ts">
import { Lock, User } from '@element-plus/icons-vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { errorMessage } from '../api'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const formRef = ref<FormInstance>()
const loading = ref(false)
const form = reactive({ username: '', password: '' })
const rules: FormRules = {
  username: [{ required: true, message: '请输入登录账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入登录密码', trigger: 'blur' }],
}

async function submit() {
  if (!(await formRef.value?.validate().catch(() => false))) return
  loading.value = true
  try {
    await auth.login(form.username.trim(), form.password)
    await router.replace(String(route.query.redirect ?? '/'))
  } catch (error) {
    ElMessage.error(errorMessage(error, '登录失败，请稍后重试'))
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="login-page">
    <div class="login-ambient" aria-hidden="true">
      <img src="/brand/institution-logo.svg" alt="" />
    </div>
    <header class="login-brand-lockup">
      <img src="/brand/institution-logo.svg" alt="机构标志" />
      <div>
        <strong>澜川智检科技有限公司</strong>
        <span>试验检测信息管理系统</span>
      </div>
    </header>

    <section class="login-message" aria-label="系统介绍">
      <p class="eyebrow">检测业务协同平台</p>
      <h1>从委托受理到报告归档，<br />每一步都清晰可追溯。</h1>
      <p>人员、设备、试验数据与审批记录在同一条业务链中协同。</p>
      <div class="login-flow" aria-label="业务流程">
        <span><b>01</b> 委托受理</span>
        <span><b>02</b> 任务检测</span>
        <span><b>03</b> 审核签发</span>
        <span><b>04</b> 受控归档</span>
      </div>
    </section>

    <section class="login-panel">
      <div class="login-card">
        <div class="login-card-heading"><p class="eyebrow">欢迎回来</p><span>内部系统</span></div>
        <h2>登录工作台</h2>
        <p class="login-hint">使用系统管理员分配的账号继续</p>
        <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
          <el-form-item label="账号" prop="username">
            <el-input v-model="form.username" size="large" autocomplete="username" placeholder="请输入登录账号" :prefix-icon="User" />
          </el-form-item>
          <el-form-item label="密码" prop="password">
            <el-input v-model="form.password" size="large" type="password" autocomplete="current-password" show-password placeholder="请输入登录密码" :prefix-icon="Lock" @keyup.enter="submit" />
          </el-form-item>
          <el-button type="primary" size="large" native-type="submit" :loading="loading" class="login-submit">登录</el-button>
        </el-form>
        <div class="login-security"><span>登录保护</span><p>连续失败将临时锁定账号，高风险操作需要再次验证密码。</p></div>
      </div>
    </section>
    <footer class="login-footer"><span>内部业务系统</span><span>Asia/Shanghai</span></footer>
  </main>
</template>
