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
import { ScaleBarcodeConfigResponse } from '../../../api/models/scale-barcode-config-response';
import { AuthService } from '../../../core/auth/auth.service';
import { withoutErrorNotification } from '../../../core/http/http-context-tokens';
import { ScaleFormDialog } from './scale-form-dialog';
import { ScaleService } from './scale.service';

/**
 * Pantalla de configuración de prefijos y estructura EAN-13 para códigos de balanza
 * (`HU-CAT-05`, `HU-CFG-07`).
 */
@Component({
  selector: 'pos-scale',
  imports: [MatButtonModule, MatIconModule, MatProgressBarModule, MatTableModule, MatTooltipModule],
  templateUrl: './scale.html',
  styleUrl: './scale.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Scale implements OnInit {
  private readonly scaleService = inject(ScaleService);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly canManage = computed(() =>
    this.auth.hasPermission('config:scale-barcode:manage'),
  );
  protected readonly configs = signal<readonly ScaleBarcodeConfigResponse[]>([]);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly displayedColumns = computed<readonly string[]>(() => {
    const cols = ['name', 'prefix', 'embeddedType', 'plu', 'value', 'active'];
    return this.canManage() ? [...cols, 'actions'] : cols;
  });

  ngOnInit(): void {
    this.loadConfigs();
  }

  protected loadConfigs(): void {
    this.loading.set(true);
    this.error.set(null);
    this.scaleService.listConfigs(withoutErrorNotification()).subscribe({
      next: (configs) => {
        this.configs.set(configs ?? []);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.error.set(
          err instanceof Error
            ? err.message
            : 'No se pudieron cargar las configuraciones de balanza.',
        );
        this.loading.set(false);
      },
    });
  }

  protected openCreate(): void {
    const ref = this.dialog.open(ScaleFormDialog, {
      data: {},
      width: '540px',
    });
    ref.afterClosed().subscribe((created) => {
      if (created) {
        this.snackBar.open('Configuración de balanza creada.', 'Cerrar', { duration: 4000 });
        this.loadConfigs();
      }
    });
  }

  protected openEdit(config: ScaleBarcodeConfigResponse): void {
    const ref = this.dialog.open(ScaleFormDialog, {
      data: { config },
      width: '540px',
    });
    ref.afterClosed().subscribe((updated) => {
      if (updated) {
        this.snackBar.open('Configuración de balanza actualizada.', 'Cerrar', { duration: 4000 });
        this.loadConfigs();
      }
    });
  }
}
