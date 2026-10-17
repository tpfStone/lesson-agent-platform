import { readFileSync } from "node:fs";
import { test, expect } from "@playwright/test";
import type { NavigationState } from "../src/features/navigation/api";
import type { LessonDocument } from "../src/types/lesson-document";

for (const viewport of [
  { width: 1440, height: 1000 },
  { width: 390, height: 844 },
]) {
  test(`chapter review shows ten units and completes the whole teaching table (${viewport.width}px)`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize(viewport);
    const document: LessonDocument = JSON.parse(
      readFileSync(
        new URL(
          "../../src/test/resources/fixtures/original-v0-structure.json",
          import.meta.url,
        ),
        "utf8",
      ),
    );
    // Hand-checked chapter boundaries from the original 43-unit lesson fixture.
    const boundaries = [5, 8, 11, 16, 19, 23, 25, 29, 33, 35, 39];
    const state: NavigationState = {
      session: {
        id: "chapters",
        status: "ACTIVE",
        current_section_id: "section-0",
        lesson_metadata: {
          subject: "数学",
          grade: "五年级",
          topic: "三角形的面积",
        },
        config_snapshot: { provider: "mock", max_rounds: 5 },
        updated_at: "2026-10-17T00:00:00Z",
      },
      round: { id: "round", round_number: 1 },
      sections: boundaries.slice(0, -1).map((start, index) => ({
        id: `section-${index}`,
        order_index: index,
        title: document.blocks[start - 1]!.text!,
        current_content: "本章节完整正文",
        source_ref: {
          blockIds: document.blocks
            .slice(start - 1, boundaries[index + 1]! - 1)
            .map((block) => block.id),
        },
        version: { id: `version-${index}`, version_number: 0 },
        review: { generated_at: null, completed_at: null },
        suggestions: [],
      })),
      lesson_plan: {
        current_content: "完整教案",
        structured_content: document,
      },
      lesson_plan_versions: [],
    };
    const generated: string[] = [];
    await page.route(/http:\/\/127\.0\.0\.1:4173\/api\//, async (route) => {
      const path = new URL(route.request().url()).pathname;
      if (path.endsWith("/auth/me"))
        return route.fulfill({
          json: {
            id: "teacher",
            username: "teacher",
            displayName: "教师",
            role: "USER",
          },
        });
      if (path.endsWith("/auth/csrf"))
        return route.fulfill({ json: { headerName: "X-CSRF", token: "test" } });
      if (path.startsWith("/api/platform/f4/sessions/chapters")) {
        const section = state.sections.find(
          (unit) => unit.id === state.session.current_section_id,
        )!;
        if (path.endsWith("/suggestions")) {
          expect(route.request().postDataJSON()).toEqual({
            round_id: "round",
            section_id: section.id,
          });
          generated.push(section.id);
          section.review.generated_at = "2026-10-17T01:00:00Z";
        } else if (path.endsWith("/complete-section")) {
          expect(section.review.generated_at).toBeTruthy();
          section.review.completed_at = "2026-10-17T01:00:00Z";
          state.session.current_section_id =
            state.sections[section.order_index + 1]?.id ?? null;
          if (!state.session.current_section_id)
            state.session.status = "ROUND_COMPLETED";
        }
        return route.fulfill({ json: state });
      }
      if (path.endsWith("/binding")) return route.fulfill({ json: {} });
      return route.fulfill({ json: { items: [], pdf: false } });
    });
    await page.goto("/platform/navigation?session=chapters");
    await expect(page.locator(".section-nav li")).toHaveCount(10);
    await expect(page.locator(".session-stats")).toContainText("0/10");
    await page.locator(".lesson-overview summary").click();
    await expect(page.locator(".lesson-overview")).toContainText("40分钟");
    await page.locator(".lesson-overview summary").click();
    for (let index = 0; index < 10; index++) {
      if (index === 5) {
        await expect(page.locator(".lesson-section h2")).toHaveText(
          "六、教学过程",
        );
        await expect(page.locator(".lesson-section tr")).toHaveCount(7);
        await expect(page.locator(".lesson-section")).toContainText(
          "6. 总结与作业",
        );
        await page.screenshot({
          path: testInfo.outputPath("whole-chapter.png"),
          fullPage: true,
        });
        const overflow = await page.evaluate(
          () => document.documentElement.scrollWidth > window.innerWidth + 1,
        );
        expect(overflow).toBe(false);
      }
      await page
        .getByRole("button", { name: "生成共析建议", exact: true })
        .click();
      await page
        .getByRole("button", {
          name: index === 9 ? "完成本轮" : "进入下一单元",
          exact: true,
        })
        .click();
      if (index === 5) {
        await page.reload();
        await expect(page.locator(".session-stats")).toContainText("6/10");
        await expect(page.locator(".lesson-section h2")).toHaveText(
          "七、形成性评价设计",
        );
      }
    }
    await expect(
      page.getByRole("heading", { name: "本轮共析结果已经保存" }),
    ).toBeVisible();
    expect(generated).toEqual(
      Array.from({ length: 10 }, (_, index) => `section-${index}`),
    );
  });
}
