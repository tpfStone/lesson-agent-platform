import { test, expect } from '@playwright/test';

test('logout and login in one tab clears private account state', async ({ page }) => {
  let account = 'teacherA';
  await page.route(/http:\/\/127\.0\.0\.1:4173\/api\//, async route => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/auth/csrf')) return route.fulfill({ json: { headerName: 'X-XSRF-TOKEN', token: 'test' } });
    if (path.endsWith('/auth/logout')) { account = ''; return route.fulfill({ status: 204 }); }
    if (path.endsWith('/auth/login')) account = 'teacherB';
    if (path.endsWith('/auth/me') || path.endsWith('/auth/login')) return route.fulfill({ json: { id: account, username: account, displayName: account, role: 'USER' } });
    if (path.endsWith('/lessons')) return route.fulfill({ json: [{ id: account, title: `${account}教案`, subject: '数学', grade: '五', topic: '面积', currentVersionId: 'v', updatedAt: '2026-10-14T00:00:00Z' }] });
    if (path.endsWith('/lesson-jobs')) return route.fulfill({ json: { items: [], page: 0, totalElements: 0, totalPages: 0 } });
    return route.fulfill({ json: [] });
  });
  await page.goto('/lessons');
  await expect(page.getByText('teacherA教案', { exact: true })).toBeVisible();
  await page.evaluate(() => localStorage.setItem('platform-generated-lesson-old-job', JSON.stringify({ lessonId: 'private-A' })));
  await page.getByRole('button', { name: '退出', exact: true }).click();
  await page.getByLabel('用户名', { exact: true }).fill('teacherB');
  await page.getByLabel('密码', { exact: true }).fill('password123');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page.getByRole('link', { name: 'teacherB', exact: true })).toBeVisible();
  await page.getByRole('link', { name: '我的教案', exact: true }).click();
  await expect(page.getByText('teacherB教案', { exact: true })).toBeVisible();
  await expect(page.getByText('teacherA教案', { exact: true })).toHaveCount(0);
  expect(await page.evaluate(() => localStorage.getItem('platform-generated-lesson-old-job'))).toBeNull();
});

test('generated result adopts on the server again after local cache is cleared', async ({ page }) => {
  let adoptions = 0;
  await page.route(/http:\/\/127\.0\.0\.1:4173\/api\//, async route => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/auth/csrf')) return route.fulfill({ json: { headerName: 'X-XSRF-TOKEN', token: 'test' } });
    if (path.endsWith('/auth/me')) return route.fulfill({ json: { id: 'teacher', username: 'teacher', displayName: '教师', role: 'USER' } });
    if (path.endsWith('/platform-lesson')) { adoptions++; return route.fulfill({ json: { lessonId: 'adopted', versionId: 'v1', versionNumber: 0 } }); }
    if (path.endsWith('/lesson-jobs/job')) return route.fulfill({ json: { jobId: 'job', mode: 'GENERATE', status: 'COMPLETED', topic: '面积', subject: '数学', grade: '五', bestVersionId: 'best', usage: {}, links: {} } });
    if (path.endsWith('/result')) return route.fulfill({ json: { jobId: 'job', status: 'COMPLETED', bestVersionId: 'best', bestLessonPlan: { title: '面积', metadata: {}, teaching_process: [] }, scores: {}, changes: [], unresolvedIssues: [], parseWarnings: [] } });
    return route.fulfill({ json: [] });
  });
  await page.goto('/jobs/job');
  const link = page.getByRole('link', { name: '查看教案', exact: true });
  await expect(link).toHaveAttribute('href', /\/lessons\/adopted\?version=v1$/);
  expect(adoptions).toBe(1);
  await page.evaluate(() => localStorage.clear());
  await page.reload();
  await expect(link).toHaveAttribute('href', /\/lessons\/adopted\?version=v1$/);
  expect(adoptions).toBe(2);
});
