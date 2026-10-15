import axios from "axios";
import { defineStore } from "pinia";
import { computed, ref } from "vue";
import { useDraftStore } from "./draft";
import { useWorkflowStore } from "./workflow";
import { useJobStore } from "./jobs";
import { clearCsrfToken, setCsrfToken } from "../api/csrf";
import { invalidatePrivateContext } from "../api/privateContext";

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
  let authGeneration = 0;

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

  async function prepareCsrf(generation = authGeneration) {
    const response = await axios.get<CsrfResponse>("/api/auth/csrf");
    if (generation !== authGeneration) return;

    setCsrfToken(response.data.headerName, response.data.token);

    axios.defaults.headers.common[response.data.headerName] =
      response.data.token;
  }

  async function restore() {
    if (initialized.value) return;
    const generation = ++authGeneration;
    try {
      await prepareCsrf(generation);
      if (generation !== authGeneration) return;
      const next = (await axios.get<AuthUser>("/api/auth/me")).data;
      if (generation === authGeneration) adoptUser(next);
    } catch {
      if (generation === authGeneration) {
        user.value = null;
        clearPrivateClientState();
      }
    } finally {
      if (generation === authGeneration) initialized.value = true;
    }
  }

  async function login(username: string, password: string) {
    const generation = ++authGeneration;
    loading.value = true;
    try {
      const next = (
        await axios.post<AuthUser>("/api/auth/login", { username, password })
      ).data;
      if (generation !== authGeneration) return;
      adoptUser(next);
      await prepareCsrf(generation);
    } finally {
      if (generation === authGeneration) {
        loading.value = false;
        initialized.value = true;
      }
    }
  }

  async function register(
    username: string,
    password: string,
    displayName: string,
  ) {
    loading.value = true;
    try {
      await axios.post<AuthUser>("/api/auth/register", {
        username,
        password,
        displayName,
      });
      await login(username, password);
    } finally {
      loading.value = false;
    }
  }

  async function logout() {
    const generation = ++authGeneration;
    try {
      await axios.post("/api/auth/logout");
    } catch (reason) {
      if (generation === authGeneration) throw reason;
    } finally {
      if (generation === authGeneration) {
        clearCsrfToken();
        delete axios.defaults.headers.common["X-XSRF-TOKEN"];
        user.value = null;
        window.localStorage.removeItem("platform-auth-user-id");
        clearPrivateClientState();
        initialized.value = true;
      }
    }
  }

  async function updateProfile(displayName: string) {
    const generation = authGeneration;
    const next = (await axios.patch<AuthUser>("/api/auth/me", { displayName }))
      .data;
    if (generation === authGeneration) adoptUser(next);
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
  invalidatePrivateContext();
  const privatePrefixes = [
    "platform-active-workflow-",
    "platform-custom-workflows-",
    "platform-selected-workflow-",
    "platform-generated-lesson-",
    "platform.f4.",
    "platform.workflow.f4.",
    "f1-active-annotation:",
    "f1-batch-review-",
  ];
  const keys = Array.from({ length: window.localStorage.length }, (_, index) =>
    window.localStorage.key(index),
  ).filter((key): key is string => Boolean(key));
  keys
    .filter((key) => privatePrefixes.some((prefix) => key.startsWith(prefix)))
    .forEach((key) => window.localStorage.removeItem(key));

  const privateSessionPrefixes = ["lesoongen.", "f2-optimize-settings-v2:"];
  const sessionKeys = Array.from(
    { length: window.sessionStorage.length },
    (_, index) => window.sessionStorage.key(index),
  ).filter((key): key is string => Boolean(key));
  sessionKeys
    .filter((key) =>
      privateSessionPrefixes.some((prefix) => key.startsWith(prefix)),
    )
    .forEach((key) => window.sessionStorage.removeItem(key));

  // Pinia stores survive route changes. Reset them as well as browser storage so a
  // logout/login in the same tab cannot expose the previous account's local state.
  useWorkflowStore().$reset();
  useDraftStore().$reset();
  useJobStore().resetPrivate();
}
