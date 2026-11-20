import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  OnInit,
  TemplateRef,
  computed,
  inject,
  viewChild,
} from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule } from '@angular/material/menu';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Observable, filter, switchMap } from 'rxjs';
import { PromotionResponse } from '../../api/models/promotion-response';
import { AuthService } from '../../core/auth/auth.service';
import { withoutErrorNotification } from '../../core/http/http-context-tokens';
import { PageResponse } from '../../shared/models/page-response';
import { ConfirmDialogService } from '../../shared/ui/confirm-dialog';
import {
  CellContext,
  Filters,
  PaginatedDataSource,
  PaginatedTable,
  TableColumn,
} from '../../shared/ui/paginated-table';
import {
  PROMOTION_TYPE_LABELS,
  PromotionType,
  TARGET_TYPE_LABELS,
  formatDateTime,
} from './promotion-validators';
import { PromotionFormDialog, PromotionFormDialogData } from './promotion-form-dialog';
import { PromotionsService } from './promotions.service';

/**
 * Pantalla de administración de promociones (`HU-POS-07`).
 *
 * Muestra la bandeja paginada de promociones (`/promociones`) con filtros por estado y tipo,
 * permite crear/editar promociones (formulario por tipo de promoción) y activar/desactivar con
 * confirmación. Los filtros de `vigentAt` y `productId` del endpoint quedan para consultas
 * puntuales (p. ej. depuración del motor de promociones) y no se exponen en la bandeja.
 */
@Component({
  selector: 'pos-promotions',
  imports: [
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatMenuModule,
    MatSelectModule,
    PaginatedTable,
    ReactiveFormsModule,
  ],
  templateUrl: './promotions.html',
  styleUrl: './promotions.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Promotions implements OnInit, OnDestroy {
  private readonly promotionsService = inject(PromotionsService);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(MatDialog);
  private readonly confirmDialog = inject(ConfirmDialogService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly fb = inject(NonNullableFormBuilder);

  private readonly statusTpl =
    viewChild.required<TemplateRef<CellContext<PromotionResponse>>>('statusTpl');
  private readonly actionsTpl =
    viewChild.required<TemplateRef<CellContext<PromotionResponse>>>('actionsTpl');

  protected readonly canManagePromotions = computed(() =>
    this.auth.hasPermission('promotions:promotion:manage'),
  );

  protected readonly promotionTypeOptions = Object.entries(PROMOTION_TYPE_LABELS).map(
    ([value, label]) => ({ value: value as PromotionType, label }),
  );

  protected readonly filterForm = this.fb.group({
    active: [null as boolean | null],
    type: [null as PromotionType | null],
  });

  protected readonly dataSource = new PaginatedDataSource<PromotionResponse>(
    (params) => {
      const activeParam = params.get('active');
      const active = activeParam === 'true' ? true : activeParam === 'false' ? false : undefined;
      const type = params.get('type') ?? undefined;
      return this.promotionsService.listPromotions(
        {
          active,
          type: type as PromotionType | undefined,
          page: Number(params.get('page') ?? 0),
          size: Number(params.get('size') ?? 20),
          sort: params.getAll('sort') ?? undefined,
        },
        withoutErrorNotification(),
      ) as unknown as Observable<PageResponse<PromotionResponse>>;
    },
    { sort: [{ field: 'priority', direction: 'asc' }] },
  );

  protected readonly columns = computed<readonly TableColumn<PromotionResponse>[]>(() => [
    {
      key: 'name',
      header: 'Nombre',
      sortField: 'name',
      cell: (row) => row.name ?? '—',
    },
    {
      key: 'promotionType',
      header: 'Tipo',
      sortField: 'promotionType',
      cell: (row) => PROMOTION_TYPE_LABELS[row.promotionType ?? 'PERCENT'],
    },
    {
      key: 'targets',
      header: 'Objetivos',
      cell: (row) => this.formatTargets(row),
    },
    {
      key: 'validity',
      header: 'Vigencia',
      cell: (row) => this.formatValidity(row),
    },
    {
      key: 'priority',
      header: 'Prioridad',
      align: 'end',
      sortField: 'priority',
      cell: (row) => String(row.priority ?? '—'),
    },
    {
      key: 'stackable',
      header: 'Acumulable',
      cell: (row) => (row.stackable ? 'Sí' : 'No'),
    },
    {
      key: 'active',
      header: 'Estado',
      template: this.statusTpl(),
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

  protected applyFilters(): void {
    const raw = this.filterForm.getRawValue();
    const filters: Filters = {
      active: raw.active,
      type: raw.type,
    };
    this.dataSource.setFilters(filters);
  }

  protected clearFilters(): void {
    this.filterForm.reset({ active: null, type: null });
    this.dataSource.setFilters({});
  }

  protected openCreate(): void {
    this.openPromotionForm(null);
  }

  protected openEdit(row: PromotionResponse): void {
    if (!row.id) {
      return;
    }
    this.openPromotionForm(row.id);
  }

  protected toggleActive(row: PromotionResponse): void {
    if (!row.id) {
      return;
    }
    const willActivate = !row.active;
    this.confirmDialog
      .confirm({
        title: willActivate ? 'Activar promoción' : 'Desactivar promoción',
        message: willActivate
          ? `¿Deseas activar la promoción "${row.name}"?`
          : `¿Deseas desactivar la promoción "${row.name}"? Dejará de aplicarse en el POS.`,
        confirmLabel: willActivate ? 'Activar' : 'Desactivar',
        destructive: !willActivate,
      })
      .pipe(
        filter(Boolean),
        switchMap(() =>
          willActivate
            ? this.promotionsService.activatePromotion(row.id!)
            : this.promotionsService.deactivatePromotion(row.id!),
        ),
      )
      .subscribe(() => {
        this.snackBar.open(
          willActivate ? 'Promoción activada.' : 'Promoción desactivada.',
          'Cerrar',
          { duration: 3000 },
        );
        this.dataSource.reload();
      });
  }

  private openPromotionForm(promotionId: number | null): void {
    this.dialog
      .open<PromotionFormDialog, PromotionFormDialogData, PromotionResponse | null>(
        PromotionFormDialog,
        {
          data: { promotionId },
          width: '900px',
          maxWidth: '96vw',
        },
      )
      .afterClosed()
      .pipe(filter((saved): saved is PromotionResponse => Boolean(saved)))
      .subscribe(() => {
        this.snackBar.open(
          promotionId === null ? 'Promoción creada.' : 'Promoción actualizada.',
          'Cerrar',
          { duration: 3000 },
        );
        this.dataSource.reload();
      });
  }

  /** Resumen legible de los objetivos: nombres si están disponibles, si no conteo por tipo. */
  private formatTargets(row: PromotionResponse): string {
    const items = row.items ?? [];
    if (items.length === 0) {
      return '—';
    }
    const labels = items.map((item) => {
      if (item.targetName) {
        return item.targetName;
      }
      const targetLabel =
        TARGET_TYPE_LABELS[item.targetType ?? 'PRODUCT'] ?? item.targetType ?? 'Objetivo';
      return `${targetLabel} #${item.targetId ?? '?'}`;
    });
    if (labels.length <= 2) {
      return labels.join(', ');
    }
    return `${labels[0]} y ${labels.length - 1} más`;
  }

  /** Rango de vigencia legible en zona local. */
  private formatValidity(row: PromotionResponse): string {
    if (!row.validFrom) {
      return '—';
    }
    return row.validTo
      ? `${formatDateTime(row.validFrom)} → ${formatDateTime(row.validTo)}`
      : `Desde ${formatDateTime(row.validFrom)}`;
  }
}
