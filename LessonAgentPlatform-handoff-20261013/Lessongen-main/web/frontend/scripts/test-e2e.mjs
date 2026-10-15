import { spawnSync } from "node:child_process";
const result = spawnSync(
  process.execPath,
  ["node_modules/@playwright/test/cli.js", "test"],
  { stdio: "inherit" },
);
process.exit(result.status ?? 1);
