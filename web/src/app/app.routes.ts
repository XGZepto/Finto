import { Routes } from '@angular/router';

export const routes: Routes = [
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
  { path: '**', redirectTo: 'summary' },
];
