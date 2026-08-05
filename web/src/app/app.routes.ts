import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () => import('./features/auth/login').then((m) => m.LoginPage),
    title: 'Sign in · Finto',
  },
  { path: '', redirectTo: 'summary', pathMatch: 'full' },
  {
    path: 'summary',
    loadComponent: () => import('./features/summary/summary').then((m) => m.SummaryPage),
    title: 'Summary · Finto',
  },
  {
    path: 'blotter',
    loadComponent: () => import('./features/blotter/blotter').then((m) => m.BlotterPage),
    title: 'Blotter · Finto',
  },
  {
    path: 'timeline',
    loadComponent: () => import('./features/timeline/timeline').then((m) => m.TimelinePage),
    title: 'Timeline · Finto',
  },
  {
    path: 'accounts/:id',
    loadComponent: () => import('./features/accounts/accounts').then((m) => m.AccountsPage),
    title: 'Account · Finto',
  },
  {
    path: 'accounts',
    loadComponent: () => import('./features/accounts/accounts').then((m) => m.AccountsPage),
    title: 'Accounts · Finto',
  },
  {
    path: 'import',
    loadComponent: () => import('./features/import/import').then((m) => m.ImportPage),
    title: 'Import · Finto',
  },
  {
    path: 'installments',
    loadComponent: () =>
      import('./features/installments/installments').then((m) => m.InstallmentsPage),
    title: 'Instalments · Finto',
  },
  {
    path: 'investments',
    loadComponent: () =>
      import('./features/investments/investments').then((m) => m.InvestmentsPage),
    title: 'Investments · Finto',
  },
  {
    path: 'review',
    loadComponent: () => import('./features/review/review').then((m) => m.ReviewPage),
    title: 'Review · Finto',
  },
  {
    path: 'integrity',
    loadComponent: () => import('./features/integrity/integrity').then((m) => m.IntegrityPage),
    title: 'Integrity · Finto',
  },
  {
    path: 'ask',
    loadComponent: () => import('./features/ask/ask').then((m) => m.AskPage),
    title: 'Ask · Finto',
  },
  {
    path: 'profile',
    loadComponent: () => import('./features/profile/profile').then((m) => m.ProfilePage),
    title: 'Profile & settings · Finto',
  },
  { path: '**', redirectTo: 'summary' },
];
