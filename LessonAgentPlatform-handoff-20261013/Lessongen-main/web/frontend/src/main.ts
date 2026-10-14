import { createApp } from "vue";
import { createPinia } from "pinia";
import axios from "axios";
import "./styles/main.css";
import App from "./App.vue";
import router from "./router";
import { useAuthStore } from "./stores/auth";
import { csrfHeaders } from "./api/csrf";
import { privateContextEpoch } from "./api/privateContext";

axios.defaults.withCredentials = true;
axios.defaults.withXSRFToken = false;
const requestEpochs = new WeakMap<object, number>();
axios.interceptors.request.use((config) => {
  requestEpochs.set(config, privateContextEpoch());
  const headers = csrfHeaders();

  Object.entries(headers).forEach(([name, value]) => {
    config.headers.set(name, value);
  });

  return config;
});
axios.interceptors.response.use(response => {
  if (requestEpochs.get(response.config) !== privateContextEpoch()) throw new axios.CanceledError("用户上下文已改变");
  return response;
}, error => {
  if (error.config && requestEpochs.get(error.config) !== privateContextEpoch()) return Promise.reject(new axios.CanceledError("用户上下文已改变"));
  return Promise.reject(error);
});

const pinia = createPinia();
const auth = useAuthStore(pinia);

router.beforeEach(async (to) => {
  await auth.restore();
  if (to.meta.public) {
    if (auth.user && (to.name === "login" || to.name === "register")) return "/";
    return true;
  }
  if (!auth.user) {
    return { name: "login", query: { redirect: to.fullPath } };
  }
  if (to.meta.admin && auth.user.role !== "ADMIN") return "/";
  
  return true;
});

createApp(App).use(pinia).use(router).mount("#app");
