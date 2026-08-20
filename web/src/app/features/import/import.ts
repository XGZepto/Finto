import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api } from '../../core/api.service';
import { MoneyPipe, ShortDatePipe } from '../../core/money.pipe';
import { Facets, ImportCapabilities, ImportFormat, Job, StagePreview } from '../../core/models';
import { FintoSelect } from '../../shared/finto-select';

/**
 * One staged file, from drop to committed.
 *
 * `preview` is what the parser *would* produce; nothing has touched the ledger
 * until `job` exists.
 */
interface Entry {
  key: string;
  filename: string;
  sizeBytes: number;
  file: File;
  state: 'staging' | 'ready' | 'refused' | 'importing' | 'done' | 'failed';
  preview?: StagePreview;
  error?: string;
  job?: Job;
  institutionId: string;
  accountId: string;
  currency: string;
}

/**
 * Import.
 *
 * Upload stages the file and shows what the parser made of it; nothing reaches
 * the ledger until that preview is confirmed. The column mappings for several
 * institutions are informed guesses, and this preview is the only place a wrong
 * one is cheap to catch — a dd/mm read as mm/dd is glaring next to the raw
 * header row and invisible six months later.
 *
 * Refusals are spelled out rather than reported as a generic failure, because
 * "no parser matched" and "this PDF has no text layer" call for different
 * actions from the person reading it.
 */
@Component({
  selector: 'app-import',
  imports: [FormsModule, MoneyPipe, ShortDatePipe, FintoSelect],
  templateUrl: './import.html',
  styleUrl: './import.css',
})
export class ImportPage {
  private api = inject(Api);

  facets = signal<Facets | null>(null);
  entries = signal<Entry[]>([]);
  history = signal<any[]>([]);
  capabilities = signal<ImportCapabilities | null>(null);
  formatsOpen = signal(false);
  dragging = signal(false);
  maintenance = signal<Job | null>(null);

  /** Applied to every file dropped afterwards, so a batch is configured once. */
  defaultInstitution = signal('');
  defaultAccount = signal('');
  defaultCurrency = signal('');

  private seq = 0;

  constructor() {
    this.api.facets().subscribe({ next: (f) => this.facets.set(f) });
    this.api.importCapabilities().subscribe({ next: (value) => this.capabilities.set(value) });
    this.loadHistory();
  }

  formatGroups = computed(() => {
    const groups = new Map<string, ImportFormat[]>();
    for (const format of this.capabilities()?.formats ?? []) {
      groups.set(format.file_format, [...(groups.get(format.file_format) ?? []), format]);
    }
    return [...groups].map(([fileFormat, formats]) => ({
      fileFormat,
      label: fileFormat.toUpperCase(),
      formats,
    }));
  });

  acceptedExtensions = computed(() => this.capabilities()?.extensions ?? ['.csv', '.pdf']);

  acceptAttribute(): string {
    return this.acceptedExtensions().join(',');
  }

  acceptedLabel(): string {
    return this.acceptedExtensions().map((value) => value.slice(1)).join(', ');
  }

  isWorkspaceFormat(format: ImportFormat): boolean {
    return format.source !== 'builtin' && format.source !== 'bundled';
  }

  loadHistory(): void {
    this.api.importHistory().subscribe({ next: (r) => this.history.set(r.files) });
  }

  /** Accounts filtered to the chosen institution, so the pair can't disagree. */
  accountOptions() {
    const all = this.facets()?.accounts ?? [];
    const inst = this.defaultInstitution();
    return inst ? all.filter((a) => a.institution_id === inst) : all;
  }

  institutionOptions() {
    return [{ id: '', display_name: 'detect' }, ...(this.facets()?.institutions ?? [])];
  }

  allAccountOptions() {
    return [{ id: '', display_name: 'detect' }, ...(this.facets()?.accounts ?? [])];
  }

  detectedAccountOptions() {
    return [{ id: '', display_name: 'detect' }, ...this.accountOptions()];
  }

  currencyOptions() {
    return [{ value: '', label: 'detect' },
      ...(this.facets()?.currencies ?? []).map((value) => ({ value, label: value }))];
  }

  onDragOver(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(true);
  }

  onDragLeave(): void {
    this.dragging.set(false);
  }

  onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(false);
    const files = event.dataTransfer?.files;
    if (files) this.addFiles(files);
  }

  onPick(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files) this.addFiles(input.files);
    input.value = '';
  }

  addFiles(files: FileList): void {
    for (const file of Array.from(files)) this.stage(file);
  }

  private stage(file: File): void {
    const key = `f${this.seq++}`;
    const meta = {
      institution_id: this.defaultInstitution() || undefined,
      account_id: this.defaultAccount() || undefined,
      currency: this.defaultCurrency() || undefined,
    };
    const entry: Entry = {
      key,
      filename: file.name,
      sizeBytes: file.size,
      file,
      state: 'staging',
      institutionId: this.defaultInstitution(),
      accountId: this.defaultAccount(),
      currency: this.defaultCurrency(),
    };
    this.entries.update((list) => [entry, ...list]);

    this.api.stage(file, meta).subscribe({
      next: (preview) =>
        this.patch(key, {
          preview,
          state: preview.error || !preview.parser ? 'refused' : 'ready',
          error: preview.error,
        }),
      error: (err) =>
        this.patch(key, {
          state: 'refused',
          error: err?.error?.detail ?? 'Upload failed.',
        }),
    });
  }

  confirm(entry: Entry): void {
    if (!entry.preview) return;
    this.patch(entry.key, { state: 'importing' });
    this.api
      .confirmImport(entry.file, entry.preview.sha256, {
        institution_id: entry.institutionId || undefined,
        account_id: entry.accountId || undefined,
        currency: entry.currency || undefined,
      })
      .subscribe({
        next: (result) => {
          const job = this.completedJob('import', result);
          const failed = result?.import?.status === 'error';
          this.patch(entry.key, { job, state: failed ? 'failed' : 'done' });
          this.loadHistory();
        },
        error: (err) =>
          this.patch(entry.key, {
            state: 'failed',
            error: err?.error?.detail ?? 'Import could not be queued.',
          }),
      });
  }

  discard(entry: Entry): void {
    this.entries.update((list) => list.filter((e) => e.key !== entry.key));
  }

  clearFinished(): void {
    this.entries.update((list) => list.filter((e) => e.state !== 'done'));
  }

  // --- Maintenance ---------------------------------------------------------

  runReconcile(): void {
    this.run(this.api.reconcile());
  }

  runReattribute(): void {
    this.run(this.api.reattribute());
  }

  runHarvestFx(): void {
    this.run(this.api.harvestFx());
  }

  private run(request: ReturnType<Api['reconcile']>): void {
    this.maintenance.set({
      id: 'maintenance', kind: 'maintenance', status: 'running', progress: 'working',
      result: null, error: null, created_at: new Date().toISOString(), finished_at: null,
    });
    request.subscribe({
      next: (result) => this.maintenance.set(this.completedJob('maintenance', result)),
      error: (err) => this.maintenance.set({
        ...this.completedJob('maintenance', null),
        status: 'error',
        error: err?.error?.detail ?? 'Maintenance failed.',
      }),
    });
  }

  private completedJob(kind: string, result: any): Job {
    const now = new Date().toISOString();
    return {
      id: `${kind}-${now}`,
      kind,
      status: 'done',
      progress: 'complete',
      result,
      error: null,
      created_at: now,
      finished_at: now,
    };
  }

  private patch(key: string, change: Partial<Entry>): void {
    this.entries.update((list) =>
      list.map((e) => (e.key === key ? { ...e, ...change } : e)),
    );
  }

  // --- Rendering helpers ---------------------------------------------------

  kb(bytes: number): string {
    return bytes < 1024 ? `${bytes} B` : `${Math.round(bytes / 1024).toLocaleString()} KB`;
  }

  headerRow(entry: Entry): string {
    return (entry.preview?.header ?? []).join('  │  ');
  }

  /** The reconcile summary, flattened into label/value pairs worth showing. */
  changes(job: Job | undefined): Array<{ label: string; value: number; notable: boolean }> {
    const r = job?.result?.reconcile;
    if (!r) return [];
    const fields: Array<[string, string, boolean]> = [
      ['transactions', 'Transactions in ledger', false],
      ['duplicates_merged', 'Duplicates merged', false],
      ['duplicate_candidates', 'Duplicates to review', true],
      ['transfers_linked', 'Transfers linked', false],
      ['transfer_candidates', 'Transfers to review', true],
      ['installment_plans', 'Instalment plans', false],
      ['installment_candidates', 'Instalments to review', true],
      ['refunds_linked', 'Refunds linked', false],
    ];
    return fields
      .filter(([key]) => r[key])
      .map(([key, label, notable]) => ({ label, value: r[key], notable }));
  }

  balanceProblems(job: Job | undefined): any[] {
    return job?.result?.reconcile?.balance_checks ?? [];
  }

  /** Scalar fields of any job result, for the maintenance actions. */
  resultPairs(job: Job | null): Array<{ label: string; value: unknown }> {
    const r = job?.result;
    if (!r || typeof r !== 'object') return [];
    return Object.entries(r)
      .filter(([, v]) => typeof v === 'number' || typeof v === 'string')
      .filter(([, v]) => v !== 0)
      .map(([key, value]) => ({ label: key.replace(/_/g, ' '), value }));
  }
}
