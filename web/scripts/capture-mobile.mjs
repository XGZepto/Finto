import fs from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';
import puppeteer from 'puppeteer-core';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const output = path.join(root, 'docs/design/mobile');
const baseUrl = process.env.FINTO_CAPTURE_URL || 'http://127.0.0.1:4200';
const username = process.env.FINTO_CAPTURE_USER || 'owner';
const password = process.env.FINTO_CAPTURE_PASSWORD || 'local-dev';
const checkOnly = process.argv.includes('--check-only');
const executablePath = process.env.CHROME_PATH ||
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';

await fs.mkdir(output, { recursive: true });
const browser = await puppeteer.launch({ executablePath, headless: true });
const page = await browser.newPage();
await page.setViewport({ width: 390, height: 844, deviceScaleFactor: 2, isMobile: true, hasTouch: true });

async function settle(selector) {
  if (selector) await page.waitForSelector(selector, { visible: true, timeout: 10_000 });
  await new Promise((resolve) => setTimeout(resolve, 900));
}

async function visit(route, selector) {
  await page.goto(`${baseUrl}${route}`, { waitUntil: 'networkidle0' });
  await settle(selector);
  await assertMobileBasics(route);
}

async function shot(name) {
  if (!checkOnly) await page.screenshot({ path: path.join(output, name), captureBeyondViewport: false });
}

async function clickText(selector, text) {
  const clicked = await page.$$eval(selector, (nodes, label) => {
    const node = nodes.find((item) => item.textContent?.trim().includes(label));
    if (!node) return false;
    node.click();
    return true;
  }, text);
  if (!clicked) throw new Error(`Could not find ${selector} containing ${text}`);
}

async function assertMobileBasics(route) {
  const audit = await page.evaluate(() => {
    const doc = document.documentElement;
    const visible = (node) => {
      const box = node.getBoundingClientRect();
      const style = getComputedStyle(node);
      return box.width > 0 && box.height > 0 && style.visibility !== 'hidden' && style.display !== 'none';
    };
    const shortTargets = [...document.querySelectorAll('button, input, select, textarea')]
      .filter((node) => visible(node) && !['checkbox', 'radio'].includes(node.getAttribute('type')))
      .map((node) => ({ label: node.getAttribute('aria-label') || node.textContent?.trim().slice(0, 32) || node.tagName,
        height: Math.round(node.getBoundingClientRect().height) }))
      .filter((item) => item.height < 44);
    return { overflow: doc.scrollWidth - doc.clientWidth, shortTargets };
  });
  if (audit.overflow > 1) throw new Error(`${route}: horizontal overflow of ${audit.overflow}px`);
  if (audit.shortTargets.length) throw new Error(`${route}: touch targets below 44px: ${JSON.stringify(audit.shortTargets)}`);
}

try {
  await page.goto(`${baseUrl}/summary`, { waitUntil: 'networkidle0' });
  if (await page.$('#identifier')) {
    await page.type('#identifier', username);
    await page.type('#password', password);
    await page.click('button[type="submit"]');
    await page.waitForNavigation({ waitUntil: 'networkidle0' });
  }

  await visit('/summary', '.hero-figure');
  await shot('summary-after.png');
  await page.click('finto-select.currency-select .select-trigger');
  await settle('.select-menu');
  const sheet = await page.$eval('.select-menu', (node) => {
    const box = node.getBoundingClientRect();
    return { bottom: Math.round(box.bottom), viewport: innerHeight,
      ownsBottom: !!document.elementFromPoint(innerWidth / 2, innerHeight - 10)?.closest('.select-menu') };
  });
  if (Math.abs(sheet.viewport - sheet.bottom) > 1 || !sheet.ownsBottom) throw new Error('Select sheet is not above the tab bar at the viewport bottom');
  await shot('summary-currency-sheet-after.png');

  await visit('/blotter', '.ledger-card');
  await shot('blotter-after.png');
  await clickText('button', 'Filters');
  await settle('.advanced.open');
  await shot('blotter-filters-after.png');
  await page.click('finto-date .date-trigger');
  await settle('finto-date .calendar');
  const calendarBox = await page.$eval('finto-date .calendar', (node) => {
    const box = node.getBoundingClientRect();
    const head = node.querySelector('.sheet-head').getBoundingClientRect();
    return { bottom: Math.round(innerHeight - box.bottom), top: Math.round(box.top), headTop: Math.round(head.top),
      ownsBottom: !!document.elementFromPoint(innerWidth / 2, innerHeight - 10)?.closest('.calendar') };
  });
  if (Math.abs(calendarBox.bottom) > 1 || calendarBox.top < 0 || calendarBox.headTop < calendarBox.top || !calendarBox.ownsBottom) {
    throw new Error(`Date sheet is clipped or not bottom anchored: ${JSON.stringify(calendarBox)}`);
  }
  await shot('blotter-date-sheet-after.png');
  await page.click('finto-date .sheet-head button');
  await page.keyboard.press('Escape');
  await settle('.ledger-card');
  const opened = await page.$$eval('tr.clickable', (rows) => {
    const row = rows.find((item) => item.querySelector('td.category')?.textContent?.toLowerCase().includes('uncategorised'));
    const button = row?.querySelector('.swipe-action button');
    if (!button) return false;
    button.click();
    return true;
  });
  if (!opened) throw new Error('Could not open category picker');
  await settle('.picker');
  await shot('blotter-category-suggestion-after.png');

  await visit('/accounts', '.account-groups');
  await shot('accounts-after.png');

  // The compact treatment must not damage the wider information-dense layout.
  await page.setViewport({ width: 1280, height: 800, deviceScaleFactor: 1 });
  for (const [route, selector] of [['/summary', '.hero-figure'], ['/blotter', '.ledger-card'], ['/accounts', '.account-groups']]) {
    await page.goto(`${baseUrl}${route}`, { waitUntil: 'networkidle0' });
    await settle(selector);
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    if (overflow > 1) throw new Error(`${route}: desktop horizontal overflow of ${overflow}px`);
  }

  await page.emulateMediaFeatures([{ name: 'prefers-reduced-motion', value: 'reduce' }]);
  await page.goto(`${baseUrl}/summary`, { waitUntil: 'networkidle0' });
  await settle('.trend');
  const reduced = await page.$eval('.trend .series', (node) => getComputedStyle(node).animationName);
  if (reduced !== 'none') throw new Error('Chart motion is not disabled for reduced-motion users');

  console.log(checkOnly ? 'Mobile UX checks passed.' : `Mobile UX checks passed; screenshots written to ${output}.`);
} finally {
  await browser.close();
}
