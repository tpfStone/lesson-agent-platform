<script setup lang="ts">
import axios from "axios";
import { ref } from "vue";
import { useAuthStore } from "../stores/auth";

const auth = useAuthStore();
const displayName = ref(auth.user?.displayName || "");
const currentPassword = ref("");
const newPassword = ref("");
const confirmPassword = ref("");
const profileMessage = ref("");
const passwordMessage = ref("");
const profileBusy = ref(false);
const passwordBusy = ref(false);

function describe(reason: unknown) {
  return axios.isAxiosError(reason)
    ? reason.response?.data?.detail || "请求未能完成，请稍后重试"
    : "请求未能完成，请稍后重试";
}

async function saveProfile() {
  profileMessage.value = "";
  profileBusy.value = true;
  try {
    await auth.updateProfile(displayName.value);
    profileMessage.value = "显示名称已更新";
  } catch (reason) {
    profileMessage.value = describe(reason);
  } finally {
    profileBusy.value = false;
  }
}

async function savePassword() {
  passwordMessage.value = "";
  if (newPassword.value !== confirmPassword.value) {
    passwordMessage.value = "两次输入的新密码不一致";
    return;
  }
  passwordBusy.value = true;
  try {
    await auth.changePassword(currentPassword.value, newPassword.value);
    currentPassword.value = "";
    newPassword.value = "";
    confirmPassword.value = "";
    passwordMessage.value = "密码已更新，下次登录请使用新密码";
  } catch (reason) {
    passwordMessage.value = describe(reason);
  } finally {
    passwordBusy.value = false;
  }
}
</script>

<template>
  <section class="settings-page">
    <header class="page-heading"><span>账户设置</span><h1>个人资料与密码</h1><p>管理当前账户的显示名称和登录密码。</p></header>
    <div class="settings-grid">
      <form class="settings-card" @submit.prevent="saveProfile">
        <div><span>个人资料</span><h2>基本信息</h2></div>
        <label>用户名<input :value="auth.user?.username" disabled /></label>
        <label>显示名称<input v-model.trim="displayName" required maxlength="100" autocomplete="name" /></label>
        <p v-if="profileMessage" class="message" role="status">{{ profileMessage }}</p>
        <button type="submit" :disabled="profileBusy">{{ profileBusy ? "正在保存…" : "保存资料" }}</button>
      </form>
      <form class="settings-card" @submit.prevent="savePassword">
        <div><span>登录安全</span><h2>修改密码</h2></div>
        <label>当前密码<input v-model="currentPassword" type="password" required autocomplete="current-password" /></label>
        <label>新密码<input v-model="newPassword" type="password" required minlength="8" maxlength="72" autocomplete="new-password" /></label>
        <label>确认新密码<input v-model="confirmPassword" type="password" required minlength="8" maxlength="72" autocomplete="new-password" /></label>
        <p v-if="passwordMessage" class="message" role="status">{{ passwordMessage }}</p>
        <button type="submit" :disabled="passwordBusy">{{ passwordBusy ? "正在保存…" : "更新密码" }}</button>
      </form>
    </div>
  </section>
</template>

<style scoped>
.settings-page{display:grid;gap:24px}.page-heading span,.settings-card span{color:var(--color-primary);font-size:12px;font-weight:800}.page-heading h1{margin:7px 0 8px;font-size:32px}.page-heading p{margin:0;color:var(--color-text-secondary)}.settings-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:20px}.settings-card{display:grid;align-content:start;gap:16px;padding:26px;border:1px solid var(--color-border);border-radius:var(--radius-md);background:#fff;box-shadow:var(--shadow-subtle)}.settings-card h2{margin:6px 0 0;font-size:21px}.settings-card label{display:grid;gap:7px;color:var(--color-text-secondary);font-size:13px;font-weight:700}.settings-card input{height:44px;padding:0 12px;border:1px solid var(--color-border);border-radius:var(--radius-sm);font:inherit}.settings-card input:focus{outline:3px solid var(--color-primary-soft);border-color:var(--color-primary)}.settings-card input:disabled{color:var(--color-text-muted);background:var(--color-surface-soft)}.settings-card button{justify-self:start;min-height:42px;padding:0 18px;border:0;border-radius:var(--radius-md);color:#fff;background:var(--color-primary);font-weight:750;cursor:pointer}.settings-card button:disabled{opacity:.55}.message{margin:0;padding:10px 12px;border-radius:var(--radius-sm);color:var(--color-primary);background:var(--color-primary-soft);font-size:13px}@media(max-width:760px){.settings-grid{grid-template-columns:1fr}}
</style>
