import { defineConfig, devices } from "@playwright/test";

const localChromiumPath = process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH;

export default defineConfig({
  testDir: "./tests/e2e",
  fullyParallel: true,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 2 : 0,
  reporter: process.env.CI ? "github" : "list",
  use: {
    baseURL: "http://127.0.0.1:4174",
    trace: "retain-on-failure"
  },
  projects: [
    {
      name: "chromium",
      use: {
        ...devices["Desktop Chrome"],
        launchOptions: localChromiumPath ? { executablePath: localChromiumPath } : undefined
      }
    }
  ],
  webServer: {
    command: "pnpm dev --host 127.0.0.1 --port 4174",
    url: "http://127.0.0.1:4174/#/login",
    reuseExistingServer: !process.env.CI,
    timeout: 120_000
  }
});
