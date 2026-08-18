import { existsSync } from 'node:fs';
import process from 'node:process';
import puppeteer from 'puppeteer-core';

const baseUrl = process.env.FINTO_CAPTURE_URL || 'https://finto-demo.vercel.app';
const username = process.env.FINTO_CAPTURE_USER || 'demo';
const password = process.env.FINTO_CAPTURE_PASSWORD || 'finto-demo-2026';
const executablePath = process.env.CHROME_PATH || [
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  '/usr/bin/google-chrome-stable',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium-browser',
  '/usr/bin/chromium',
].find((candidate) => existsSync(candidate));
if (!executablePath) throw new Error('Set CHROME_PATH to a Chrome or Chromium binary');

const browser = await puppeteer.launch({ executablePath, headless: true });
const page = await browser.newPage();
await page.setViewport({ width: 390, height: 844, deviceScaleFactor: 2, isMobile: true, hasTouch: true });

async function settle(selector) {
  await page.waitForSelector(selector, { visible: true, timeout: 20_000 });
  await new Promise((resolve) => setTimeout(resolve, 400));
}

function auditShell() {
  return page.evaluate(() => {
    const content = document.querySelector('.content');
    const nav = document.querySelector('.mobile-nav');
    const pageRoot = document.querySelector('router-outlet + *');
    const navBox = nav?.getBoundingClientRect();
    return {
      contentHeight: Math.round(content?.clientHeight ?? 0),
      hasPage: !!pageRoot && pageRoot.getBoundingClientRect().height > 0,
      navVisible: !!navBox && navBox.height > 0 && getComputedStyle(nav).display !== 'none',
      hero: !!document.querySelector('.hero-figure'),
    };
  });
}

try {
  await page.goto(`${baseUrl}/summary`, { waitUntil: 'networkidle0', timeout: 45_000 });
  if (await page.$('#identifier')) {
    await page.type('#identifier', username);
    await page.type('#password', password);
    await page.click('button[type="submit"]');
    await page.waitForNavigation({ waitUntil: 'networkidle0', timeout: 45_000 });
  }
  await settle('.hero-figure');

  const loaded = await auditShell();
  if (!loaded.navVisible || !loaded.hasPage || !loaded.hero || loaded.contentHeight < 32) {
    throw new Error(`Summary loaded as a blank shell: ${JSON.stringify(loaded)}`);
  }

  await page.evaluate(() => {
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'hidden' });
    document.dispatchEvent(new Event('visibilitychange'));
  });
  await page.evaluate(() => {
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'visible' });
    document.dispatchEvent(new Event('visibilitychange'));
  });
  await settle('.hero-figure');
  const resumed = await auditShell();
  if (!resumed.navVisible || !resumed.hasPage || !resumed.hero || resumed.contentHeight < 32) {
    throw new Error(`Summary went blank after resume: ${JSON.stringify(resumed)}`);
  }

  console.log('PWA shell checks passed.');
} finally {
  await browser.close();
}
