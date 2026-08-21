import fs from 'node:fs/promises';
import { existsSync } from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';
import puppeteer from 'puppeteer-core';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const output = path.join(root, '.artifacts/design/mobile');
const baseUrl = process.env.FINTO_CAPTURE_URL || 'http://127.0.0.1:4200';
const username = process.env.FINTO_CAPTURE_USER || 'owner';
const password = process.env.FINTO_CAPTURE_PASSWORD || 'local-dev';
const checkOnly = process.argv.includes('--check-only');
const executablePath = process.env.CHROME_PATH || [
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  '/usr/bin/google-chrome-stable',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium-browser',
  '/usr/bin/chromium',
].find((candidate) => existsSync(candidate));
if (!executablePath) throw new Error('Set CHROME_PATH to a Chrome or Chromium binary');

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

async function assertModalScrim(selector) {
  const material = await page.$eval(selector, (node) => {
    const style = getComputedStyle(node);
    return {
      background: style.backgroundColor,
      filter: style.backdropFilter || style.webkitBackdropFilter,
    };
  });
  if (!material.filter || material.filter === 'none' || material.background === 'rgba(0, 0, 0, 0)') {
    throw new Error(`${selector}: modal backdrop is not both darkened and blurred: ${JSON.stringify(material)}`);
  }
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
    const overflow = doc.scrollWidth - doc.clientWidth;
    const content = document.querySelector('.content');
    const page = document.querySelector('router-outlet + *');
    const nav = document.querySelector('.mobile-nav');
    const navBox = nav?.getBoundingClientRect();
    return {
      overflow,
      shortTargets,
      contentHeight: Math.round(content?.clientHeight ?? 0),
      hasPage: !!page && page.getBoundingClientRect().height > 0,
      navVisible: !!navBox && navBox.height > 0 && getComputedStyle(nav).display !== 'none',
    };
  });
  if (audit.overflow > 1) throw new Error(`${route}: horizontal overflow of ${audit.overflow}px`);
  if (audit.shortTargets.length) throw new Error(`${route}: touch targets below 44px: ${JSON.stringify(audit.shortTargets)}`);
  if (!audit.navVisible || !audit.hasPage || audit.contentHeight < 32) {
    throw new Error(`${route}: shell painted without a page: ${JSON.stringify({
      contentHeight: audit.contentHeight, hasPage: audit.hasPage, navVisible: audit.navVisible,
    })}`);
  }
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
  await page.evaluate(() => {
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'hidden' });
    document.dispatchEvent(new Event('visibilitychange'));
  });
  await page.evaluate(() => {
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'visible' });
    document.dispatchEvent(new Event('visibilitychange'));
  });
  await settle('.hero-figure');
  const afterResume = await page.evaluate(() => ({
    hero: !!document.querySelector('.hero-figure'),
    contentHeight: Math.round(document.querySelector('.content')?.clientHeight ?? 0),
  }));
  if (!afterResume.hero || afterResume.contentHeight < 32) {
    throw new Error(`Summary missing .hero-figure after visibility change: ${JSON.stringify(afterResume)}`);
  }
  const shellTransition = await page.evaluate(() => ({
    root: getComputedStyle(document.documentElement).viewTransitionName,
    page: getComputedStyle(document.querySelector('router-outlet + *')).viewTransitionName,
    nav: getComputedStyle(document.querySelector('.mobile-nav')).viewTransitionName,
  }));
  if (shellTransition.page !== 'none' || shellTransition.nav !== 'none') {
    throw new Error(`Route snapshots remain enabled: ${JSON.stringify(shellTransition)}`);
  }
  await shot('summary-after.png');
  const tickOverlap = await page.$$eval('.trend .tick', (nodes) => {
    const boxes = nodes.filter((node) => getComputedStyle(node).display !== 'none')
      .map((node) => node.getBoundingClientRect()).sort((a, b) => a.left - b.left);
    return boxes.some((box, index) => index > 0 && box.left < boxes[index - 1].right);
  });
  if (tickOverlap) throw new Error('Summary chart labels overlap');
  const donut = await page.$('finto-donut[fintoReveal]');
  if (donut) {
    const waitingDonut = await donut.evaluate((node) => ({
      revealed: node.classList.contains('finto-in-view'),
      opacity: getComputedStyle(node.querySelector('.arc')).opacity,
    }));
    if (!waitingDonut.revealed && waitingDonut.opacity !== '0') {
      throw new Error(`Summary donut flashes before reveal: ${JSON.stringify(waitingDonut)}`);
    }
    await donut.evaluate((node) => node.scrollIntoView({ block: 'center' }));
    await settle('finto-donut.finto-in-view');
    const revealedDonut = await donut.evaluate((node) => {
      const style = getComputedStyle(node.querySelector('.arc'));
      return { name: style.animationName, opacity: Number(style.opacity), transform: style.transform };
    });
    if (revealedDonut.name === 'none' || revealedDonut.opacity <= 0 || revealedDonut.transform === 'none') {
      throw new Error(`Summary donut did not reveal stably: ${JSON.stringify(revealedDonut)}`);
    }
    await page.evaluate(() => document.querySelector('.content')?.scrollTo(0, 0));
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  await page.click('finto-select.currency-select .select-trigger');
  await settle('.select-menu');
  await assertModalScrim('.select-scrim');
  const sheet = await page.$eval('.select-menu', (node) => {
    const box = node.getBoundingClientRect();
    const style = getComputedStyle(node);
    return { bottom: Math.round(box.bottom), viewport: innerHeight,
      ownsBottom: !!document.elementFromPoint(innerWidth / 2, innerHeight - 10)?.closest('.select-menu'),
      namedCurrencies: [...node.querySelectorAll('.option-copy strong')].some((item) => item.textContent?.includes('Dollar')),
      hasSearch: !!node.querySelector('input[type="search"]'), material: style.backdropFilter || style.webkitBackdropFilter };
  });
  if (Math.abs(sheet.viewport - sheet.bottom) > 1 || !sheet.ownsBottom) throw new Error('Select sheet is not above the tab bar at the viewport bottom');
  if (!sheet.namedCurrencies || !sheet.hasSearch || !sheet.material || sheet.material === 'none') throw new Error(`Currency sheet lacks names, search, or raised material: ${JSON.stringify(sheet)}`);
  const selectedCurrencyCopy = await page.$eval('.select-menu .select-option.selected', (node) => node.textContent ?? '');
  if ((selectedCurrencyCopy.match(/USD/g) ?? []).length !== 1 || !selectedCurrencyCopy.includes('US Dollar')) {
    throw new Error(`Currency identity is repetitive or incomplete: ${selectedCurrencyCopy.trim()}`);
  }
  const singleSelectMark = await page.$eval('.select-menu .select-option.selected .selection-mark', (node) => {
    const style = getComputedStyle(node);
    return { border: style.borderStyle, background: style.backgroundColor, text: node.textContent?.trim() };
  });
  if (singleSelectMark.border !== 'none' || singleSelectMark.text !== '✓') {
    throw new Error(`Single select still resembles multi-select: ${JSON.stringify(singleSelectMark)}`);
  }
  await shot('summary-currency-sheet-after.png');

  await visit('/blotter', '.ledger-card');
  const reviewChrome = await page.$('.review-progress, .review-value');
  if (reviewChrome) throw new Error('Ledger still exposes review-state chrome');
  const compactAmountControls = await page.evaluate(() => ({
    inline: getComputedStyle(document.querySelector('.aggregation-actions')).display,
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

  // A new top-level category must replace the whole taxonomy pair. Otherwise
  // the server validates the previous leaf under its new, incompatible parent.
  const openedCategorisedPicker = await page.$$eval('tr.clickable', (rows) => {
    const row = rows.find((item) => {
      const category = item.querySelector('td.category')?.textContent?.trim().toLowerCase();
      return category && !category.includes('uncategorised');
    });
    const button = row?.querySelector('.swipe-action button');
    if (!button) return false;
    button.click();
    return true;
  });
  if (!openedCategorisedPicker) throw new Error('Could not open a categorised row picker');
  await settle('.picker');
  const categoryPatch = page.waitForResponse((response) =>
    response.request().method() === 'PATCH' && /\/api\/transactions\/[^/]+$/.test(new URL(response.url()).pathname));
  const changedCategory = await page.$$eval('.picker-grid button', (buttons) => {
    const button = buttons.find((item) => !item.classList.contains('on'));
    if (!button) return false;
    button.click();
    return true;
  });
  if (!changedCategory) throw new Error('Could not choose a replacement category');
  const categoryResponse = await categoryPatch;
  const categoryPayload = JSON.parse(categoryResponse.request().postData() ?? '{}');
  if (!categoryResponse.ok() || categoryPayload.subcategory !== null) {
    throw new Error(`Top-level category did not clear its old subcategory: ${JSON.stringify({ status: categoryResponse.status(), payload: categoryPayload })}`);
  }
  await settle('.ledger-card');

  await page.click('.amount-options-trigger');
  await settle('.amount-options');
  await assertModalScrim('.amount-options-scrim');
  const optionsSheet = await page.$eval('.amount-options', (node) => {
    const box = node.getBoundingClientRect();
    const style = getComputedStyle(node);
    return { bottom: Math.round(innerHeight - box.bottom), hasMode: !!node.querySelector('.seg'), hasCurrency: !!node.querySelector('finto-select'), ownsFocus: node.contains(document.activeElement), material: style.backdropFilter || style.webkitBackdropFilter };
  });
  if (Math.abs(optionsSheet.bottom) > 1 || !optionsSheet.hasMode || !optionsSheet.hasCurrency || !optionsSheet.ownsFocus || !optionsSheet.material || optionsSheet.material === 'none') {
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
  await assertModalScrim('.drawer-backdrop');
  const detailAudit = await page.$eval('.drawer', (node) => ({
    overflow: node.scrollWidth - node.clientWidth,
    barVisible: getComputedStyle(node.querySelector('.bar-title')).display !== 'none',
    headerBackground: getComputedStyle(node.querySelector('.transaction-bar')).backgroundColor,
    disclosure: !!node.querySelector('.data-disclosure'),
    backTarget: Math.round(node.querySelector('.mobile-back')?.getBoundingClientRect().height ?? 0),
    editTarget: Math.round(node.querySelector('.bar-action')?.getBoundingClientRect().height ?? 0),
    amountHasCurrency: /[A-Z]{3}/.test(node.querySelector('.amount-hero')?.textContent ?? ''),
  }));
  if (detailAudit.overflow > 1 || detailAudit.barVisible || detailAudit.headerBackground !== 'rgba(0, 0, 0, 0)' || !detailAudit.disclosure || detailAudit.backTarget < 44 || detailAudit.editTarget < 44 || !detailAudit.amountHasCurrency) {
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
  await assertModalScrim('.sheet-scrim');
  await shot('blotter-filters-after.png');
  await page.click('.advanced.open finto-select[ariaLabel="Category"] .select-trigger');
  await settle('.select-menu');
  const sheetGesture = await page.$eval('.select-menu', (node) => {
    node.scrollTop = 0;
    const touch = (type, y) => node.dispatchEvent(new TouchEvent(type, {
      bubbles: true, cancelable: true, touches: type === 'touchend' ? [] : [new Touch({ identifier: 1, target: node, clientX: 100, clientY: y })],
      changedTouches: [new Touch({ identifier: 1, target: node, clientX: 100, clientY: y })],
    }));
    touch('touchstart', 260); touch('touchmove', 120); touch('touchend', 120);
    node.scrollTop = Math.min(80, node.scrollHeight - node.clientHeight);
    const ptr = document.querySelector('finto-pull-to-refresh .ptr');
    return { scrollTop: node.scrollTop, overflow: node.scrollHeight - node.clientHeight,
      refreshTransform: ptr ? getComputedStyle(ptr).transform : '' };
  });
  if (sheetGesture.overflow <= 0 || sheetGesture.scrollTop <= 0 || !['none', 'matrix(1, 0, 0, 1, 0, 0)'].includes(sheetGesture.refreshTransform)) {
    throw new Error(`Sheet gesture leaked to page refresh or cannot scroll: ${JSON.stringify(sheetGesture)}`);
  }
  await page.click('.select-menu .sheet-head button');
  await page.click('finto-date .date-trigger');
  await settle('finto-date .calendar');
  await assertModalScrim('.date-scrim');
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
  await assertModalScrim('.picker-scrim');
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
  const settingsAudit = await page.evaluate(() => ({
    text: document.querySelector('.settings-grid')?.textContent ?? '',
    apiCollapsed: !document.querySelector('.api-access')?.hasAttribute('open'),
    hasSignOut: !!document.querySelector('.identity .sign-out-action'),
  }));
  if (/PostgreSQL|Connected|Workspace|Import statements/.test(settingsAudit.text) || !settingsAudit.apiCollapsed || !settingsAudit.hasSignOut) {
    throw new Error(`Settings exposes implementation or redundant navigation: ${JSON.stringify(settingsAudit)}`);
  }
  await shot('settings-after.png');
  await page.click('.api-access > summary');
  await settle('.api-content');
  await assertMobileBasics('/profile#api-access');
  await shot('settings-api-after.png');

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

  await visit('/blotter', '.ledger-card');
  await page.evaluate(() => { document.documentElement.dataset.theme = 'light'; });
  await page.click('tr.clickable');
  await settle('.drawer .transaction-hero');
  const lightFloatShadow = await page.$eval('.mobile-back', (node) => getComputedStyle(node).boxShadow);
  if (!lightFloatShadow.includes('0.12')) throw new Error(`Light floating controls use a hard shadow: ${lightFloatShadow}`);
  await shot('blotter-transaction-light-after.png');
  await page.click('.mobile-back');
  await settle('.ledger-card');
  await clickText('button', 'Filters');
  await settle('.advanced.open');
  await page.click('.advanced.open finto-select[ariaLabel="Account"] .select-trigger');
  await settle('.select-menu');
  const lightSheetShadow = await page.$eval('.select-menu', (node) => getComputedStyle(node).boxShadow);
  if (!lightSheetShadow.includes('0.1')) throw new Error(`Light selector uses a hard shadow: ${lightSheetShadow}`);
  await shot('blotter-account-selector-light-after.png');
  await page.click('.select-menu .sheet-head button');
  await page.click('finto-date .date-trigger');
  await settle('finto-date .calendar');
  await shot('blotter-date-sheet-light-after.png');
  await page.click('finto-date .sheet-head button');
  await page.evaluate(() => { document.documentElement.dataset.theme = 'dark'; });

  await visit('/reports', '.totals-card');
  await shot('reports-after.png');
  const flowWaiting = await page.$eval('finto-flow', (node) => !node.classList.contains('finto-in-view'));
  if (!flowWaiting) throw new Error('Below-fold flow chart animated before entering the viewport');
  await page.$eval('finto-flow', (node) => node.scrollIntoView({ block: 'center' }));
  await settle('finto-flow.finto-in-view');
  const flowMotion = await page.$eval('finto-flow .seg', (node) => {
    const style = getComputedStyle(node);
    return { name: style.animationName, state: style.animationPlayState };
  });
  if (flowMotion.name === 'none' || flowMotion.state !== 'running') {
    throw new Error(`Flow chart did not animate on entry: ${JSON.stringify(flowMotion)}`);
  }
  await shot('reports-flow-after.png');

  if (await page.$('.scroll-edge')) throw new Error('Decorative scroll-edge chrome returned');

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
        inline: getComputedStyle(document.querySelector('.aggregation-actions')).display,
        options: getComputedStyle(document.querySelector('.amount-options-trigger')).display,
      }));
      if (desktopAmounts.inline === 'none' || desktopAmounts.options !== 'none') {
        throw new Error(`Desktop amount controls lost their dense layout: ${JSON.stringify(desktopAmounts)}`);
      }
    }
    await shot(image);
  }

  await page.goto(`${baseUrl}/blotter`, { waitUntil: 'networkidle0' });
  await settle('.ledger-card');
  await page.click('tr.clickable');
  await settle('.drawer .transaction-hero');
  const desktopDrawer = await page.$eval('.drawer', (node) => {
    const header = node.querySelector('.transaction-bar');
    const back = node.querySelector('.mobile-back');
    const edit = node.querySelector('.bar-action');
    const close = node.querySelector('.desktop-close');
    const centre = (item) => {
      const box = item.getBoundingClientRect();
      return Math.round(box.top + box.height / 2);
    };
    return {
      back: getComputedStyle(back).display,
      headerHeight: Math.round(header.getBoundingClientRect().height),
      editHeight: Math.round(edit.getBoundingClientRect().height),
      closeHeight: Math.round(close.getBoundingClientRect().height),
      alignment: Math.abs(centre(edit) - centre(close)),
    };
  });
  if (desktopDrawer.back !== 'none' || desktopDrawer.headerHeight > 65 || desktopDrawer.editHeight < 44 || desktopDrawer.closeHeight < 44 || desktopDrawer.alignment > 1) {
    throw new Error(`Desktop transaction actions are misaligned: ${JSON.stringify(desktopDrawer)}`);
  }
  await shot('blotter-transaction-desktop-after.png');

  // The field screenshots came from a ~360 CSS-pixel Android viewport. Keep a
  // dedicated regression pass at that width instead of assuming 390 is close.
  await page.setViewport({ width: 360, height: 800, deviceScaleFactor: 2, isMobile: true, hasTouch: true });
  await visit('/reports', '.totals-card');
  const narrowReport = await page.evaluate(() => {
    const figures = [...document.querySelectorAll('.totals-row b')];
    const boxes = figures.map((node) => node.getBoundingClientRect());
    return {
      overflow: figures.map((node) => node.scrollWidth - node.clientWidth),
      overlap: boxes.some((box, index) => boxes.some((other, otherIndex) =>
        otherIndex > index && box.left < other.right && box.right > other.left &&
        box.top < other.bottom && box.bottom > other.top)),
    };
  });
  if (narrowReport.overlap || narrowReport.overflow.some((value) => value > 1)) {
    throw new Error(`Narrow report figures collide: ${JSON.stringify(narrowReport)}`);
  }
  await shot('reports-narrow-after.png');

  await visit('/blotter?from=2025-09-01&uncategorisedOnly=true', '.ledger-card');
  const narrowFilter = await page.evaluate(() => ({
    chips: getComputedStyle(document.querySelector('.filter-bar .chips')).display,
    height: Math.round(document.querySelector('.filter-bar').getBoundingClientRect().height),
  }));
  if (narrowFilter.chips !== 'none' || narrowFilter.height > 112) {
    throw new Error(`Active mobile filters consume the ledger: ${JSON.stringify(narrowFilter)}`);
  }
  await shot('blotter-active-filters-narrow-after.png');

  await visit('/accounts', '.account-groups');
  const narrowAccounts = await page.evaluate(() => ({
    childMetadata: document.querySelectorAll('.account-child .child-main small').length,
    tallestChild: Math.max(0, ...[...document.querySelectorAll('.account-child')]
      .map((node) => Math.round(node.getBoundingClientRect().height))),
  }));
  if (narrowAccounts.childMetadata || narrowAccounts.tallestChild > 54) {
    throw new Error(`Account rows remain crowded: ${JSON.stringify(narrowAccounts)}`);
  }
  await shot('accounts-narrow-after.png');

  await visit('/summary', '.hero-figure');
  if (await page.$('.hero-action')) throw new Error('Summary still contains the redundant month shortcut');
  const navFrames = await page.evaluate(async () => {
    document.querySelector('.mobile-nav a[href="/reports"]')?.click();
    const frames = [];
    for (let index = 0; index < 18; index++) {
      await new Promise((resolve) => requestAnimationFrame(resolve));
      const nav = document.querySelector('.mobile-nav');
      const box = nav.getBoundingClientRect();
      frames.push({ top: Math.round(box.top), height: Math.round(box.height),
        opacity: getComputedStyle(nav).opacity,
        visible: [...nav.querySelectorAll('a')].filter((node) => node.getBoundingClientRect().height > 0).length });
    }
    return frames;
  });
  const firstFrame = navFrames[0];
  if (navFrames.some((frame) => frame.top !== firstFrame.top || frame.height !== firstFrame.height ||
      frame.opacity !== '1' || frame.visible !== 5)) {
    throw new Error(`Persistent navigation flickers during routing: ${JSON.stringify(navFrames)}`);
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
