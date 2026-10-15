import { beforeEach, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import { useJobStore } from "../src/stores/jobs";
import { getJob, getResult, getArtifacts } from "../src/api/client";
import axios from "axios";
import { useAuthStore } from "../src/stores/auth";
import type { Artifact, LessonResult } from "../src/types/api";
vi.mock("axios", () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    patch: vi.fn(),
    defaults: { headers: { common: {} } },
  },
}));
vi.mock("../src/api/client", () => ({
  getJob: vi.fn(),
  getArtifacts: vi.fn(),
  getResult: vi.fn(),
  listJobs: vi.fn(),
}));
beforeEach(() => setActivePinia(createPinia()));
it("late job response cannot repopulate a reset account", async () => {
  let resolve!: (value: any) => void;
  vi.mocked(getJob).mockReturnValue(
    new Promise((r) => {
      resolve = r;
    }),
  );
  const store = useJobStore();
  const request = store.loadJob("old-user-job");
  store.resetCurrent();
  resolve({ jobId: "old-user-job" });
  await request;
  expect(store.current).toBeNull();
});

it("a late restore failure cannot clear the new account", async () => {
  let reject!: (error: Error) => void;
  vi.mocked(axios.get).mockImplementation((url) =>
    url === "/api/auth/me"
      ? new Promise((_resolve, r) => {
          reject = r;
        })
      : Promise.resolve({ data: { headerName: "X-XSRF-TOKEN", token: "new" } }),
  );
  const auth = useAuthStore();
  const restore = auth.restore();
  await Promise.resolve();
  await Promise.resolve();
  await Promise.resolve();
  vi.mocked(axios.post).mockResolvedValue({
    data: {
      id: "new-account",
      username: "new",
      displayName: "new",
      role: "USER",
    },
  });
  await auth.login("new", "password");
  reject(new Error("old restore rejected"));
  await restore;
  expect(auth.user?.id).toBe("new-account");
});

it("logout prevents pending profile and terminal responses from restoring the previous account's data", async () => {
  const user = {
    id: "old-account",
    username: "old",
    displayName: "Old teacher",
    role: "USER" as const,
  };
  const auth = useAuthStore();
  auth.user = user;
  window.localStorage.setItem("platform-auth-user-id", user.id);
  window.localStorage.setItem(
    "platform-generated-lesson-private",
    "private lesson",
  );
  window.sessionStorage.setItem(
    "lesoongen.generate-draft",
    JSON.stringify({ topic: "Private topic" }),
  );

  let resolveProfile!: (value: { data: typeof user }) => void;
  let resolveResult!: (value: LessonResult) => void;
  let resolveArtifacts!: (value: Artifact[]) => void;
  vi.mocked(axios.patch).mockReturnValueOnce(
    new Promise((done) => {
      resolveProfile = done;
    }),
  );
  vi.mocked(getResult).mockReturnValueOnce(
    new Promise((done) => {
      resolveResult = done;
    }),
  );
  vi.mocked(getArtifacts).mockReturnValueOnce(
    new Promise((done) => {
      resolveArtifacts = done;
    }),
  );
  const jobs = useJobStore();
  const pending = [
    auth.updateProfile("Updated old teacher"),
    jobs.loadTerminalData("private-job"),
  ];

  vi.mocked(axios.post).mockResolvedValueOnce({ status: 204 });
  await auth.logout();
  resolveProfile({ data: { ...user, displayName: "Updated old teacher" } });
  resolveResult({
    jobId: "private-job",
    status: "COMPLETED",
    bestVersionId: "private-version",
    bestLessonPlan: { title: "Private lesson" },
    scores: {},
    scoreNotice: "",
    changes: [],
    unresolvedIssues: [],
    parseWarnings: [],
  });
  resolveArtifacts([
    {
      artifactId: "private-docx",
      type: "DOCX",
      displayName: "Private lesson",
      mediaType:
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
      sizeBytes: 123,
      sha256: "a".repeat(64),
      downloadUrl: "/api/private.docx",
    },
  ]);
  await Promise.all(pending);

  expect(auth.user).toBeNull();
  expect(auth.isAuthenticated).toBe(false);
  expect(jobs.result).toBeNull();
  expect(jobs.artifacts).toEqual([]);
  expect(jobs.resultReady).toBe(false);
  expect(jobs.artifactsReady).toBe(false);
  expect(jobs.resultLoading).toBe(false);
  expect(jobs.artifactLoading).toBe(false);
  expect(window.localStorage.getItem("platform-auth-user-id")).toBeNull();
  expect(
    window.localStorage.getItem("platform-generated-lesson-private"),
  ).toBeNull();
  expect(window.sessionStorage.getItem("lesoongen.generate-draft")).toBeNull();
});

it("ignores a stale logout failure after a new account signs in", async () => {
  let rejectLogout!: (reason: Error) => void;
  const nextUser = {
    id: "next-account",
    username: "next",
    displayName: "Next teacher",
    role: "USER" as const,
  };
  vi.mocked(axios.post)
    .mockReturnValueOnce(
      new Promise((_resolve, reject) => {
        rejectLogout = reject;
      }),
    )
    .mockResolvedValueOnce({ data: nextUser });
  vi.mocked(axios.get).mockResolvedValueOnce({
    data: { headerName: "X-XSRF-TOKEN", token: "next-token" },
  });
  const auth = useAuthStore();
  const pendingLogout = auth.logout();
  await auth.login("next", "password");
  rejectLogout(new Error("previous logout failed"));

  await expect(pendingLogout).resolves.toBeUndefined();
  expect(auth.user?.id).toBe("next-account");
  expect(window.localStorage.getItem("platform-auth-user-id")).toBe(
    "next-account",
  );
});
