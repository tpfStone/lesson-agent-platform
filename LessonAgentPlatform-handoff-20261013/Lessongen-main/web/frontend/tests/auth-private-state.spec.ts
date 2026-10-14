import { beforeEach, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import { useJobStore } from "../src/stores/jobs";
import { getJob } from "../src/api/client";
import axios from "axios";
import { useAuthStore } from "../src/stores/auth";
vi.mock("axios", () => ({ default: { get: vi.fn(), post: vi.fn(), defaults: { headers: { common: {} } } } }));
vi.mock("../src/api/client", () => ({getJob: vi.fn(), getArtifacts: vi.fn(), getResult: vi.fn(), listJobs: vi.fn()}));
beforeEach(() => setActivePinia(createPinia()));
it("late job response cannot repopulate a reset account", async () => {
  let resolve!: (value: any) => void;
  vi.mocked(getJob).mockReturnValue(new Promise(r => { resolve = r; }));
  const store = useJobStore();
  const request = store.loadJob("old-user-job");
  store.resetCurrent();
  resolve({jobId: "old-user-job"});
  await request;
  expect(store.current).toBeNull();
});

it("a late restore failure cannot clear the new account", async () => {
  let reject!: (error: Error) => void;
  vi.mocked(axios.get).mockImplementation((url) => url === "/api/auth/me"
    ? new Promise((_resolve, r) => { reject = r; })
    : Promise.resolve({ data: { headerName: "X-XSRF-TOKEN", token: "new" } }));
  const auth = useAuthStore();
  const restore = auth.restore();
  await Promise.resolve(); await Promise.resolve(); await Promise.resolve();
  vi.mocked(axios.post).mockResolvedValue({ data: { id: "new-account", username: "new", displayName: "new", role: "USER" } });
  await auth.login("new", "password");
  reject(new Error("old restore rejected"));
  await restore;
  expect(auth.user?.id).toBe("new-account");
});
