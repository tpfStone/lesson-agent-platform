import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { effectScope, ref, type EffectScope } from "vue";
import { flushPromises } from "@vue/test-utils";
import axios from "axios";
import { useVersionDocument } from "../src/composables/useVersionDocument";
import type { VersionDocument } from "../src/types/lesson-document";

vi.mock("axios", () => ({
  default: {
    get: vi.fn(),
    isAxiosError: (reason: { isAxiosError?: boolean }) =>
      reason?.isAxiosError === true,
  },
}));

const scopes: EffectScope[] = [];
beforeEach(() => vi.resetAllMocks());
afterEach(() => scopes.splice(0).forEach((scope) => scope.stop()));

function openDocument(version = ref("A")) {
  const scope = effectScope();
  scopes.push(scope);
  return {
    scope,
    ...scope.run(() => useVersionDocument(ref("lesson"), version))!,
  };
}

function documentResponse(versionId = "A"): VersionDocument {
  return {
    lessonId: "lesson",
    versionId,
    bindingStatus: "VERIFIED",
    warnings: [],
    contentSha256: "a".repeat(64),
    structureSha256: "b".repeat(64),
    document: {
      schemaVersion: "1.1",
      blocks: [{ id: "paragraph", type: "paragraph", text: "Private lesson" }],
    },
  };
}

it("falls back only for an absent document and surfaces corrupt-document errors", async () => {
  vi.mocked(axios.get).mockRejectedValueOnce(
    Object.assign(new Error("missing"), {
      isAxiosError: true,
      response: { status: 404, data: { code: "LESSON_DOCUMENT_NOT_FOUND" } },
    }),
  );
  const version = ref("A");
  const state = openDocument(version);
  await flushPromises();
  expect(state.state.value).toBe("fallback");
  expect(state.snapshot.value).toBeNull();
  expect(state.error.value).toBe("");

  vi.mocked(axios.get).mockRejectedValueOnce(
    Object.assign(new Error("stored document is corrupt"), {
      isAxiosError: true,
      response: { status: 422, data: { code: "LESSON_DOCUMENT_CORRUPT" } },
    }),
  );
  version.value = "B";
  await flushPromises();
  expect(state.state.value).toBe("error");
  expect(state.snapshot.value).toBeNull();
  expect(state.error.value).not.toBe("");
});

it.each([
  ["missing document", undefined],
  ["missing blocks", { schemaVersion: "1.1" }],
  ["non-array blocks", { schemaVersion: "1.1", blocks: {} }],
  ["null block", { schemaVersion: "1.1", blocks: [null] }],
])(
  "rejects %s instead of publishing an unusable ready preview",
  async (_name, document) => {
    vi.mocked(axios.get).mockResolvedValueOnce({
      data: { ...documentResponse(), document },
    });
    const state = openDocument();
    await flushPromises();
    expect(state.state.value).toBe("error");
    expect(state.snapshot.value).toBeNull();
    expect(state.error.value).not.toBe("");
  },
);

it("keeps a valid empty document ready", async () => {
  vi.mocked(axios.get).mockResolvedValueOnce({
    data: {
      ...documentResponse(),
      document: { schemaVersion: "1.1", blocks: [] },
    },
  });
  const state = openDocument();
  await flushPromises();
  expect(state.state.value).toBe("ready");
  expect(state.snapshot.value?.document.blocks).toEqual([]);
  expect(state.error.value).toBe("");
});

it("rejects another version's document even when its schema and binding status are valid", async () => {
  vi.mocked(axios.get).mockResolvedValueOnce({ data: documentResponse("B") });
  const state = openDocument();
  await flushPromises();
  expect(state.state.value).toBe("error");
  expect(state.snapshot.value).toBeNull();
  expect(state.error.value).not.toBe("");
});

it("does not publish a pending private document after its view scope is disposed", async () => {
  let resolve!: (value: { data: VersionDocument }) => void;
  vi.mocked(axios.get).mockReturnValueOnce(
    new Promise((done) => {
      resolve = done;
    }),
  );
  const state = openDocument();
  state.scope.stop();
  resolve({ data: documentResponse() });
  await flushPromises();
  expect(state.snapshot.value).toBeNull();
  expect(state.state.value).not.toBe("ready");
});
