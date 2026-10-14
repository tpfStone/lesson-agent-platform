<script setup lang="ts">
import axios from "axios";
import { computed, ref } from "vue";
import { RouterLink, useRoute, useRouter } from "vue-router";
import { useAuthStore } from "../stores/auth";

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const registering = computed(() => route.name === "register");
const username = ref("");
const displayName = ref("");
const password = ref("");
const confirmPassword = ref("");
const error = ref("");

function message(reason: unknown) {
  if (axios.isAxiosError(reason)) {
    return reason.response?.data?.detail || "请求未能完成，请稍后重试";
  }
  return reason instanceof Error ? reason.message : "请求未能完成，请稍后重试";
}

async function submit() {
  error.value = "";
  if (registering.value && password.value !== confirmPassword.value) {
    error.value = "两次输入的密码不一致";
    return;
  }
  try {
    if (registering.value) {
      await auth.register(username.value, password.value, displayName.value);
    } else {
      await auth.login(username.value, password.value);
    }
    const redirect = typeof route.query.redirect === "string" && route.query.redirect.startsWith("/")
      ? route.query.redirect
      : "/";
    await router.replace(redirect);
  } catch (reason) {
    error.value = message(reason);
  }
}
</script>

<template>
  <main class="auth-page">
    <section class="auth-intro">
      <div class="auth-brand"><span>备</span><div><strong>备课搭子</strong><small>教案设计多智能体支持系统</small></div></div>
      <div class="auth-copy">
        <p>统一教案 · 智能协作</p>
        <h1>让每一次备课都有清晰的过程与可靠的成果</h1>
        <span>登录后，你的教案、版本和任务记录仅在自己的账户中显示。</span>
      </div>
    </section>

    <section class="auth-panel" aria-labelledby="auth-title">
      <form class="auth-card" @submit.prevent="submit">
        <header><span>{{ registering ? "创建账户" : "欢迎回来" }}</span><h2 id="auth-title">{{ registering ? "注册备课搭子" : "登录备课搭子" }}</h2></header>
        <label v-if="registering">显示名称<input v-model.trim="displayName" required maxlength="100" autocomplete="name" placeholder="例如：王老师" /></label>
        <label>用户名<input v-model.trim="username" required minlength="4" maxlength="32" autocomplete="username" placeholder="4–32 位字母、数字或 . _ -" /></label>
        <label>密码<input v-model="password" required minlength="8" maxlength="72" type="password" :autocomplete="registering ? 'new-password' : 'current-password'" placeholder="至少 8 个字符" /></label>
        <label v-if="registering">确认密码<input v-model="confirmPassword" required minlength="8" maxlength="72" type="password" autocomplete="new-password" placeholder="再次输入密码" /></label>
        <p v-if="error" class="auth-error" role="alert">{{ error }}</p>
        <button class="auth-submit" type="submit" :disabled="auth.loading">{{ auth.loading ? "正在处理…" : registering ? "注册并登录" : "登录" }}</button>
        <p class="auth-switch">{{ registering ? "已有账户？" : "还没有账户？" }} <RouterLink :to="registering ? '/login' : '/register'">{{ registering ? "返回登录" : "立即注册" }}</RouterLink></p>
      </form>
    </section>
  </main>
</template>

<style scoped>
.auth-page{min-height:100vh;display:grid;grid-template-columns:minmax(360px,1.05fr) minmax(420px,.95fr);background:#f6f9ff}.auth-intro{display:flex;flex-direction:column;padding:48px clamp(40px,7vw,112px);color:#fff;background:linear-gradient(145deg,#174ea6,#2563d9 58%,#5b8def)}.auth-brand{display:flex;align-items:center;gap:14px}.auth-brand>span{display:grid;place-items:center;width:48px;height:48px;border-radius:12px;background:#fff;color:#1f5fc7;font-weight:800}.auth-brand strong,.auth-brand small{display:block}.auth-brand strong{font-size:22px}.auth-brand small{margin-top:4px;color:#dce9ff;font-size:12px}.auth-copy{max-width:590px;margin:auto 0}.auth-copy p{font-size:14px;font-weight:700;letter-spacing:.12em}.auth-copy h1{margin:18px 0;font-size:clamp(34px,4vw,56px);line-height:1.18}.auth-copy span{display:block;max-width:480px;color:#e4edff;line-height:1.8}.auth-panel{display:grid;place-items:center;padding:40px}.auth-card{width:min(430px,100%);padding:38px;border:1px solid var(--color-border);border-radius:18px;background:#fff;box-shadow:0 18px 54px rgba(34,73,135,.12)}.auth-card header span{color:var(--color-primary);font-size:13px;font-weight:700}.auth-card h2{margin:8px 0 28px;color:var(--color-text);font-size:28px}.auth-card label{display:grid;gap:8px;margin-top:18px;color:var(--color-text-secondary);font-size:14px;font-weight:650}.auth-card input{height:46px;padding:0 14px;border:1px solid var(--color-border);border-radius:var(--radius-md);outline:none;font:inherit}.auth-card input:focus{border-color:var(--color-primary);box-shadow:0 0 0 3px var(--color-primary-soft)}.auth-error{margin:16px 0 0;padding:10px 12px;border-radius:var(--radius-sm);color:#a22929;background:#fff1f1;font-size:13px}.auth-submit{width:100%;height:46px;margin-top:24px;border:0;border-radius:var(--radius-md);color:#fff;background:var(--color-primary);font-weight:750;cursor:pointer}.auth-submit:disabled{opacity:.55;cursor:wait}.auth-switch{text-align:center;margin:20px 0 0;color:var(--color-text-muted);font-size:13px}.auth-switch a{color:var(--color-primary);font-weight:700}@media(max-width:820px){.auth-page{grid-template-columns:1fr}.auth-intro{min-height:250px;padding:30px}.auth-copy{margin:48px 0 0}.auth-copy h1{font-size:30px}.auth-panel{padding:28px 18px}.auth-card{padding:28px}}
</style>
