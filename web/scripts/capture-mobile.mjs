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
  await page.evaluate(() => document.querySelector('.content')?.scrollTo(0, 0));
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
  const tickOverlap = await page.$$eval('.trend .tick', (nodes) => {
    const boxes = nodes.filter((node) => getComputedStyle(node).display !== 'none')
      .map((node) => node.getBoundingClientRect()).sort((a, b) => a.left - b.left);
    return boxes.some((box, index) => index > 0 && box.left < boxes[index - 1].right);
  });
  if (tickOverlap) throw new Error('Summary chart labels overlap');
  await page.click('finto-select.currency-select .select-trigger');
  await settle('.select-menu');
  const sheet = await page.$eval('.select-menu', (node) => {
    const box = node.getBoundingClientRect();
    return { bottom: Math.round(box.bottom), viewport: innerHeight,
      ownsBottom: !!document.elementFromPoint(innerWidth / 2, innerHeight - 10)?.closest('.select-menu'),
      namedCurrencies: [...node.querySelectorAll('.option-copy strong')].some((item) => item.textContent?.includes('Dollar')),
      hasSearch: !!node.querySelector('input[type="search"]') };
  });
  if (Math.abs(sheet.viewport - sheet.bottom) > 1 || !sheet.ownsBottom) throw new Error('Select sheet is not above the tab bar at the viewport bottom');
  if (!sheet.namedCurrencies || !sheet.hasSearch) throw new Error('Currency sheet lacks names or search');
  const selectedCurrencyCopy = await page.$eval('.select-menu .select-option.selected', (node) => node.textContent ?? '');
  if ((selectedCurrencyCopy.match(/USD/g) ?? []).length !== 1 || !selectedCurrencyCopy.includes('US Dollar')) {
    throw new Error(`Currency identity is repetitive or incomplete: ${selectedCurrencyCopy.trim()}`);
  }
  await shot('summary-currency-sheet-after.png');

  await visit('/blotter', '.ledger-card');
  const reviewChrome = await page.$('.review-progress, .review-value');
  if (reviewChrome) throw new Error('Ledger still exposes review-state chrome');
  const compactAmountControls = await page.evaluate(() => ({
    inline: getComputedStyle(document.querySelector('.aggregation-controls')).display,
    options: getComputedStyle(document.querySelector('.amount-options-trigger')).display,
  }));
  if (compactAmountControls.inline !== 'none' || compactAmountControls.options === 'none') {
    throw new Error(`Compact amount controls are not tucked into Options: ${JSON.stringify(compactAmountControls)}`);
  }
  const navLabels = await page.$$eval('.mobile-nav a', (nodes) => nodes.map((node) => node.textContent?.trim()));
  if (navLabels.join('|') !== 'Summary|Blotter|Reports|Accounts|More') {
    throw new Error(`Unexpected mobile navigation: ${navLabels.join('|')}`);
  }
  await shot('blotter-after.png');

  await page.click('.amount-options-trigger');
  await settle('.amount-options');
  const optionsSheet = await page.$eval('.amount-options', (node) => {
    const box = node.getBoundingClientRect();
    return { bottom: Math.round(innerHeight - box.bottom), hasMode: !!node.querySelector('.seg'), hasCurrency: !!node.querySelector('finto-select'), ownsFocus: node.contains(document.activeElement) };
  });
  if (Math.abs(optionsSheet.bottom) > 1 || !optionsSheet.hasMode || !optionsSheet.hasCurrency || !optionsSheet.ownsFocus) {
    throw new Error(`Amount options sheet failed audit: ${JSON.stringify(optionsSheet)}`);
  }
  await shot('blotter-options-after.png');
  await page.click('.amount-options finto-select .select-trigger');
  await settle('.select-menu');
  const nestedCurrencyBottom = await page.$eval('.select-menu', (node) => Math.round(innerHeight - node.getBoundingClientRect().bottom));
  if (Math.abs(nestedCurrencyBottom) > 1) throw new Error(`Currency selector escaped its options sheet: ${nestedCurrencyBottom}px`);
  await page.click('.select-menu .sheet-head button');
  await page.click('.amount-options header button');
  await settle('.ledger-card');
  const optionsFocusRestored = await page.$eval('.amount-options-trigger', (node) => node === document.activeElement);
  if (!optionsFocusRestored) throw new Error('Options did not restore focus to its trigger');

  const openedDetail = await page.$$eval('tr.clickable', (rows) => {
    const row = rows.find((item) => item.querySelector('td.num .muted')) ?? rows[0];
    if (!row) return false;
    row.click();
    return true;
  });
  if (!openedDetail) throw new Error('Could not open transaction detail');
  await settle('.drawer .transaction-hero');
  const detailAudit = await page.$eval('.drawer', (node) => ({
    overflow: node.scrollWidth - node.clientWidth,
    bar: node.querySelector('.bar-title')?.textContent?.trim(),
    disclosure: !!node.querySelector('.data-disclosure'),
    backTarget: Math.round(node.querySelector('.mobile-back')?.getBoundingClientRect().height ?? 0),
    amountHasCurrency: /[A-Z]{3}/.test(node.querySelector('.amount-hero')?.textContent ?? ''),
  }));
  if (detailAudit.overflow > 1 || detailAudit.bar !== 'Transaction' || !detailAudit.disclosure || detailAudit.backTarget < 44 || !detailAudit.amountHasCurrency) {
    throw new Error(`Transaction detail failed mobile audit: ${JSON.stringify(detailAudit)}`);
  }
  await shot('blotter-transaction-after.png');
  await clickText('.drawer button', 'Edit');
  await settle('.edit-panel');
  await page.click('.edit-panel finto-select .select-trigger');
  await settle('.select-menu');
  await shot('blotter-transaction-category-after.png');
  await page.click('.select-menu .sheet-head button');
  await clickText('.drawer button', 'Cancel');
  await page.click('.data-disclosure > summary');
  await page.$eval('.data-disclosure', (node) => node.scrollIntoView({ block: 'start' }));
  await settle('.data-disclosure[open]');
  await shot('blotter-statement-data-after.png');
  await page.click('.mobile-back');
  await settle('.ledger-card');

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
  const accountGeometry = await page.evaluate(() => {
    const single = document.querySelector('.single-account')?.getBoundingClientRect();
    const group = document.querySelector('.group-header')?.getBoundingClientRect();
    return { singleHeight: Math.round(single?.height ?? 0), groupHeight: Math.round(group?.height ?? 0),
      singleCentre: single ? Math.round(single.top + single.height / 2) : 0,
      groupCentre: group ? Math.round(group.top + group.height / 2) : 0 };
  });
  if (!accountGeometry.singleHeight || Math.abs(accountGeometry.singleHeight - accountGeometry.groupHeight) > 1) {
    throw new Error(`Account headers do not share geometry: ${JSON.stringify(accountGeometry)}`);
  }
  await shot('accounts-after.png');

  await page.click('.group-header');
  await settle('.group-hero');
  await assertMobileBasics('/accounts/group/:group');
  const groupScroll = await page.$eval('.content', (node) => node.scrollTop);
  if (groupScroll > 1) throw new Error(`Group detail inherited ${groupScroll}px of list scroll`);
  const groupRoute = new URL(page.url()).pathname;
  await shot('account-group-after.png');
  await page.click('.group-subaccount-grid button');
  await settle('.balance-card');
  await assertMobileBasics('/accounts/:id');
  const subaccountScroll = await page.$eval('.content', (node) => node.scrollTop);
  if (subaccountScroll > 1) throw new Error(`Subaccount detail inherited ${subaccountScroll}px of group scroll`);
  const subaccountRoute = new URL(page.url()).pathname;
  await shot('account-subaccount-after.png');

  await visit('/accounts', '.single-account');
  await page.click('.single-account');
  await settle('.balance-card');
  await assertMobileBasics('/accounts/:id');
  const standaloneScroll = await page.$eval('.content', (node) => node.scrollTop);
  if (standaloneScroll > 1) throw new Error(`Standalone account inherited ${standaloneScroll}px of list scroll`);
  await shot('account-standalone-after.png');

  await visit('/tools', '.tool-grid');
  await shot('more-after.png');
  await visit('/profile', '.settings-grid');
  await shot('settings-after.png');

  // Light surfaces are checked independently; dark-only polish is not enough.
  for (const [route, selector, image] of [
    ['/summary', '.hero-figure', 'summary-light-after.png'],
    ['/accounts', '.account-groups', 'accounts-light-after.png'],
    ['/profile', '.settings-grid', 'settings-light-after.png'],
  ]) {
    await visit(route, selector);
    await page.evaluate(() => { document.documentElement.dataset.theme = 'light'; });
    await new Promise((resolve) => setTimeout(resolve, 100));
    await shot(image);
  }
  await page.evaluate(() => { document.documentElement.dataset.theme = 'dark'; });

  await visit('/reports', '.totals-card');
  await shot('reports-after.png');
  const flowWaiting = await page.$eval('finto-flow', (node) => !node.classList.contains('finto-in-view'));
  if (!flowWaiting) throw new Error('Below-fold flow chart animated before entering the viewport');
  await page.$eval('finto-flow', (node) => node.scrollIntoView({ block: 'center' }));
  await settle('finto-flow.finto-in-view');
  const flowRunning = await page.$eval('finto-flow .seg', (node) => getComputedStyle(node).animationPlayState);
  if (flowRunning !== 'running') throw new Error(`Flow chart did not animate on entry: ${flowRunning}`);
  await shot('reports-flow-after.png');

  await visit('/reports', '.totals-card');
  const edgesAtTop = await page.evaluate(() => ({
    top: document.querySelector('.scroll-edge.top')?.classList.contains('show'),
    bottom: document.querySelector('.scroll-edge.bottom')?.classList.contains('show'),
  }));
  if (edgesAtTop.top || !edgesAtTop.bottom) throw new Error(`Incorrect scroll edge state at top: ${JSON.stringify(edgesAtTop)}`);
  await page.evaluate(() => document.querySelector('.content')?.scrollTo(0, document.querySelector('.content')?.scrollHeight ?? 0));
  await new Promise((resolve) => setTimeout(resolve, 250));
  const edgesAtBottom = await page.evaluate(() => ({
    top: document.querySelector('.scroll-edge.top')?.classList.contains('show'),
    bottom: document.querySelector('.scroll-edge.bottom')?.classList.contains('show'),
  }));
  if (!edgesAtBottom.top || edgesAtBottom.bottom) throw new Error(`Incorrect scroll edge state at bottom: ${JSON.stringify(edgesAtBottom)}`);

  // The compact treatment must not damage the wider information-dense layout.
  await page.setViewport({ width: 1280, height: 800, deviceScaleFactor: 1 });
  for (const [route, selector, image] of [
    ['/summary', '.hero-figure', 'summary-desktop-after.png'],
    ['/blotter', '.ledger-card', 'blotter-desktop-after.png'],
    ['/accounts', '.account-groups', 'accounts-desktop-after.png'],
    ['/reports', '.totals-card', 'reports-desktop-after.png'],
    [groupRoute, '.group-hero', 'account-group-desktop-after.png'],
    [subaccountRoute, '.balance-card', 'account-subaccount-desktop-after.png'],
    ['/profile', '.settings-grid', 'settings-desktop-after.png'],
  ]) {
    await page.goto(`${baseUrl}${route}`, { waitUntil: 'networkidle0' });
    await settle(selector);
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    if (overflow > 1) throw new Error(`${route}: desktop horizontal overflow of ${overflow}px`);
    if (route === '/blotter') {
      const desktopAmounts = await page.evaluate(() => ({
        inline: getComputedStyle(document.querySelector('.aggregation-controls')).display,
        options: getComputedStyle(document.querySelector('.amount-options-trigger')).display,
      }));
      if (desktopAmounts.inline === 'none' || desktopAmounts.options !== 'none') {
        throw new Error(`Desktop amount controls lost their dense layout: ${JSON.stringify(desktopAmounts)}`);
      }
    }
    await shot(image);
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
