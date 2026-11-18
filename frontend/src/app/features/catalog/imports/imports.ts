import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTableModule } from '@angular/material/table';
import { MatTabsModule } from '@angular/material/tabs';
import { filter, finalize, switchMap } from 'rxjs';
import { BrandResponse } from '../../../api/models/brand-response';
import { CategoryResponse } from '../../../api/models/category-response';
import { DataExchangeImportJobResponse } from '../../../api/models/data-exchange-import-job-response';
import { DataExchangeImportRowError } from '../../../api/models/data-exchange-import-row-error';
import { AuthService } from '../../../core/auth/auth.service';
import { isApiError } from '../../../core/http/api-error';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { StoreContextService } from '../../../core/store-context/store-context.service';
import { ConfirmDialogService } from '../../../shared/ui/confirm-dialog';
import {
  DownloadedFile,
  ExportFormat,
  ImportType,
  ImportsService,
  MAX_IMPORT_FILE_BYTES,
} from './imports.service';

/** Opción de tipo de importación con etiqueta y descripción para la UI. */
export interface ImportTypeOption {
  value: ImportType;
  label: string;
  description: string;
}

/** Opción plana de categoría para el selector de filtros de exportación. */
export interface CategorySelectOption {
  id: number;
  label: string;
}

/** Opciones disponibles de importación masiva. */
export const IMPORT_TYPE_OPTIONS: readonly ImportTypeOption[] = [
  {
    value: 'PRODUCTS',
    label: 'Productos',
    description: 'Crea o actualiza productos, categorías, marcas, precios y códigos de barras.',
  },
  {
    value: 'PRODUCT_SUPPLIERS',
    label: 'Productos por proveedor',
    description: 'Asocia SKU de productos con proveedores, códigos de proveedor y costos.',
  },
  {
    value: 'INITIAL_STOCK',
    label: 'Stock inicial',
    description: 'Carga existencias iniciales en el local activo (y lotes si corresponde).',
  },
];

/** Etiquetas legibles para los estados de un trabajo de importación. */
export const IMPORT_STATUS_LABELS: Readonly<Record<string, string>> = {
  PENDING: 'Pendiente',
  VALIDATED: 'Validado',
  PROCESSING: 'Procesando',
  COMPLETED: 'Completado',
  FAILED: 'Fallido',
};

/** Extensiones de archivo aceptadas para importación masiva. */
const ACCEPTED_EXTENSIONS = ['.csv', '.xlsx', '.xls'];

/** Aplana el árbol de categorías en una lista ordenada con sangría legible. */
function flattenCategoryTree(
  categories: readonly CategoryResponse[],
  prefix = '',
): CategorySelectOption[] {
  const result: CategorySelectOption[] = [];
  for (const cat of categories) {
    if (cat.id === undefined || !cat.name) {
      continue;
    }
    const label = prefix ? `${prefix} › ${cat.name}` : cat.name;
    result.push({ id: cat.id, label });
    if (cat.children?.length) {
      result.push(...flattenCategoryTree(cat.children, label));
    }
  }
  return result;
}

/** Dispara la descarga en el navegador de un {@link DownloadedFile}. */
function triggerBrowserDownload(file: DownloadedFile): void {
  if (typeof URL === 'undefined' || typeof URL.createObjectURL !== 'function') {
    return;
  }
  const objectUrl = URL.createObjectURL(file.blob);
  const link = document.createElement('a');
  link.href = objectUrl;
  link.download = file.fileName;
  link.click();
  URL.revokeObjectURL(objectUrl);
}

/** Formatea bytes a una cadena legible en KB/MB. */
function formatFileSize(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  const kb = bytes / 1024;
  if (kb < 1024) {
    return `${kb.toFixed(1)} KB`;
  }
  return `${(kb / 1024).toFixed(2)} MB`;
}

/**
 * Pantalla de importación y exportación masiva del catálogo (`/catalogo/importaciones`,
 * `HU-CAT-10`, `HU-CAT-11`).
 *
 * Incluye dos pestañas:
 * - **Importación masiva** (`catalog:product:import`): descarga de plantillas CSV/XLSX,
 *   validación local de tamaño (<= 20 MB), ejecución en modo simulación (`dryRun=true`) o
 *   directa (`dryRun=false`), resumen de filas, tabla de errores por fila y campo,
 *   confirmación transaccional y consulta por ID.
 * - **Exportación del catálogo** (`catalog:product:export`): filtros equivalentes al listado de
 *   productos, formato CSV/XLSX, descarga binaria directa y aviso cuando falta
 *   `catalog:cost:read`.
 */
@Component({
  selector: 'pos-imports',
  imports: [
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    MatSelectModule,
    MatTableModule,
    MatTabsModule,
    ReactiveFormsModule,
  ],
  templateUrl: './imports.html',
  styleUrl: './imports.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Imports implements OnInit {
  private readonly importsService = inject(ImportsService);
  private readonly auth = inject(AuthService);
  private readonly storeContext = inject(StoreContextService);
  private readonly confirmDialog = inject(ConfirmDialogService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly fb = inject(NonNullableFormBuilder);

  protected readonly importTypeOptions = IMPORT_TYPE_OPTIONS;
  protected readonly errorColumns: readonly string[] = ['row', 'field', 'message'];

  protected readonly canImport = computed(() => this.auth.hasPermission('catalog:product:import'));
  protected readonly canExport = computed(() => this.auth.hasPermission('catalog:product:export'));
  protected readonly canReadCosts = computed(() => this.auth.hasPermission('catalog:cost:read'));
  protected readonly activeStore = this.storeContext.activeStore;
  protected readonly activeStoreId = this.storeContext.storeId;

  protected readonly categories = signal<readonly CategorySelectOption[]>([]);
  protected readonly brands = signal<readonly BrandResponse[]>([]);

  protected readonly selectedFile = signal<File | null>(null);
  protected readonly fileValidationError = signal<string | null>(null);
  protected readonly importError = signal<string | null>(null);
  protected readonly exportError = signal<string | null>(null);

  protected readonly downloadingTemplate = signal(false);
  protected readonly submittingImport = signal(false);
  protected readonly confirmingImport = signal(false);
  protected readonly loadingJob = signal(false);
  protected readonly exporting = signal(false);

  protected readonly currentJob = signal<DataExchangeImportJobResponse | null>(null);

  protected readonly importForm = this.fb.group({
    importType: ['PRODUCTS' as ImportType, Validators.required],
  });

  protected readonly lookupForm = this.fb.group({
    jobId: ['', [Validators.required, Validators.pattern(/^\d+$/)]],
  });

  protected readonly exportForm = this.fb.group({
    format: ['xlsx' as ExportFormat, Validators.required],
    q: [''],
    categoryId: [null as number | null],
    brandId: [null as number | null],
    saleType: [null as 'UNIT' | 'WEIGHT' | 'PACK' | null],
    active: [null as boolean | null],
    restricted: [null as boolean | null],
    lotControlled: [null as boolean | null],
  });

  /** Descripción del tipo de importación seleccionado. */
  protected readonly selectedImportDescription = computed(() => {
    const type = this.importForm.controls.importType.value;
    return this.importTypeOptions.find((opt) => opt.value === type)?.description ?? '';
  });

  /** Indica si hay una operación de importación en curso. */
  protected readonly isImportBusy = computed(
    () =>
      this.submittingImport() ||
      this.confirmingImport() ||
      this.loadingJob() ||
      this.downloadingTemplate(),
  );

  /** Lista de errores por fila y campo del trabajo actual. */
  protected readonly jobErrors = computed<readonly DataExchangeImportRowError[]>(
    () => this.currentJob()?.errors ?? [],
  );

  /** Indica si el trabajo actual puede confirmarse (simulación validada y sin errores). */
  protected readonly canConfirmCurrentJob = computed(() => {
    const job = this.currentJob();
    if (!job || job.id === undefined) {
      return false;
    }
    return (
      job.dryRun === true &&
      job.status === 'VALIDATED' &&
      (job.errorRows ?? 0) === 0 &&
      !this.confirmingImport()
    );
  });

  /** Texto legible del tamaño del archivo seleccionado. */
  protected readonly selectedFileSizeLabel = computed(() => {
    const file = this.selectedFile();
    return file ? formatFileSize(file.size) : '';
  });

  ngOnInit(): void {
    if (this.canExport()) {
      this.loadExportCatalogMetadata();
    }
  }

  /** Descarga la plantilla oficial en formato XLSX o CSV para el tipo seleccionado. */
  protected downloadTemplate(format: ExportFormat): void {
    const importType = this.importForm.controls.importType.value;
    this.importError.set(null);
    this.downloadingTemplate.set(true);

    this.importsService
      .downloadTemplate(importType, format)
      .pipe(finalize(() => this.downloadingTemplate.set(false)))
      .subscribe({
        next: (file) => {
          triggerBrowserDownload(file);
          this.snackBar.open(`Plantilla ${format.toUpperCase()} descargada.`, 'Cerrar', {
            duration: 3000,
          });
        },
        error: (err) => {
          this.importError.set(this.extractErrorMessage(err, 'No se pudo descargar la plantilla.'));
        },
      });
  }

  /** Maneja la selección de un archivo desde `<input type="file">` y valida extensión y tamaño. */
  protected onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0] ?? null;
    this.importError.set(null);
    this.fileValidationError.set(null);

    if (!file) {
      this.selectedFile.set(null);
      return;
    }

    const lowerName = file.name.toLowerCase();
    const validExt = ACCEPTED_EXTENSIONS.some((ext) => lowerName.endsWith(ext));
    if (!validExt) {
      this.selectedFile.set(null);
      this.fileValidationError.set('Formato no soportado. Selecciona un archivo CSV o XLSX.');
      return;
    }

    if (file.size === 0) {
      this.selectedFile.set(null);
      this.fileValidationError.set('El archivo seleccionado está vacío.');
      return;
    }

    if (file.size > MAX_IMPORT_FILE_BYTES) {
      this.selectedFile.set(null);
      this.fileValidationError.set(
        `El archivo supera el límite máximo de 20 MB (${formatFileSize(file.size)}).`,
      );
      return;
    }

    this.selectedFile.set(file);
  }

  /** Ejecuta la carga en modo simulación (`dryRun=true`). */
  protected runDryRun(): void {
    this.executeUpload(true);
  }

  /** Solicita confirmación al usuario y ejecuta la importación directa (`dryRun=false`). */
  protected runDirectImport(): void {
    const file = this.selectedFile();
    if (!file || this.fileValidationError()) {
      return;
    }
    this.confirmDialog
      .confirm({
        title: 'Importación directa',
        message:
          `Se importará "${file.name}" directamente sin paso previo de revisión. ` +
          'Si todas las filas son válidas, los cambios se aplicarán de inmediato en una transacción.',
        confirmLabel: 'Importar ahora',
      })
      .pipe(filter(Boolean))
      .subscribe(() => this.executeUpload(false));
  }

  /** Confirma y aplica en una transacción el trabajo validado actualmente en pantalla. */
  protected confirmCurrentImport(): void {
    const job = this.currentJob();
    if (!job?.id || !this.canConfirmCurrentJob()) {
      return;
    }

    const okRows = job.okRows ?? 0;
    this.confirmDialog
      .confirm({
        title: 'Confirmar importación',
        message: `¿Aplicar la importación #${job.id} (${okRows} filas válidas) en el catálogo?`,
        confirmLabel: 'Confirmar importación',
      })
      .pipe(
        filter(Boolean),
        switchMap(() => {
          this.importError.set(null);
          this.confirmingImport.set(true);
          return this.importsService
            .confirmImport(job.id!, this.activeStoreId(), withoutErrorNotification())
            .pipe(finalize(() => this.confirmingImport.set(false)));
        }),
      )
      .subscribe({
        next: (updated) => {
          this.currentJob.set(updated);
          this.snackBar.open(
            `Importación #${updated.id} confirmada (${updated.okRows ?? 0} filas aplicadas).`,
            'Cerrar',
            { duration: 4000 },
          );
        },
        error: (err) => {
          this.importError.set(
            this.extractErrorMessage(err, 'No se pudo confirmar la importación.'),
          );
        },
      });
  }

  /** Consulta un trabajo de importación existente por su identificador. */
  protected lookupJobById(): void {
    if (this.lookupForm.invalid) {
      this.lookupForm.markAllAsTouched();
      return;
    }
    const id = Number(this.lookupForm.controls.jobId.value);
    this.importError.set(null);
    this.loadingJob.set(true);

    this.importsService
      .getImport(id, withoutErrorNotification())
      .pipe(finalize(() => this.loadingJob.set(false)))
      .subscribe({
        next: (job) => {
          this.currentJob.set(job);
        },
        error: (err) => {
          this.importError.set(
            this.extractErrorMessage(err, `No se encontró la importación #${id}.`),
          );
        },
      });
  }

  /** Ejecuta la exportación del catálogo con los filtros y formato seleccionados. */
  protected exportCatalog(): void {
    const raw = this.exportForm.getRawValue();
    this.exportError.set(null);
    this.exporting.set(true);

    this.importsService
      .exportCatalog(
        {
          format: raw.format,
          q: raw.q.trim() || undefined,
          categoryId: raw.categoryId ?? undefined,
          brandId: raw.brandId ?? undefined,
          saleType: raw.saleType ?? undefined,
          active: raw.active ?? undefined,
          restricted: raw.restricted ?? undefined,
          lotControlled: raw.lotControlled ?? undefined,
        },
        withoutErrorNotification(),
      )
      .pipe(finalize(() => this.exporting.set(false)))
      .subscribe({
        next: (file) => {
          triggerBrowserDownload(file);
          this.snackBar.open(`Catálogo exportado (${file.fileName}).`, 'Cerrar', {
            duration: 3500,
          });
        },
        error: (err) => {
          this.exportError.set(this.extractErrorMessage(err, 'No se pudo exportar el catálogo.'));
        },
      });
  }

  /** Restablece los filtros de exportación a sus valores por defecto. */
  protected resetExportFilters(): void {
    this.exportForm.reset({
      format: 'xlsx',
      q: '',
      categoryId: null,
      brandId: null,
      saleType: null,
      active: null,
      restricted: null,
      lotControlled: null,
    });
  }

  /** Devuelve una etiqueta legible para el estado del trabajo de importación. */
  protected formatStatus(status: string | undefined): string {
    if (!status) {
      return 'Desconocido';
    }
    return IMPORT_STATUS_LABELS[status] ?? status;
  }

  /** Devuelve una etiqueta legible para el tipo de importación. */
  protected formatImportType(importType: string | undefined): string {
    return (
      this.importTypeOptions.find((opt) => opt.value === importType)?.label ?? importType ?? ''
    );
  }

  private executeUpload(dryRun: boolean): void {
    const file = this.selectedFile();
    if (!file || this.fileValidationError()) {
      return;
    }
    const importType = this.importForm.controls.importType.value;
    this.importError.set(null);
    this.submittingImport.set(true);

    this.importsService
      .createImport(
        {
          importType,
          dryRun,
          file,
          storeId: this.activeStoreId(),
        },
        withoutErrorNotification(),
      )
      .pipe(finalize(() => this.submittingImport.set(false)))
      .subscribe({
        next: (job) => {
          this.currentJob.set(job);
          if (job.id !== undefined) {
            this.lookupForm.controls.jobId.setValue(String(job.id));
          }
          const errorsCount = job.errorRows ?? 0;
          if (dryRun) {
            this.snackBar.open(
              errorsCount > 0
                ? `Validación completada con ${errorsCount} fila(s) con error.`
                : `Validación exitosa: ${job.okRows ?? 0} fila(s) listas para confirmar.`,
              'Cerrar',
              { duration: 4000 },
            );
          } else {
            this.snackBar.open(
              errorsCount > 0
                ? `Importación finalizada con ${errorsCount} fila(s) con error.`
                : `Importación completada: ${job.okRows ?? 0} fila(s) aplicadas.`,
              'Cerrar',
              { duration: 4000 },
            );
          }
        },
        error: (err) => {
          this.importError.set(
            this.extractErrorMessage(err, 'No se pudo procesar el archivo de importación.'),
          );
        },
      });
  }

  private loadExportCatalogMetadata(): void {
    this.importsService.listCategories(withoutErrorNotification()).subscribe({
      next: (tree) => this.categories.set(flattenCategoryTree(tree)),
      error: () => this.categories.set([]),
    });

    this.importsService.listBrands(withoutErrorNotification()).subscribe({
      next: (page) => this.brands.set(page.content ?? []),
      error: () => this.brands.set([]),
    });
  }

  private extractErrorMessage(err: unknown, fallback: string): string {
    if (isApiError(err)) {
      return err.message || fallback;
    }
    if (err instanceof Error && err.message) {
      return err.message;
    }
    return fallback;
  }
}
