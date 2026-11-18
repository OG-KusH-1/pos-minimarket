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
import { BrandResponse } from '../../../api/models/brand-response';
import { AuthService } from '../../../core/auth/auth.service';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { PageResponse } from '../../../shared/models/page-response';
import { ConfirmDialogService } from '../../../shared/ui/confirm-dialog';
import {
  CellContext,
  Filters,
  PaginatedDataSource,
  PaginatedTable,
  TableColumn,
} from '../../../shared/ui/paginated-table';
import { BrandFormDialog } from './brand-form-dialog';
import { BrandsService } from './brands.service';

/**
 * Pantalla de administración de marcas del catálogo (`HU-CAT-12`).
 */
@Component({
  selector: 'pos-brands',
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
  templateUrl: './brands.html',
  styleUrl: './brands.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Brands implements OnInit, OnDestroy {
  private readonly brandsService = inject(BrandsService);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(MatDialog);
  private readonly confirmDialog = inject(ConfirmDialogService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly fb = inject(NonNullableFormBuilder);

  private readonly statusTpl =
    viewChild.required<TemplateRef<CellContext<BrandResponse>>>('statusTpl');
  private readonly actionsTpl =
    viewChild.required<TemplateRef<CellContext<BrandResponse>>>('actionsTpl');

  protected readonly canManage = computed(() => this.auth.hasPermission('catalog:brand:manage'));

  protected readonly filterForm = this.fb.group({
    q: [''],
    active: [null as boolean | null],
  });

  protected readonly dataSource = new PaginatedDataSource<BrandResponse>(
    (params) =>
      this.brandsService.listBrands(
        {
          q: params.get('q') ?? undefined,
          active: this.parseBooleanParam(params.get('active')),
          page: this.parseNumberParam(params.get('page')),
          size: this.parseNumberParam(params.get('size')),
          sort: params.getAll('sort') ?? undefined,
        },
        withoutErrorNotification(),
      ) as unknown as Observable<PageResponse<BrandResponse>>,
    { sort: [{ field: 'name', direction: 'asc' }] },
  );

  protected readonly columns = computed<readonly TableColumn<BrandResponse>[]>(() => {
    const base: TableColumn<BrandResponse>[] = [
      { key: 'name', header: 'Nombre', sortField: 'name' },
      { key: 'active', header: 'Estado', sortField: 'active', template: this.statusTpl() },
    ];
    if (this.canManage()) {
      base.push({ key: 'actions', header: 'Acciones', template: this.actionsTpl(), align: 'end' });
    }
    return base;
  });

  ngOnInit(): void {
    this.dataSource.reload();
  }

  ngOnDestroy(): void {
    this.dataSource.destroy();
  }

  protected applyFilters(): void {
    const raw = this.filterForm.getRawValue();
    const filters: Filters = {
      q: raw.q.trim() || null,
      active: raw.active,
    };
    this.dataSource.setFilters(filters);
  }

  protected clearFilters(): void {
    this.filterForm.reset({ q: '', active: null });
    this.dataSource.setFilters({});
  }

  protected openCreate(): void {
    const ref = this.dialog.open(BrandFormDialog, {
      data: {},
      width: '420px',
    });
    ref.afterClosed().subscribe((created) => {
      if (created) {
        this.snackBar.open('Marca creada correctamente.', 'Cerrar', { duration: 4000 });
        this.dataSource.reload();
      }
    });
  }

  protected openEdit(brand: BrandResponse): void {
    const ref = this.dialog.open(BrandFormDialog, {
      data: { brand },
      width: '420px',
    });
    ref.afterClosed().subscribe((updated) => {
      if (updated) {
        this.snackBar.open('Marca actualizada correctamente.', 'Cerrar', { duration: 4000 });
        this.dataSource.reload();
      }
    });
  }

  protected toggleActive(brand: BrandResponse): void {
    if (brand.id === undefined) {
      return;
    }
    const brandId = brand.id;
    const isCurrentlyActive = brand.active !== false;
    this.confirmDialog
      .confirm({
        title: isCurrentlyActive ? 'Desactivar marca' : 'Activar marca',
        message: isCurrentlyActive
          ? `¿Desactivar la marca "${brand.name}"?`
          : `¿Reactivar la marca "${brand.name}"?`,
        confirmLabel: isCurrentlyActive ? 'Desactivar' : 'Activar',
        destructive: isCurrentlyActive,
      })
      .pipe(
        filter(Boolean),
        switchMap(() =>
          isCurrentlyActive
            ? this.brandsService.deactivateBrand(brandId)
            : this.brandsService.activateBrand(brandId),
        ),
      )
      .subscribe(() => {
        this.snackBar.open(
          isCurrentlyActive ? 'Marca desactivada.' : 'Marca reactivada.',
          'Cerrar',
          { duration: 4000 },
        );
        this.dataSource.reload();
      });
  }

  private parseNumberParam(raw: string | null): number | undefined {
    if (raw === null || raw === '') {
      return undefined;
    }
    const parsed = Number(raw);
    return Number.isFinite(parsed) ? parsed : undefined;
  }

  private parseBooleanParam(raw: string | null): boolean | undefined {
    if (raw === 'true') {
      return true;
    }
    if (raw === 'false') {
      return false;
    }
    return undefined;
  }
}
