import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { filter, switchMap } from 'rxjs';
import { CategoryResponse } from '../../../api/models/category-response';
import { AuthService } from '../../../core/auth/auth.service';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { ConfirmDialogService } from '../../../shared/ui/confirm-dialog';
import { CategoriesService } from './categories.service';
import { CategoryFormDialog } from './category-form-dialog';

/**
 * Pantalla de administración de categorías y subcategorías de producto (`HU-CAT-12`).
 *
 * Muestra la jerarquía de dos niveles retornada por `GET /api/v1/categories?tree=true`, con
 * búsqueda por nombre/código, filtro por estado y acciones con `catalog:category:manage`.
 */
@Component({
  selector: 'pos-categories',
  imports: [
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    MatSelectModule,
    MatTooltipModule,
    ReactiveFormsModule,
  ],
  templateUrl: './categories.html',
  styleUrl: './categories.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Categories implements OnInit {
  private readonly categoriesService = inject(CategoriesService);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(MatDialog);
  private readonly confirmDialog = inject(ConfirmDialogService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly fb = inject(NonNullableFormBuilder);

  protected readonly canManage = computed(() => this.auth.hasPermission('catalog:category:manage'));

  protected readonly categories = signal<readonly CategoryResponse[]>([]);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly collapsedIds = signal<ReadonlySet<number>>(new Set());

  protected readonly searchTerm = signal('');
  protected readonly activeFilter = signal<boolean | null>(null);

  protected readonly filterForm = this.fb.group({
    q: [''],
    active: [null as boolean | null],
  });

  /** Categorías raíz filtradas conservando sus subcategorías coincidentes. */
  protected readonly filteredTree = computed<readonly CategoryResponse[]>(() => {
    const query = this.searchTerm().trim().toLowerCase();
    const active = this.activeFilter();

    const matchesStatus = (cat: CategoryResponse): boolean => {
      if (active === null) {
        return true;
      }
      return (cat.active !== false) === active;
    };

    const matchesQuery = (cat: CategoryResponse): boolean => {
      if (!query) {
        return true;
      }
      const nameMatch = (cat.name ?? '').toLowerCase().includes(query);
      const codeMatch = (cat.code ?? '').toLowerCase().includes(query);
      return nameMatch || codeMatch;
    };

    const result: CategoryResponse[] = [];
    for (const root of this.categories()) {
      const rootMatchesQuery = matchesQuery(root);
      const rootMatchesStatus = matchesStatus(root);
      const allChildren = root.children ?? [];
      const matchingChildren = allChildren.filter(
        (child) => matchesStatus(child) && (rootMatchesQuery || matchesQuery(child)),
      );

      if ((rootMatchesQuery && rootMatchesStatus) || matchingChildren.length > 0) {
        result.push({
          ...root,
          children: matchingChildren,
        });
      }
    }
    return result;
  });

  ngOnInit(): void {
    this.loadCategories();
  }

  protected loadCategories(): void {
    this.loading.set(true);
    this.error.set(null);
    this.categoriesService.listCategories(true, withoutErrorNotification()).subscribe({
      next: (tree) => {
        this.categories.set(tree ?? []);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.error.set(
          err instanceof Error ? err.message : 'No se pudieron cargar las categorías.',
        );
        this.loading.set(false);
      },
    });
  }

  protected applyFilters(): void {
    const raw = this.filterForm.getRawValue();
    this.searchTerm.set(raw.q);
    this.activeFilter.set(raw.active);
  }

  protected clearFilters(): void {
    this.filterForm.reset({ q: '', active: null });
    this.searchTerm.set('');
    this.activeFilter.set(null);
  }

  protected isCollapsed(id: number | undefined): boolean {
    return id !== undefined && this.collapsedIds().has(id);
  }

  protected toggleCollapse(id: number | undefined): void {
    if (id === undefined) {
      return;
    }
    this.collapsedIds.update((prev) => {
      const next = new Set(prev);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  }

  protected openCreate(defaultParentId: number | null = null): void {
    const ref = this.dialog.open(CategoryFormDialog, {
      data: {
        rootCategories: this.categories(),
        defaultParentId,
      },
      width: '460px',
    });
    ref.afterClosed().subscribe((created) => {
      if (created) {
        this.snackBar.open('Categoría creada correctamente.', 'Cerrar', { duration: 4000 });
        this.loadCategories();
      }
    });
  }

  protected openEdit(category: CategoryResponse): void {
    const ref = this.dialog.open(CategoryFormDialog, {
      data: {
        category,
        rootCategories: this.categories(),
      },
      width: '460px',
    });
    ref.afterClosed().subscribe((updated) => {
      if (updated) {
        this.snackBar.open('Categoría actualizada correctamente.', 'Cerrar', { duration: 4000 });
        this.loadCategories();
      }
    });
  }

  protected toggleActive(category: CategoryResponse): void {
    if (category.id === undefined) {
      return;
    }
    const categoryId = category.id;
    const isCurrentlyActive = category.active !== false;
    this.confirmDialog
      .confirm({
        title: isCurrentlyActive ? 'Desactivar categoría' : 'Activar categoría',
        message: isCurrentlyActive
          ? `¿Desactivar "${category.name}"? No podrá usarse mientras esté inactiva.`
          : `¿Reactivar "${category.name}"?`,
        confirmLabel: isCurrentlyActive ? 'Desactivar' : 'Activar',
        destructive: isCurrentlyActive,
      })
      .pipe(
        filter(Boolean),
        switchMap(() =>
          isCurrentlyActive
            ? this.categoriesService.deactivateCategory(categoryId)
            : this.categoriesService.activateCategory(categoryId),
        ),
      )
      .subscribe(() => {
        this.snackBar.open(
          isCurrentlyActive ? 'Categoría desactivada.' : 'Categoría reactivada.',
          'Cerrar',
          { duration: 4000 },
        );
        this.loadCategories();
      });
  }
}
