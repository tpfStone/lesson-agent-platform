<script setup lang="ts">
import axios from "axios";
import { computed, onMounted, ref } from "vue";
import { useAuthStore } from "../stores/auth";

interface AdminUser {
  id: string;
  username: string;
  displayName: string;
  role: "USER" | "ADMIN";
  status: "ACTIVE" | "DISABLED";
  createdAt: string;
  lastLoginAt?: string | null;
  failedLoginCount: number;
  lockedUntil?: string | null;
}

interface LoginAudit {
  id: number;
  userId?: string | null;
  usernameAttempted: string;
  success: boolean;
  clientIp?: string | null;
  createdAt: string;
}

const auth = useAuthStore();
const users = ref<AdminUser[]>([]);
const audits = ref<LoginAudit[]>([]);
const loading = ref(true);
const error = ref("");
const changing = ref("");
const activeCount = computed(() => users.value.filter(user => user.status === "ACTIVE").length);

function describe(reason: unknown) {
  return axios.isAxiosError(reason)
    ? reason.response?.data?.detail || "请求未能完成，请稍后重试"
    : "请求未能完成，请稍后重试";
}

function time(value?: string | null) {
  return value ? new Date(value).toLocaleString("zh-CN", { hour12: false }) : "—";
}

async function load() {
  loading.value = true;
  error.value = "";
  try {
    const [userResponse, auditResponse] = await Promise.all([
      axios.get<AdminUser[]>("/api/admin/users"),
      axios.get<LoginAudit[]>("/api/admin/login-audits", { params: { limit: 100 } }),
    ]);
    users.value = userResponse.data;
    audits.value = auditResponse.data;
  } catch (reason) {
    error.value = describe(reason);
  } finally {
    loading.value = false;
  }
}

async function toggle(user: AdminUser) {
  const status = user.status === "ACTIVE" ? "DISABLED" : "ACTIVE";
  changing.value = user.id;
  error.value = "";
  try {
    const updated = (await axios.patch<AdminUser>(
      `/api/admin/users/${encodeURIComponent(user.id)}/status`, { status },
    )).data;
    users.value = users.value.map(item => item.id === updated.id ? updated : item);
  } catch (reason) {
    error.value = describe(reason);
  } finally {
    changing.value = "";
  }
}

onMounted(load);
</script>

<template>
  <section class="admin-page">
    <header class="page-heading"><span>系统管理</span><h1>用户与登录安全</h1><p>查看平台账户、控制使用状态，并核对最近的登录活动。</p></header>
    <div class="summary-row"><div><strong>{{ users.length }}</strong><span>账户总数</span></div><div><strong>{{ activeCount }}</strong><span>正常账户</span></div><div><strong>{{ audits.length }}</strong><span>近期登录记录</span></div></div>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <div v-if="loading" class="panel empty">正在读取账户信息……</div>
    <template v-else>
      <section class="panel"><div class="panel-title"><div><span>账户管理</span><h2>平台注册账户</h2></div><button type="button" @click="load">刷新</button></div>
        <div class="table-wrap"><table><thead><tr><th>账户</th><th>角色</th><th>状态</th><th>最近登录</th><th>安全状态</th><th>操作</th></tr></thead><tbody>
          <tr v-for="user in users" :key="user.id"><td><strong>{{ user.displayName }}</strong><small>{{ user.username }}</small></td><td>{{ user.role === 'ADMIN' ? '管理员' : '普通用户' }}</td><td><span class="status" :class="user.status.toLowerCase()">{{ user.status === 'ACTIVE' ? '正常' : '已禁用' }}</span></td><td>{{ time(user.lastLoginAt) }}</td><td>{{ user.lockedUntil ? `锁定至 ${time(user.lockedUntil)}` : user.failedLoginCount ? `${user.failedLoginCount} 次失败` : '正常' }}</td><td><button type="button" class="action" :disabled="changing === user.id || user.id === auth.user?.id" @click="toggle(user)">{{ user.id === auth.user?.id ? '当前账户' : user.status === 'ACTIVE' ? '禁用' : '启用' }}</button></td></tr>
        </tbody></table></div>
      </section>
      <section class="panel"><div class="panel-title"><div><span>安全审计</span><h2>最近登录活动</h2></div></div>
        <div class="table-wrap"><table><thead><tr><th>时间</th><th>用户名</th><th>结果</th><th>来源 IP</th></tr></thead><tbody>
          <tr v-for="audit in audits" :key="audit.id"><td>{{ time(audit.createdAt) }}</td><td>{{ audit.usernameAttempted }}</td><td><span class="status" :class="audit.success ? 'active' : 'disabled'">{{ audit.success ? '成功' : '失败' }}</span></td><td>{{ audit.clientIp || '—' }}</td></tr>
        </tbody></table></div>
      </section>
    </template>
  </section>
</template>

<style scoped>
.admin-page{display:grid;gap:22px}.page-heading span,.panel-title span{color:var(--color-primary);font-size:12px;font-weight:800}.page-heading h1{margin:7px 0 8px;font-size:32px}.page-heading p{margin:0;color:var(--color-text-secondary)}.summary-row{display:grid;grid-template-columns:repeat(3,1fr);gap:14px}.summary-row div,.panel{border:1px solid var(--color-border);border-radius:var(--radius-md);background:#fff;box-shadow:var(--shadow-subtle)}.summary-row div{display:flex;align-items:baseline;gap:10px;padding:18px 20px}.summary-row strong{color:var(--color-primary);font-size:28px}.summary-row span{color:var(--color-text-muted);font-size:13px}.panel{padding:22px}.panel.empty{text-align:center;color:var(--color-text-muted)}.panel-title{display:flex;align-items:center;justify-content:space-between;margin-bottom:16px}.panel-title h2{margin:5px 0 0;font-size:20px}.panel-title button,.action{min-height:34px;padding:0 11px;border:1px solid var(--color-primary-border);border-radius:var(--radius-sm);color:var(--color-primary);background:#fff;cursor:pointer}.action:disabled{opacity:.5;cursor:not-allowed}.table-wrap{overflow:auto}table{width:100%;border-collapse:collapse;min-width:760px}th,td{padding:12px;border-bottom:1px solid var(--color-border);text-align:left;font-size:13px}th{color:var(--color-text-muted);background:var(--color-surface-soft);font-weight:750}td strong,td small{display:block}td small{margin-top:3px;color:var(--color-text-muted)}.status{display:inline-flex;padding:4px 9px;border-radius:999px;font-size:12px;font-weight:750}.status.active{color:var(--color-primary);background:var(--color-primary-soft)}.status.disabled{color:#9d3434;background:#fff0f0}.error{margin:0;padding:11px 13px;border-radius:var(--radius-sm);color:#9d3434;background:#fff0f0}@media(max-width:680px){.summary-row{grid-template-columns:1fr}.panel{padding:16px}}
</style>
