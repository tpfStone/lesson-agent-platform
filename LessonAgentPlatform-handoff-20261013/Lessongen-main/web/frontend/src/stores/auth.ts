import axios from "axios";
import { defineStore } from "pinia";
import { computed, ref } from "vue";
import { useDraftStore } from "./draft";
import { useWorkflowStore } from "./workflow";
import { clearCsrfToken, setCsrfToken } from "../api/csrf";

export interface AuthUser {
  id: string;
  username: string;
  displayName: string;
  role: "USER" | "ADMIN";
  lastLoginAt?: string | null;
}

export const useAuthStore = defineStore("auth", () => {
  const user = ref<AuthUser | null>(null);
  const initialized = ref(false);
  const loading = ref(false);

  function adoptUser(next: AuthUser) {
    const previous = window.localStorage.getItem("platform-auth-user-id");
    if (previous && previous !== next.id) clearPrivateClientState();
    window.localStorage.setItem("platform-auth-user-id", next.id);
    user.value = next;
  }

  interface CsrfResponse {
  headerName: string;
  parameterName: string;
  token: string;
}

async function prepareCsrf() {
  const response = await axios.get<CsrfResponse>("/api/auth/csrf");

  setCsrfToken(
    response.data.headerName,
    response.data.token,
  );

  axios.defaults.headers.common[response.data.headerName] =
    response.data.token;
}

  async function restore() {
    if (initialized.value) return;
    try {
      await prepareCsrf();
      adoptUser((await axios.get<AuthUser>("/api/auth/me")).data);
    } catch {
      user.value = null;
    } finally {
      initialized.value = true;
    }
  }

  async function login(username: string, password: string) {
    loading.value = true;
    try {
      adoptUser((await axios.post<AuthUser>("/api/auth/login", { username, password })).data);
      await prepareCsrf();
    } finally {
      loading.value = false;
      initialized.value = true;
    }
  }

  async function register(username: string, password: string, displayName: string) {
    loading.value = true;
    try {
      await axios.post<AuthUser>("/api/auth/register", { username, password, displayName });
      await login(username, password);
    } finally {
      loading.value = false;
    }
  }

  async function logout() {
    try {
      await axios.post("/api/auth/logout");
    } finally {
      clearCsrfToken();
      delete axios.defaults.headers.common["X-XSRF-TOKEN"];
      user.value = null;
      window.localStorage.removeItem("platform-auth-user-id");
      clearPrivateClientState();
      initialized.value = true;
    }
  }

  async function updateProfile(displayName: string) {
    const next = (await axios.patch<AuthUser>("/api/auth/me", { displayName })).data;
    adoptUser(next);
  }

  async function changePassword(currentPassword: string, newPassword: string) {
    await axios.post("/api/auth/password", { currentPassword, newPassword });
  }

  return {
    user,
    initialized,
    loading,
    isAuthenticated: computed(() => Boolean(user.value)),
    restore,
    login,
    register,
    logout,
    updateProfile,
    changePassword,
  };
});

function clearPrivateClientState() {
  const privatePrefixes = [
    "platform-active-workflow-", "platform-custom-workflows-",
    "platform-selected-workflow-", "platform-generated-lesson-",
    "platform.f4.", "platform.workflow.f4.",
    "f1-active-annotation:", "f1-batch-review-",
  ];
  const keys = Array.from({ length: window.localStorage.length }, (_, index) =>
    window.localStorage.key(index)).filter((key): key is string => Boolean(key));
  keys.filter(key => privatePrefixes.some(prefix => key.startsWith(prefix)))
    .forEach(key => window.localStorage.removeItem(key));

  const privateSessionPrefixes = ["lesoongen.", "f2-optimize-settings-v2:"];
  const sessionKeys = Array.from({ length: window.sessionStorage.length }, (_, index) =>
    window.sessionStorage.key(index)).filter((key): key is string => Boolean(key));
  sessionKeys.filter(key => privateSessionPrefixes.some(prefix => key.startsWith(prefix)))
    .forEach(key => window.sessionStorage.removeItem(key));

  // Pinia stores survive route changes. Reset them as well as browser storage so a
  // logout/login in the same tab cannot expose the previous account's local state.
  useWorkflowStore().$reset();
  useDraftStore().$reset();
}
