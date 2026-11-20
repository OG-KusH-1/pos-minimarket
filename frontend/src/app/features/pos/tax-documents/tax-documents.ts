import {
  ChangeDetectionStrategy,
  Component,
  TemplateRef,
  OnDestroy,
  OnInit,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import {
  FormControl,
  NonNullableFormBuilder,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { firstValueFrom } from 'rxjs';
import { TaxDocumentResponse } from '../../../api/models/tax-document-response';
import { AuthService } from '../../../core/auth/auth.service';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { StoreContextService } from '../../../core/store-context/store-context.service';
import { ClpPipe } from '../../../shared/pipes';
import {
  CellContext,
  Filters,
  PaginatedDataSource,
  PaginatedTable,
  TableColumn,
} from '../../../shared/ui/paginated-table';
import { ConfirmDialogService } from '../../../shared/ui/confirm-dialog';
import { extractPosErrorMessage } from '../sale/cart/pos-http.utils';
import {
  PosEmailTicketDialog,
  PosEmailTicketDialogData,
} from '../sale/customer/pos-email-ticket-dialog';
import {
  TAX_DOCUMENT_STATUS_OPTIONS,
  TAX_DOCUMENT_TYPE_OPTIONS,
  TaxDocumentStatusCode,
  TaxDocumentTypeCode,
  canRetryTaxDocument,
  formatTaxDocumentDateTime,
  taxDocumentSourceLabel,
  taxDocumentStatusLabel,
  taxDocumentTypeLabel,
} from './tax-documents.models';
import { TaxDocumentDetailDialog } from './tax-document-detail-dialog';
import { TaxDocumentsService } from './tax-documents.service';

/** Resultado de la consulta del documento de una venta (204 incluido como `document: null`). */
interface SaleTaxDocumentLookup {
  readonly saleId: number;
  readonly document: TaxDocumentResponse | null;
}

/** Duración de los avisos de reintento en pantalla, en milisegundos. */
const SNACKBAR_DURATION_MS = 5000;

/**
 * Pantalla de documentos tributarios simulados (`HU-POS-12`).
 *
 * Ofrece:
 * - La lista filtrable y paginada de documentos del local activo (`GET /tax-documents`) con
 *   detalle por documento y reintento de emisión para documentos ERROR o REJECTED
 *   (`POST /tax-documents/{id}/retry`, con confirmación).
 * - La consulta del documento de una venta concreta (`GET /sales/{id}/tax-document`): el
 *   backend responde **204 mientras la boleta sigue pendiente de emisión asíncrona**, caso que
 *   se muestra como estado pendiente (aviso informativo), nunca como error.
 * - El envío del comprobante de una venta por correo (`HU-POS-11`, diálogo reutilizable de
 *   `features/pos/sale/customer`).
 */
@Component({
  selector: 'pos-tax-documents',
  imports: [
    ClpPipe,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressSpinnerModule,
    MatSelectModule,
    PaginatedTable,
    ReactiveFormsModule,
  ],
  templateUrl: './tax-documents.html',
  styleUrl: './tax-documents.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TaxDocuments implements OnInit, OnDestroy {
  private readonly taxDocumentsService = inject(TaxDocumentsService);
  private readonly storeContext = inject(StoreContextService);
  private readonly auth = inject(AuthService);
  private readonly confirmDialog = inject(ConfirmDialogService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly fb = inject(NonNullableFormBuilder);

  private readonly statusTpl =
    viewChild.required<TemplateRef<CellContext<TaxDocumentResponse>>>('statusTpl');
  private readonly actionsTpl =
    viewChild.required<TemplateRef<CellContext<TaxDocumentResponse>>>('actionsTpl');

  /** Instancia de la pipe de CLP para formatear montos en celdas de texto. */
  private readonly clpPipe = new ClpPipe();

  protected readonly statusOptions = TAX_DOCUMENT_STATUS_OPTIONS;
  protected readonly typeOptions = TAX_DOCUMENT_TYPE_OPTIONS;

  /** Nombre del local activo para el subtítulo (coherente con el resto de las pantallas). */
  protected readonly activeStoreName = computed(
    () => this.storeContext.activeStore()?.name ?? 'Local activo',
  );

  /** Permiso para reintentar emisiones (`sales:tax-document:retry`). */
  protected readonly canRetry = computed(() => this.auth.hasPermission('sales:tax-document:retry'));

  /** Permiso para enviar comprobantes por correo (`sales:pos:operate`). */
  protected readonly canEmail = computed(() => this.auth.hasPermission('sales:pos:operate'));

  /** Formulario tipado de filtros de la lista. */
  protected readonly filterForm = this.fb.group({
    status: [null as TaxDocumentStatusCode | null],
    documentType: [null as TaxDocumentTypeCode | null],
    folio: [null as number | null],
    from: [''],
    to: [''],
  });

  /** Identificador de venta escrito por el usuario para la consulta individual. */
  protected readonly lookupForm = this.fb.group({
    saleId: new FormControl<number | null>(null, {
      validators: [Validators.required, Validators.min(1)],
    }),
  });

  /** Resultado vigente de la consulta por venta (incluye el caso 204 como `document: null`). */
  protected readonly lookup = signal<SaleTaxDocumentLookup | null>(null);
  protected readonly lookupLoading = signal(false);
  protected readonly lookupError = signal<string | null>(null);

  /** `id` del documento cuyo reintento está en vuelo (deshabilita solo ese botón). */
  protected readonly retryingId = signal<number | null>(null);

  /** Origen de datos de la tabla (el padre crea/destruye y dispara la carga inicial). */
  protected readonly dataSource = new PaginatedDataSource<TaxDocumentResponse>(
    (params) =>
      this.taxDocumentsService.listDocuments(
        {
          status: (params.get('status') as TaxDocumentStatusCode | null) ?? undefined,
          documentType: (params.get('documentType') as TaxDocumentTypeCode | null) ?? undefined,
          folio: this.parseNumber(params.get('folio')),
          from: params.get('from') || undefined,
          to: params.get('to') || undefined,
          page: this.parseNumber(params.get('page')),
          size: this.parseNumber(params.get('size')),
          sort: params.getAll('sort') ?? undefined,
        },
        withoutErrorNotification(),
      ),
    { sort: [{ field: 'id', direction: 'desc' }] },
  );

  /** Columnas de la tabla de documentos. */
  protected readonly columns = computed<readonly TableColumn<TaxDocumentResponse>[]>(() => [
    {
      key: 'folio',
      header: 'Folio',
      sortField: 'folio',
      cell: (row) => (row.folio !== undefined ? String(row.folio) : '—'),
    },
    {
      key: 'documentType',
      header: 'Tipo',
      sortField: 'documentType',
      cell: (row) => taxDocumentTypeLabel(row.documentType),
    },
    {
      key: 'status',
      header: 'Estado',
      sortField: 'status',
      template: this.statusTpl(),
    },
    {
      key: 'source',
      header: 'Origen',
      cell: (row) => taxDocumentSourceLabel(row),
    },
    {
      key: 'totalAmount',
      header: 'Total',
      sortField: 'totalAmount',
      align: 'end',
      cell: (row) => this.clpPipe.transform(row.totalAmount) || '—',
    },
    {
      key: 'issuedAt',
      header: 'Emitido',
      sortField: 'issuedAt',
      cell: (row) => formatTaxDocumentDateTime(row.issuedAt),
    },
    {
      key: 'actions',
      header: 'Acciones',
      template: this.actionsTpl(),
    },
  ]);

  ngOnInit(): void {
    this.dataSource.reload();
  }

  ngOnDestroy(): void {
    this.dataSource.destroy();
  }

  /** Aplica los filtros de la lista sobre el origen de datos (vuelve a la página 0). */
  protected applyFilters(): void {
    const raw = this.filterForm.getRawValue();
    const filters: Filters = {
      status: raw.status,
      documentType: raw.documentType,
      folio: raw.folio,
      from: raw.from || null,
      to: raw.to || null,
    };
    this.dataSource.setFilters(filters);
  }

  /** Limpia los filtros y recarga la lista completa. */
  protected clearFilters(): void {
    this.filterForm.reset({ status: null, documentType: null, folio: null, from: '', to: '' });
    this.dataSource.setFilters({});
  }

  /**
   * Consulta el documento tributario de la venta indicada.
   *
   * El 204 del backend (emisión asíncrona aún en curso) se representa como
   * `lookup().document === null` con aviso informativo, sin mensaje de error.
   */
  protected async lookupSaleDocument(): Promise<void> {
    const control = this.lookupForm.controls.saleId;
    if (this.lookupLoading()) {
      return;
    }
    const saleId = control.value;
    if (saleId === null || !Number.isFinite(saleId) || saleId <= 0) {
      control.markAsTouched();
      return;
    }

    this.lookupLoading.set(true);
    this.lookupError.set(null);
    try {
      const document = await firstValueFrom(
        this.taxDocumentsService.getSaleTaxDocument(saleId, withoutErrorNotification()),
      );
      this.lookup.set({ saleId, document });
    } catch (error: unknown) {
      this.lookup.set(null);
      this.lookupError.set(
        extractPosErrorMessage(error, 'No se pudo consultar el documento de la venta.'),
      );
    } finally {
      this.lookupLoading.set(false);
    }
  }

  /** Abre el detalle completo de un documento en un diálogo. */
  protected openDetail(document: TaxDocumentResponse): void {
    this.dialog.open<TaxDocumentDetailDialog, TaxDocumentResponse, void>(TaxDocumentDetailDialog, {
      data: document,
      width: '520px',
      maxWidth: '96vw',
    });
  }

  /** Abre el diálogo de envío del comprobante de la venta por correo (`HU-POS-11`). */
  protected openEmailDialog(saleId: number): void {
    const data: PosEmailTicketDialogData = { saleId };
    this.dialog.open<PosEmailTicketDialog, PosEmailTicketDialogData, void>(PosEmailTicketDialog, {
      data,
      width: '460px',
      maxWidth: '96vw',
    });
  }

  /**
   * Reintenta la emisión de un documento ERROR o REJECTED con confirmación previa
   * (`POST /tax-documents/{id}/retry`).
   */
  protected async retryDocument(document: TaxDocumentResponse): Promise<void> {
    const id = document.id;
    if (id === undefined || !canRetryTaxDocument(document) || this.retryingId() !== null) {
      return;
    }

    const confirmed = await firstValueFrom(
      this.confirmDialog.confirm({
        title: 'Reintentar emisión',
        message:
          `Se reintentará la emisión de la ${taxDocumentTypeLabel(document.documentType)}` +
          (document.folio !== undefined ? ` folio ${document.folio}` : '') +
          '. El proveedor simulado conservará el folio original. ¿Deseas continuar?',
        confirmLabel: 'Reintentar',
        cancelLabel: 'Cancelar',
      }),
    );
    if (!confirmed) {
      return;
    }

    this.retryingId.set(id);
    try {
      const updated = await firstValueFrom(
        this.taxDocumentsService.retryDocument(id, withoutErrorNotification()),
      );
      this.snackBar.open(
        `Reintento ejecutado. Estado actual: ${taxDocumentStatusLabel(updated.status)}.`,
        'Cerrar',
        { duration: SNACKBAR_DURATION_MS },
      );
      this.dataSource.reload();
      const current = this.lookup();
      if (current?.document?.id === id) {
        this.lookup.set({ ...current, document: updated });
      }
    } catch (error: unknown) {
      this.snackBar.open(
        extractPosErrorMessage(error, 'No se pudo reintentar la emisión del documento.'),
        'Cerrar',
        { duration: SNACKBAR_DURATION_MS },
      );
    } finally {
      this.retryingId.set(null);
    }
  }

  /** Indica si el botón de reintento de una fila está deshabilitado (reintento en vuelo). */
  protected isRetryBusy(id: number | undefined): boolean {
    return this.retryingId() !== null && this.retryingId() === id;
  }

  /** Wrapper para plantillas: etiqueta en español del estado de emisión. */
  protected statusLabel(status: TaxDocumentStatusCode | undefined): string {
    return taxDocumentStatusLabel(status);
  }

  /** Wrapper para plantillas: ¿el documento admite reintento (ERROR o REJECTED)? */
  protected retryable(document: TaxDocumentResponse): boolean {
    return canRetryTaxDocument(document);
  }

  /** Wrapper para plantillas: etiqueta del tipo de documento (prioriza `typeLabel` del backend). */
  protected documentTypeLabel(document: TaxDocumentResponse): string {
    return document.typeLabel ?? taxDocumentTypeLabel(document.documentType);
  }

  /** Wrapper para plantillas: formatea la fecha de emisión ISO-8601 en español. */
  protected formatDateTime(iso: string | undefined): string {
    return formatTaxDocumentDateTime(iso);
  }

  /** Traduce un parámetro de texto a número (`undefined` si falta o no es válido). */
  private parseNumber(raw: string | null): number | undefined {
    if (raw === null || raw === '') {
      return undefined;
    }
    const parsed = Number(raw);
    return Number.isFinite(parsed) && parsed >= 0 ? parsed : undefined;
  }
}
