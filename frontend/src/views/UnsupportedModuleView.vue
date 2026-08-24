<script setup lang="ts">
import { useRoute } from 'vue-router'
const route = useRoute()
</script>

<template>
  <section class="page"><el-result icon="warning" title="未配置的业务入口" :sub-title="`模块 ${String(route.params.moduleKey)} 未在受控导航中注册`"><template #extra><el-button type="primary" @click="$router.push('/')">返回工作台</el-button></template></el-result></section>
</template>
