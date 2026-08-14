const { test, expect, chromium } = require("playwright/test");

test.setTimeout(60000);

test("pre-ai workflow cards stay responsive under repeated clicks", async () => {
  const browser = await chromium.launch({
    executablePath: "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
    headless: true
  });
  const context = await browser.newContext();
  const page = await context.newPage();
  const consoleErrors = [];
  const failedRequests = [];
  const forbiddenRequests = [];

  page.on("console", message => {
    if (message.type() === "error") consoleErrors.push(message.text());
  });
  page.on("requestfailed", request => failedRequests.push(`${request.method()} ${request.url()} ${request.failure()?.errorText || ""}`));
  page.on("response", response => {
    if (response.status() === 403) forbiddenRequests.push(`${response.request().method()} ${response.url()}`);
  });

  await page.goto("http://127.0.0.1:8848/", { waitUntil: "domcontentloaded" });
  console.log("PHASE root loaded");

  const loginResponse = await page.request.post("http://127.0.0.1:8848/auth/login", {
    data: { username: "admin", password: process.env.PRE_AI_TEST_PASSWORD }
  });
  expect(loginResponse.ok()).toBeTruthy();
  const loginPayload = await loginResponse.json();
  await page.evaluate(data => {
    localStorage.setItem("geeker-user", JSON.stringify({ token: data.access_token, userInfo: data.userInfo }));
  }, loginPayload.data);
  console.log("PHASE login stored");

  // A hash-only navigation keeps the already initialized anonymous Pinia/router
  // state alive. Reload once so the application boots from the persisted login.
  await page.goto("http://127.0.0.1:8848/?stress-test=1#/pre-ai/encounters", { waitUntil: "domcontentloaded" });
  console.log("PHASE route loaded", page.url());

  const patientCards = page.locator(".encounter-card, .patient-card, [class*='encounter-card'], [class*='patient-card']");
  await patientCards.first().waitFor({ state: "visible", timeout: 15000 });
  await patientCards.first().click();
  console.log("PHASE patient clicked", await patientCards.count());

  const workflowCards = page.locator(".workflow-card, [class*='workflow-card']");
  await workflowCards.first().waitFor({ state: "visible", timeout: 15000 });
  const cardCount = await workflowCards.count();
  console.log("PHASE workflow ready", cardCount);
  expect(cardCount).toBeGreaterThan(0);

  const samples = await page.evaluate(async iterations => {
    const cards = Array.from(document.querySelectorAll(".workflow-card"));
    const preferredIndices = [2, 4, 5].filter(index => index < cards.length);
    const indices = preferredIndices.length ? preferredIndices : cards.map((_, index) => index);
    const elapsed = [];
    for (let index = 0; index < iterations; index += 1) {
      const start = performance.now();
      cards[indices[index % indices.length]].click();
      await new Promise(resolve => setTimeout(resolve, 25));
      elapsed.push(performance.now() - start);
    }
    return elapsed;
  }, 100);
  console.log("PHASE stress complete");

  const recursiveErrors = consoleErrors.filter(error => /maximum recursive updates|too much recursion|call stack/i.test(error));
  const maxClickMs = Math.max(...samples);
  const firstQuarterAverage = samples.slice(0, 25).reduce((sum, value) => sum + value, 0) / 25;
  const lastQuarterAverage = samples.slice(-25).reduce((sum, value) => sum + value, 0) / 25;

  console.log(
    JSON.stringify(
      {
        url: page.url(),
        cardCount,
        maxClickMs,
        firstQuarterAverage,
        lastQuarterAverage,
        consoleErrors,
        recursiveErrors,
        failedRequests,
        forbiddenRequests
      },
      null,
      2
    )
  );

  expect(recursiveErrors).toEqual([]);
  expect(forbiddenRequests).toEqual([]);
  expect(maxClickMs).toBeLessThan(3000);
  expect(lastQuarterAverage).toBeLessThan(firstQuarterAverage * 3 + 100);

  await context.close();
  await browser.close();
});
