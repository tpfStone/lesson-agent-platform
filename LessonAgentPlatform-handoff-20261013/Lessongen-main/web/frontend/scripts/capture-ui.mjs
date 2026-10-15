import { chromium } from "@playwright/test";
import { mkdir } from "node:fs/promises";
const url = process.env.UI_AUDIT_URL;
if (!url)
  throw new Error(
    "Set UI_AUDIT_URL to a running local page before capturing UI evidence.",
  );
if (!["localhost", "127.0.0.1"].includes(new URL(url).hostname))
  throw new Error("UI_AUDIT_URL must be local");
const browser = await chromium.launch({ headless: true });
try {
  const page = await browser.newPage({
    viewport: { width: 1440, height: 1000 },
  });
  await page.goto(url);
  await mkdir("artifacts", { recursive: true });
  await page.screenshot({ path: "artifacts/ui-audit.png", fullPage: true });
} finally {
  await browser.close();
}
