export interface LessonDocument {
  schemaVersion: string;
  blocks: Array<{
    id: string;
    type: string;
    level?: number;
    text?: string;
    ordered?: boolean;
    items?: string[];
    tableRows?: string[][];
    rowIds?: string[];
  }>;
}
export interface VersionDocument {
  lessonId: string;
  versionId: string;
  document: LessonDocument;
  bindingStatus: "VERIFIED" | "TEXT_FALLBACK" | "UNVERIFIED";
  warnings: string[];
  contentSha256: string;
  structureSha256: string;
}
