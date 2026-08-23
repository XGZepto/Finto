import fs from 'node:fs/promises';
import { existsSync } from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';
import puppeteer from 'puppeteer-core';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const output = path.join(root, '.artifacts/design/sanity');
const baseUrl = process.env.FINTO_CAPTURE_URL || 'http://127.0.0.1:4200';
const username = process.env.FINTO_CAPTURE_USER || 'owner';
const password = process.env.FINTO_CAPTURE_PASSWORD || 'local-dev';
const executablePath = process.env.CHROME_PATH || [
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  '/usr/bin/google-chrome-stable',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium-browser',
  '/usr/bin/chromium',
].find((candidate) => existsSync(candidate));
if (!executablePath) throw new Error('Set CHROME_PATH to a Chrome or Chromium binary');

const viewports = {
  mobile: { width: 390, height: 844, deviceScaleFactor: 2, isMobile: true, hasTouch: true },
  desktop: { width: 1440, height: 960, deviceScaleFactor: 1, isMobile: false, hasTouch: false },
};

const routes = [
  ['summary', '/summary'],
  ['reports', '/reports'],
  ['recurring', '/recurring'],
  ['more', '/tools'],
  ['blotter', '/blotter'],
  ['timeline', '/timeline'],
  ['accounts', '/accounts'],
  ['import', '/import'],
  ['instalments', '/installments'],
  ['investments', '/investments'],
  ['matching-suggestions', '/review'],
  ['integrity', '/integrity'],
  ['ask', '/ask'],
  ['settings', '/profile'],
];

await fs.mkdir(output, { recursive: true });
const browser = await puppeteer.launch({ executablePath, headless: true });
const page = await browser.newPage();
const captured = [];

async function setMode(mode) {
  await page.setViewport(viewports[mode]);
  await fs.mkdir(path.join(output, mode), { recursive: true });
}

async function settle(selector) {
  if (selector) await page.waitForSelector(selector, { visible: true, timeout: 10_000 });
  await new Promise((resolve) => setTimeout(resolve, 650));
}

async function visit(route, selector = 'router-outlet + *') {
  await page.goto(`${baseUrl}${route}`, { waitUntil: 'networkidle0' });
  await page.evaluate(() => document.querySelector('.content')?.scrollTo(0, 0));
  await settle(selector);
}

async function shot(mode, name) {
  const file = path.join(output, mode, `${name}.png`);
  await page.screenshot({ path: file, captureBeyondViewport: false });
  captured.push(`${mode}/${name}.png`);
}

async function click(selector) {
  const node = await page.$(selector);
  if (!node) return false;
  await node.click();
  return true;
}

async function clickText(selector, text) {
  return page.$$eval(selector, (nodes, label) => {
    const node = nodes.find((item) => item.textContent?.trim().includes(label));
    if (!node) return false;
    node.click();
    return true;
  }, text);
}

async function audit(mode, route) {
  const result = await page.evaluate(() => {
    const shell = document.querySelector('.shell');
    const content = document.querySelector('.content');
    const visible = (node) => {
      const box = node.getBoundingClientRect();
      const style = getComputedStyle(node);
      return box.width > 0 && box.height > 0 && style.display !== 'none' && style.visibility !== 'hidden';
    };
    const controlHeights = [...document.querySelectorAll(
      '.controls-bar input:not([type="checkbox"]), .controls-bar finto-select .select-trigger, ' +
      '.controls-bar finto-date .date-trigger, .controls-bar finto-pills .pills, ' +
      '.defaults finto-select .select-trigger, .filter-bar .controls finto-select .select-trigger, ' +
      '.filter-bar .controls finto-date .date-trigger',
    )].filter(visible).map((node) => ({
      label: node.getAttribute('aria-label') || node.tagName,
      height: Math.round(node.getBoundingClientRect().height),
    }));
    return {
      viewportOverflow: document.documentElement.scrollWidth - document.documentElement.clientWidth,
      shellOverflow: shell ? shell.scrollWidth - shell.clientWidth : 0,
      contentOverflow: content ? content.scrollWidth - content.clientWidth : 0,
      navItems: [...document.querySelectorAll('.mobile-nav a')].filter((node) => {
        const box = node.getBoundingClientRect();
        const style = getComputedStyle(node);
        return box.width > 0 && box.height > 0 && style.display !== 'none' && style.visibility !== 'hidden' && Number(style.opacity) > 0;
      }).length,
      controlHeights,
    };
  });
  if ([result.viewportOverflow, result.shellOverflow, result.contentOverflow].some((value) => value > 1)) {
    throw new Error(`${mode} ${route}: horizontal overflow ${JSON.stringify(result)}`);
  }
  if (mode === 'mobile' && result.navItems !== 5) {
    throw new Error(`${route}: persistent navigation lost visible destinations: ${JSON.stringify(result)}`);
  }
  const expectedControlHeight = mode === 'mobile' ? 44 : 36;
  const unevenControls = result.controlHeights.filter(
    (control) => Math.abs(control.height - expectedControlHeight) > 1,
  );
  if (unevenControls.length) {
    throw new Error(`${mode} ${route}: inconsistent control heights ${JSON.stringify(unevenControls)}`);
  }
}

async function captureBaseRoutes(mode) {
  for (const [name, route] of routes) {
    await visit(route);
    await audit(mode, route);
    await shot(mode, name);
  }
}

async function captureActions(mode) {
  await visit('/summary', '.hero-figure');
  if (await click('finto-select.currency-select .select-trigger')) {
    await settle('.select-menu');
    await shot(mode, 'summary-reporting-currency');
    await page.keyboard.press('Escape');
  }

  await visit('/reports', '.totals-card');
  if (await click('finto-select .select-trigger')) {
    await settle('.select-menu');
    await shot(mode, 'reports-options');
    await page.keyboard.press('Escape');
  }

  await visit('/recurring');
  if (await click('button.row')) {
    await settle('.drawer');
    await shot(mode, 'recurring-detail');
    await page.keyboard.press('Escape');
  }

  await visit('/blotter', '.ledger-card');
  if (mode === 'mobile' && await click('.amount-options-trigger')) {
    await settle('.amount-options');
    await shot(mode, 'blotter-display-options');
    await page.keyboard.press('Escape');
  }
  if (await clickText('button', 'Filters')) {
    await settle('.advanced.open');
    await shot(mode, 'blotter-filters');
    if (await click('.advanced.open finto-select[ariaLabel="Account"] .select-trigger')) {
      await settle('.select-menu');
      await shot(mode, 'blotter-account-selector');
      await page.keyboard.press('Escape');
    }
    if (await click('finto-date .date-trigger')) {
      await settle('finto-date .calendar');
      await shot(mode, 'blotter-date-selector');
      await page.keyboard.press('Escape');
    }
    await page.keyboard.press('Escape');
  }
  await visit('/blotter', '.ledger-card');
  if (await click('tr.clickable')) {
    await settle('.drawer');
    await shot(mode, 'blotter-transaction-detail');
    if (await clickText('.drawer button', 'Edit')) {
      await settle('.edit-panel');
      await shot(mode, 'blotter-transaction-edit');
      if (await click('.data-disclosure > summary')) {
        await settle('.data-disclosure[open]');
        await shot(mode, 'blotter-statement-data');
      }
    }
    await page.keyboard.press('Escape');
  }

  await visit('/timeline');
  if (await clickText('.seg button', 'amount')) {
    await settle();
    await shot(mode, 'timeline-amount-mode');
  }
  if (await click('finto-select .select-trigger')) {
    await settle('.select-menu');
    await shot(mode, 'timeline-dimension-selector');
    await page.keyboard.press('Escape');
  }

  await visit('/accounts', '.account-groups');
  if (await click('.group-header')) {
    await settle('.group-hero');
    await shot(mode, 'account-group');
    if (await click('.group-subaccount-grid button')) {
      await settle('.balance-card');
      await shot(mode, 'account-subaccount');
      if (await clickText('.view-switch button', 'List')) {
        await settle();
        await shot(mode, 'account-money-flow-list');
      }
    }
  }
  await visit('/accounts', '.single-account');
  if (await click('.single-account')) {
    await settle('.balance-card');
    await shot(mode, 'account-standalone');
  }

  await visit('/import');
  if (await clickText('button', 'formats')) {
    await settle();
    await shot(mode, 'import-formats');
  }
  if (await click('finto-select .select-trigger')) {
    await settle('.select-menu');
    await shot(mode, 'import-institution-selector');
    await page.keyboard.press('Escape');
  }

  await visit('/installments');
  if (await clickText('.seg button', 'All')) {
    await settle();
    await shot(mode, 'instalments-all');
  }
  if (await click('tr.clickable')) {
    await settle();
    await shot(mode, 'instalments-plan-expanded');
  }

  await visit('/investments');
  if (await click('finto-select .select-trigger')) {
    await settle('.select-menu');
    await shot(mode, 'investments-snapshot-selector');
    await page.keyboard.press('Escape');
  }

  await visit('/review');
  if (await click('.bar .seg button')) {
    await settle();
    await shot(mode, 'matching-suggestions-queue');
  }
  if (await click('.queue li button')) {
    await settle();
    await shot(mode, 'matching-suggestions-candidate');
  }

  await visit('/integrity');
  if (await clickText('.seg button', 'All periods')) {
    await settle();
    await shot(mode, 'integrity-all-periods');
  }

  await visit('/ask');
  if (await click('.suggestions button')) {
    await settle('.generated-answer');
    await shot(mode, 'ask-example-ready');
  }

  await visit('/profile', '.settings-grid');
  if (await click('.api-access > summary')) {
    await settle('.api-content');
    await shot(mode, 'settings-api-access');
  }
  if (await click('#theme .select-trigger')) {
    await settle('.select-menu');
    await shot(mode, 'settings-theme-selector');
    await page.keyboard.press('Escape');
  }
}

try {
  await setMode('desktop');
  await page.goto(`${baseUrl}/login`, { waitUntil: 'networkidle0' });
  await settle('#identifier');
  await shot('desktop', 'login');

  await setMode('mobile');
  await page.goto(`${baseUrl}/login`, { waitUntil: 'networkidle0' });
  await settle('#identifier');
  await shot('mobile', 'login');
  await page.type('#identifier', username);
  await page.type('#password', password);
  await Promise.all([
    page.waitForNavigation({ waitUntil: 'networkidle0' }),
    page.click('button[type="submit"]'),
  ]);

  for (const mode of ['mobile', 'desktop']) {
    await setMode(mode);
    await captureBaseRoutes(mode);
    await captureActions(mode);
  }

  await fs.writeFile(path.join(output, 'manifest.json'), `${JSON.stringify({
    generatedAt: new Date().toISOString(),
    viewports,
    captured,
  }, null, 2)}\n`);
  console.log(`Captured ${captured.length} route and action states in ${output}.`);
} finally {
  await browser.close();
}
