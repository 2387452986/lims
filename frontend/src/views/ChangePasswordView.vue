<script setup lang="ts">
import { ArrowLeft, Lock } from '@element-plus/icons-vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { errorMessage } from '../api'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const formRef = ref<FormInstance>()
const loading = ref(false)
const form = reactive({ currentPassword: '', newPassword: '', confirmation: '' })
const returnPath = computed(() => {
  const candidate = typeof route.query.returnTo === 'string' ? route.query.returnTo : '/'
  return candidate.startsWith('/') && !candidate.startsWith('//')
    && !['/login', '/change-password'].includes(candidate) ? candidate : '/'
})
const rules: FormRules = {
  currentPassword: [{ required: true, message: '请输入当前密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 12, max: 128, message: '新密码须为12至128位', trigger: 'blur' },
  ],
  confirmation: [{
    validator: (_rule, value, callback) => value === form.newPassword ? callback() : callback(new Error('两次输入的新密码不一致')),
    trigger: 'blur',
  }],
}

async function submit() {
  if (!(await formRef.value?.validate().catch(() => false))) return
  loading.value = true
  try {
    await auth.changePassword(form.currentPassword, form.newPassword)
    ElMessage.success('密码已更新，请重新登录')
    await router.replace('/login')
  } catch (error) {
    ElMessage.error(errorMessage(error, '密码修改失败'))
  } finally {
    loading.value = false
  }
}

async function returnToSystem() {
  if (auth.user?.forcePasswordChange) return
  await router.replace(returnPath.value)
}
</script>

<template>
  <main class="password-page">
    <section class="password-card">
      <img src="/brand/institution-logo.svg" alt="机构标志" />
      <p class="eyebrow">账号安全</p>
      <h1>{{ auth.user?.forcePasswordChange ? '首次登录，请设置新密码' : '修改登录密码' }}</h1>
      <p>完成修改后，当前会话将撤销，需要使用新密码重新登录。</p>
      <el-alert title="密码须为12至128位，包含大小写字母、数字和特殊字符。" type="info" show-icon :closable="false" />
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
        <el-form-item label="当前密码" prop="currentPassword"><el-input v-model="form.currentPassword" type="password" show-password :prefix-icon="Lock" /></el-form-item>
        <el-form-item label="新密码" prop="newPassword"><el-input v-model="form.newPassword" type="password" show-password :prefix-icon="Lock" /></el-form-item>
        <el-form-item label="再次输入新密码" prop="confirmation"><el-input v-model="form.confirmation" type="password" show-password :prefix-icon="Lock" @keyup.enter="submit" /></el-form-item>
        <div class="password-actions">
          <el-button v-if="!auth.user?.forcePasswordChange" :icon="ArrowLeft" @click="returnToSystem">返回系统</el-button>
          <el-button class="login-submit" type="primary" native-type="submit" :loading="loading">保存并重新登录</el-button>
        </div>
      </el-form>
    </section>
  </main>
</template>
