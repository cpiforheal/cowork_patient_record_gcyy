import { expect, test, type Page } from "@playwright/test";

const viewports = [
  { name: "mobile", width: 360, height: 800 },
  { name: "tablet", width: 768, height: 900 },
  { name: "small-desktop", width: 1024, height: 768 },
  { name: "desktop", width: 1440, height: 900 }
];

async function mockLoginOptions(page: Page) {
  await page.route("**/auth/options", route =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        code: "200",
        msg: "success",
        data: {
          departments: ["门诊"],
          accounts: [{ id: "doctor-1", username: "doctor", name: "门诊医生", department: "门诊" }]
        }
      })
    })
  );
}

for (const viewport of viewports) {
  test(`${viewport.name} login has no horizontal overflow and keeps usable targets`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await mockLoginOptions(page);
    await page.goto("/#/login");
    await page.locator(".hello-action").click();
    await expect(page.locator(".clinic-login-form")).toBeVisible();

    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    expect(overflow).toBeLessThanOrEqual(1);

    const targets = page.locator(
      ".clinic-login-form .el-select__wrapper, .clinic-login-form .el-input__wrapper, .login-btn button"
    );
    const count = await targets.count();
    expect(count).toBeGreaterThan(0);
    for (let index = 0; index < count; index += 1) {
      const box = await targets.nth(index).boundingBox();
      expect(box?.height ?? 0).toBeGreaterThanOrEqual(44);
    }
  });
}

test("login supports keyboard focus and persistent validation feedback", async ({ page }) => {
  await mockLoginOptions(page);
  await page.goto("/#/login");
  await page.locator(".hello-action").click();

  const password = page.locator('input[type="password"]');
  await password.focus();
  await expect(password).toBeFocused();
  await expect(password.locator("xpath=ancestor::*[contains(@class, 'el-input__wrapper')][1]")).toHaveClass(/is-focus/);

  await page.locator(".login-btn .el-button--primary").click();
  const validationError = page.locator(".el-form-item__error");
  await expect(validationError).toHaveCount(1);
  await page.waitForTimeout(300);
  await expect(validationError).toBeVisible();
});
