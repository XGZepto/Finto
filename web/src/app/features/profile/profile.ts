import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { RouterLink } from '@angular/router';
import { Api, ApiKeyMeta } from '../../core/api.service';
import {
  LanguagePreference, Preferences, ThemePreference,
} from '../../core/preferences.service';
import { FintoSelect } from '../../shared/finto-select';

@Component({
  selector: 'app-profile',
  imports: [FormsModule, RouterLink, FintoSelect],
  templateUrl: './profile.html',
  styleUrl: './profile.css',
})
export class ProfilePage {
  private api = inject(Api);
  private router = inject(Router);
  preferences = inject(Preferences);
  apiKeys = signal<ApiKeyMeta[]>([]);
  newApiKey = signal('');
  copied = signal(false);

  constructor() {
    this.preferences.loadUser();
    this.loadApiKeys();
  }
  readonly themes = [
    { value: 'system', en: 'System', zh: '跟隨系統' },
    { value: 'dark', en: 'Dark', zh: '深色' },
    { value: 'light', en: 'Light', zh: '淺色' },
  ];
  readonly languages = [
    { value: 'en', label: 'English' },
    { value: 'zh-Hant', label: '繁體中文' },
  ];
  readonly currencies = ['USD', 'HKD', 'GBP', 'EUR', 'JPY', 'CNY', 'SGD', 'AUD', 'CAD'];

  themeOptions() {
    return this.themes.map((item) => ({ value: item.value, label: this.t(item.en, item.zh) }));
  }

  t(en: string, zh: string): string { return this.preferences.text(en, zh); }
  setTheme(value: ThemePreference): void { this.preferences.setTheme(value); }
  setLanguage(value: LanguagePreference): void { this.preferences.setLanguage(value); }
  setBaseCurrency(value: string): void { this.preferences.setBaseCurrency(value); }
  loadApiKeys(): void {
    this.api.apiKeys().subscribe({ next: (r) => this.apiKeys.set(r.keys) });
  }
  generateApiKey(): void {
    this.api.createApiKey().subscribe({ next: (r) => {
      this.newApiKey.set(r.key); this.copied.set(false); this.loadApiKeys();
    }});
  }
  copyApiKey(): void {
    void navigator.clipboard.writeText(this.newApiKey()).then(() => this.copied.set(true));
  }
  revokeApiKey(id: string): void {
    this.api.revokeApiKey(id).subscribe({ next: () => this.loadApiKeys() });
  }
  logout(): void { this.api.logout().subscribe({ next: () => this.router.navigate(['/login']) }); }
}
