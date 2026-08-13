import { Injectable, inject, signal } from '@angular/core';
import { Api, AuthUser } from './api.service';

export type ThemePreference = 'system' | 'dark' | 'light';
export type LanguagePreference = 'en' | 'zh-Hant';

const THEME_KEY = 'finto.theme';
const LANGUAGE_KEY = 'finto.language';
const CURRENCY_KEY = 'finto.baseCurrency';

@Injectable({ providedIn: 'root' })
export class Preferences {
  private api = inject(Api);
  theme = signal<ThemePreference>(this.readTheme());
  language = signal<LanguagePreference>(this.readLanguage());
  baseCurrency = signal(this.readCurrency());
  user = signal<AuthUser | null>(null);

  constructor() {
    this.applyTheme(this.theme());
    this.applyLanguage(this.language());
    // On 'system' the resolved theme still changes under us.
    matchMedia('(prefers-color-scheme: dark)')
      .addEventListener('change', () => this.applyTheme(this.theme()));
  }

  setTheme(theme: ThemePreference): void {
    this.theme.set(theme);
    localStorage.setItem(THEME_KEY, theme);
    this.applyTheme(theme);
    this.api.updatePreferences({ theme }).subscribe({
      next: (user) => this.user.set(user), error: () => undefined,
    });
  }

  setLanguage(language: LanguagePreference): void {
    this.language.set(language);
    localStorage.setItem(LANGUAGE_KEY, language);
    this.applyLanguage(language);
    this.api.updatePreferences({ language }).subscribe({
      next: (user) => this.user.set(user), error: () => undefined,
    });
  }

  setBaseCurrency(currency: string): void {
    const base_currency = currency.toUpperCase();
    this.baseCurrency.set(base_currency);
    localStorage.setItem(CURRENCY_KEY, base_currency);
    this.api.updatePreferences({ base_currency }).subscribe({
      next: (user) => this.user.set(user), error: () => undefined,
    });
  }

  loadUser(): void {
    this.api.me().subscribe({ next: (user) => this.applyUser(user), error: () => undefined });
  }

  applyUser(user: AuthUser): void {
    this.user.set(user);
    const theme = user.preferences.theme;
    const language = user.preferences.language;
    const baseCurrency = user.preferences.base_currency;
    if (theme === 'system' || theme === 'dark' || theme === 'light') {
      this.theme.set(theme);
      localStorage.setItem(THEME_KEY, theme);
      this.applyTheme(theme);
    }
    if (language === 'en' || language === 'zh-Hant') {
      this.language.set(language);
      localStorage.setItem(LANGUAGE_KEY, language);
      this.applyLanguage(language);
    }
    if (baseCurrency?.match(/^[A-Z]{3}$/)) {
      this.baseCurrency.set(baseCurrency);
      localStorage.setItem(CURRENCY_KEY, baseCurrency);
    }
  }

  text(en: string, zh: string): string {
    return this.language() === 'zh-Hant' ? zh : en;
  }

  private readTheme(): ThemePreference {
    const saved = localStorage.getItem(THEME_KEY);
    return saved === 'dark' || saved === 'light' ? saved : 'system';
  }

  private readLanguage(): LanguagePreference {
    return localStorage.getItem(LANGUAGE_KEY) === 'zh-Hant' ? 'zh-Hant' : 'en';
  }

  private readCurrency(): string {
    const saved = localStorage.getItem(CURRENCY_KEY)?.toUpperCase();
    return saved?.match(/^[A-Z]{3}$/) ? saved : 'USD';
  }

  private applyTheme(theme: ThemePreference): void {
    const root = document.documentElement;
    if (theme === 'system') root.removeAttribute('data-theme');
    else root.dataset['theme'] = theme;
    // Read the surface back rather than restating its hex here, so the browser
    // chrome cannot drift from the palette it is supposed to continue.
    const bg = getComputedStyle(root).getPropertyValue('--bg').trim();
    document.querySelector('meta[name="theme-color"]')?.setAttribute('content', bg);
  }

  private applyLanguage(language: LanguagePreference): void {
    document.documentElement.lang = language;
  }
}
