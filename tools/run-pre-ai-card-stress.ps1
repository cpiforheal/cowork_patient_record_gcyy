$ErrorActionPreference = "Stop"

if (-not $env:PRE_AI_TEST_PASSWORD) {
    throw "Set PRE_AI_TEST_PASSWORD explicitly before running this deployment-only stress test."
}

& pnpm dlx playwright@1.55.0 test tools/pre-ai-card-stress.spec.js --workers=1 --reporter=line
exit $LASTEXITCODE
