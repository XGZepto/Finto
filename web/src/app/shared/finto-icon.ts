import { Component, computed, effect, input, signal } from '@angular/core';

const CATEGORY_ALIASES: Record<string, string> = {
  software: 'services', subscription: 'services', utilities: 'housing',
  rent: 'housing', salary: 'income', transfer: 'other',
};
const CATEGORIES = new Set([
  'dining', 'groceries', 'transport', 'travel', 'shopping', 'services',
  'housing', 'health', 'entertainment', 'fees', 'interest', 'income',
  'rewards', 'other',
]);

/** Issuer → brand domain and fallback badge (colour + wordmark). */
const ISSUER: Record<string, { domain: string; bg: string; fg: string; label: string }> = {
  hsbc_hk: { domain: 'hsbc.com.hk', bg: '#db0011', fg: '#fff', label: 'HSBC' },
  hsbc: { domain: 'hsbc.com', bg: '#db0011', fg: '#fff', label: 'HSBC' },
  amex_hk: { domain: 'americanexpress.com', bg: '#006fcf', fg: '#fff', label: 'AMEX' },
  amex_us: { domain: 'americanexpress.com', bg: '#006fcf', fg: '#fff', label: 'AMEX' },
  amex: { domain: 'americanexpress.com', bg: '#006fcf', fg: '#fff', label: 'AMEX' },
  mox: { domain: 'mox.com', bg: '#00d0b0', fg: '#03110f', label: 'mox' },
  wise: { domain: 'wise.com', bg: '#9fe870', fg: '#163300', label: 'wise' },
  chase: { domain: 'chase.com', bg: '#117aca', fg: '#fff', label: 'Chase' },
  citi: { domain: 'citi.com', bg: '#003b70', fg: '#fff', label: 'citi' },
};

/** Gateway → brand domain and fallback badge. */
const GATEWAY: Record<string, { domain: string; bg: string; fg: string; label: string }> = {
  'apple pay': { domain: 'apple.com', bg: '#000', fg: '#fff', label: 'Pay' },
  'google pay': { domain: 'pay.google.com', bg: '#fff', fg: '#4285F4', label: 'GPay' },
  'wechat pay': { domain: 'wechat.com', bg: '#1AAD19', fg: '#fff', label: '微信' },
  alipay: { domain: 'alipay.com', bg: '#1677FF', fg: '#fff', label: '支付' },
  alipayhk: { domain: 'alipayhk.com', bg: '#1677FF', fg: '#fff', label: '支付' },
  tenpay: { domain: 'tenpay.com', bg: '#1AAD19', fg: '#fff', label: 'Ten' },
  unionpay: { domain: 'unionpayintl.com', bg: '#e21836', fg: '#fff', label: 'UP' },
  kpay: { domain: 'kpay.com', bg: '#111', fg: '#fff', label: 'KPay' },
  taobao: { domain: 'taobao.com', bg: '#ff4400', fg: '#fff', label: '淘' },
};

/** Known merchant → brand domain, so the logo query hits the right site. */
const MERCHANT_DOMAIN: Record<string, string> = {
  apple: 'apple.com', 'app store': 'apple.com', itunes: 'apple.com',
  netflix: 'netflix.com', spotify: 'spotify.com', disney: 'disneyplus.com',
  youtube: 'youtube.com', amazon: 'amazon.com', aws: 'aws.amazon.com',
  google: 'google.com', microsoft: 'microsoft.com', openai: 'openai.com',
  anthropic: 'anthropic.com', github: 'github.com', uber: 'uber.com',
  didi: 'didiglobal.com', 'didi taxi': 'didiglobal.com', grab: 'grab.com',
  lyft: 'lyft.com', starbucks: 'starbucks.com', mcdonald: 'mcdonalds.com',
  'mcdonalds': 'mcdonalds.com', kfc: 'kfc.com', 'pizza hut': 'pizzahut.com',
  'cathay pacific': 'cathaypacific.com', cathay: 'cathaypacific.com',
  hkexpress: 'hkexpress.com', 'singapore airlines': 'singaporeair.com',
  emirates: 'emirates.com', qatar: 'qatarairways.com', marriott: 'marriott.com',
  hilton: 'hilton.com', hyatt: 'hyatt.com', airbnb: 'airbnb.com',
  agoda: 'agoda.com', booking: 'booking.com', expedia: 'expedia.com',
  parknshop: 'parknshop.com', wellcome: 'wellcome.com.hk', 'city super': 'citysuper.com.hk',
  'hktvmall': 'hktvmall.com', watsons: 'watsons.com.hk', mannings: 'mannings.com.hk',
  ikea: 'ikea.com', uniqlo: 'uniqlo.com', muji: 'muji.com', zara: 'zara.com',
  nike: 'nike.com', adidas: 'adidas.com', lululemon: 'lululemon.com',
  mtr: 'mtr.com.hk', octopus: 'octopus.com.hk', foodpanda: 'foodpanda.com',
  deliveroo: 'deliveroo.com', meituan: 'meituan.com', 'shake shack': 'shakeshack.com',
  venchi: 'venchi.com', paypal: 'paypal.com', stripe: 'stripe.com',
  klook: 'klook.com', trip: 'trip.com', 'trip.com': 'trip.com',
};

/**
 * Brand mark for a merchant, category, gateway or issuer.
 *
 * A merchant or issuer resolves to a domain and shows its real logo; when no
 * logo loads, the mark falls back to a category line icon or a colour badge so
 * a row is never blank.
 */
@Component({
  selector: 'finto-icon',
  template: `
    <!-- The mark is never empty: the fallback always renders and the real logo
         fades in over it only once it has actually loaded. -->
    @if (badge(); as b) {
      <span class="badge" [style.background]="b.bg" [style.color]="b.fg"
            [class.wordmark]="b.label.length > 2">{{ b.label }}</span>
    } @else {
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"
           stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
        @switch (category()) {
          @case ('dining') { <path d="M6 3v7M4 3v4a2 2 0 0 0 2 2M8 3v4a2 2 0 0 1-2 2M6 12v9M17 3c-1.5 0-2.5 2-2.5 5s1 3 1 3v10" /> }
          @case ('groceries') { <path d="M4 7h16l-1.4 9.3a2 2 0 0 1-2 1.7H7.4a2 2 0 0 1-2-1.7L4 7Z" /><path d="M9 7 8 3M15 7l1-4" /> }
          @case ('transport') { <path d="M5 13l1.5-5A2 2 0 0 1 8.4 6.5h7.2a2 2 0 0 1 1.9 1.5L19 13M4 13h16v4H4zM7 17v2M17 17v2" /> }
          @case ('travel') { <path d="M3 15l18-6-3.5 9L14 15l-3 3-1-4-7 1Z" /> }
          @case ('shopping') { <path d="M6 8h12l-1 12H7L6 8Z" /><path d="M9 8a3 3 0 0 1 6 0" /> }
          @case ('services') { <circle cx="12" cy="12" r="3" /><path d="M12 2v3M12 19v3M2 12h3M19 12h3M5 5l2 2M17 17l2 2M19 5l-2 2M7 17l-2 2" /> }
          @case ('housing') { <path d="M4 11l8-7 8 7M6 10v9h12v-9" /> }
          @case ('health') { <path d="M12 21s-7-4.5-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 11c0 5.5-7 10-7 10Z" /> }
          @case ('entertainment') { <circle cx="12" cy="12" r="8" /><path d="M10 9l5 3-5 3V9Z" /> }
          @case ('fees') { <path d="M6 5h9l3 3v11H6V5Z" /><path d="M9 11h6M9 15h6" /> }
          @case ('interest') { <path d="M19 5 5 19M7.5 8a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3ZM16.5 19a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3Z" /> }
          @case ('income') { <path d="M12 4v10M8 11l4 4 4-4M5 20h14" /> }
          @case ('rewards') { <path d="m12 3 2.5 5.5L20 9l-4 4 1 6-5-3-5 3 1-6-4-4 5.5-.5L12 3Z" /> }
          @default { <circle cx="12" cy="12" r="7" /> }
        }
      </svg>
    }
    @if (logoUrl()) {
      <img class="logo" [class.shown]="loaded()" [src]="logoUrl()" alt=""
           loading="lazy" referrerpolicy="no-referrer"
           (load)="onLoad($event)" (error)="loaded.set(false)" />
    }
  `,
  styles: [`
    :host { position: relative; display: inline-grid; place-items: center; width: 100%; height: 100%; overflow: hidden; }
    /* Favicons ship as rounded-square tiles; Finto is square everywhere, so fill
       the box and scale just past the tile's own corner radius, which the host's
       overflow clips back into a clean square. */
    .logo {
      position: absolute; inset: 0;
      width: 100%; height: 100%; object-fit: cover;
      transform: scale(1.08);
      background: #fff;
      opacity: 0; transition: opacity 120ms linear;
    }
    .logo.shown { opacity: 1; }
    svg { width: 62%; height: 62%; }
    .badge {
      display: grid; place-items: center; width: 100%; height: 100%;
      font-family: var(--sans); font-weight: 700; font-size: 12px; line-height: 1;
    }
    .badge.wordmark { font-size: 8px; letter-spacing: -.02em; }
  `],
})
export class FintoIcon {
  kind = input<'merchant' | 'category' | 'gateway' | 'issuer'>('category');
  name = input<string>('other');
  /** For a merchant, the category is the fallback icon when no logo loads. */
  fallbackCategory = input<string>('other');

  loaded = signal(false);
  private key = computed(() => (this.name() || '').trim().toLowerCase());

  /** Show the logo only if it is sharp enough; a tiny favicon degrades to the
   *  fallback badge or icon rather than upscaling into a blurry mess. */
  onLoad(event: Event): void {
    const img = event.target as HTMLImageElement;
    this.loaded.set(img.naturalWidth >= 32);
  }

  constructor() {
    // A changed name is a different brand, so retry the logo.
    effect(() => { this.logoUrl(); this.loaded.set(false); });
  }

  /** hsbc_hk, amex_us … share one brand; drop the region suffix to match. */
  private issuerKey = computed(() => {
    const k = this.key();
    return ISSUER[k] ? k : k.replace(/_(hk|us|uk|sg|cn|au|ca|jp)$/, '');
  });

  private domain = computed<string | null>(() => {
    if (this.kind() === 'issuer') return ISSUER[this.issuerKey()]?.domain ?? null;
    if (this.kind() === 'gateway') return GATEWAY[this.key()]?.domain ?? null;
    if (this.kind() === 'merchant') {
      const k = this.key();
      if (MERCHANT_DOMAIN[k]) return MERCHANT_DOMAIN[k];
      for (const [needle, dom] of Object.entries(MERCHANT_DOMAIN)) {
        if (k.includes(needle)) return dom;
      }
    }
    return null;
  });

  logoUrl = computed(() => {
    const d = this.domain();
    return d ? `https://www.google.com/s2/favicons?sz=128&domain=${d}` : null;
  });

  badge = computed(() => {
    if (this.kind() === 'gateway') return GATEWAY[this.key()] ?? null;
    if (this.kind() === 'issuer') {
      return ISSUER[this.issuerKey()] ?? {
        domain: '', bg: 'var(--panel-3)', fg: 'var(--fg-2)',
        label: this.issuerKey().slice(0, 2).toUpperCase() || '—',
      };
    }
    return null;
  });

  category = computed(() => {
    const k = this.kind() === 'merchant' ? this.fallbackCategory().toLowerCase() : this.key();
    if (CATEGORIES.has(k)) return k;
    return CATEGORY_ALIASES[k] ?? 'other';
  });
}
