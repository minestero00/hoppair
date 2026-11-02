const { defineConfig } = require('@playwright/test');
module.exports = defineConfig({
  testDir: '.', testMatch: '*.spec.js', workers: 1,
  outputDir: '../../artifacts/site-tests',
  use: { baseURL: process.env.MACDROID_SITE_URL || 'http://127.0.0.1:8741', headless: true },
});
