import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { TaxResponse } from '../../../api/models/tax-response';
import { AuthService } from '../../../core/auth/auth.service';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { TaxFormDialog } from './tax-form-dialog';
import { TaxesService } from './taxes.service';

/**
 * Pantalla de administración de impuestos configurables (`HU-CAT-07`, `HU-CFG-03`).
 *
 * Muestra IVA y tributos adicionales (ILA, IABA) con su tasa, código SII y período de vigencia.
 */
@Component({
  selector: 'pos-taxes',
  imports: [MatButtonModule, MatIconModule, MatProgressBarModule, MatTableModule, MatTooltipModule],
  templateUrl: './taxes.html',
  styleUrl: './taxes.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Taxes implements OnInit {
  private readonly taxesService = inject(TaxesService);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly canManage = computed(() => this.auth.hasPermission('config:tax:manage'));
  protected readonly taxes = signal<readonly TaxResponse[]>([]);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly displayedColumns = computed<readonly string[]>(() => {
    const cols = ['code', 'name', 'taxType', 'rate', 'siiCode', 'validity', 'active'];
    return this.canManage() ? [...cols, 'actions'] : cols;
  });

  ngOnInit(): void {
    this.loadTaxes();
  }

  protected loadTaxes(): void {
    this.loading.set(true);
    this.error.set(null);
    this.taxesService.listTaxes(withoutErrorNotification()).subscribe({
      next: (taxes) => {
        this.taxes.set(taxes ?? []);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.error.set(err instanceof Error ? err.message : 'No se pudieron cargar los impuestos.');
        this.loading.set(false);
      },
    });
  }

  protected openCreate(): void {
    const ref = this.dialog.open(TaxFormDialog, {
      data: {},
      width: '520px',
    });
    ref.afterClosed().subscribe((created) => {
      if (created) {
        this.snackBar.open('Impuesto creado correctamente.', 'Cerrar', { duration: 4000 });
        this.loadTaxes();
      }
    });
  }

  protected openEdit(tax: TaxResponse): void {
    const ref = this.dialog.open(TaxFormDialog, {
      data: { tax },
      width: '520px',
    });
    ref.afterClosed().subscribe((updated) => {
      if (updated) {
        this.snackBar.open('Impuesto actualizado correctamente.', 'Cerrar', { duration: 4000 });
        this.loadTaxes();
      }
    });
  }

  protected formatRate(rate: number | undefined): string {
    return rate === undefined ? '—' : `${rate} %`;
  }
}
