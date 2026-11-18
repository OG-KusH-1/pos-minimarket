import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTableModule } from '@angular/material/table';
import { filter, switchMap } from 'rxjs';
import { SessionResponse } from '../../../api/models/session-response';
import { ConfirmDialogService } from '../../../shared/ui/confirm-dialog';
import { UsersService } from './users.service';

/** Datos de entrada para {@link UserSessionsDialog}. */
export interface UserSessionsDialogData {
  readonly userId: number;
  readonly username: string;
}

/**
 * Diálogo para consultar y revocar las sesiones activas de un usuario (`HU-SEG-11`).
 */
@Component({
  selector: 'pos-user-sessions-dialog',
  imports: [
    DatePipe,
    MatButtonModule,
    MatDialogModule,
    MatIconModule,
    MatProgressBarModule,
    MatTableModule,
  ],
  template: `
    <h2 mat-dialog-title>Sesiones activas — {{ data.username }}</h2>
    <mat-dialog-content class="content">
      @if (loading()) {
        <mat-progress-bar mode="indeterminate" />
      } @else if (sessions().length === 0) {
        <p class="empty" role="status">El usuario no tiene sesiones activas.</p>
      } @else {
        <table
          mat-table
          [dataSource]="sessions()"
          class="sessions-table"
          aria-label="Sesiones activas"
        >
          <ng-container matColumnDef="issuedAt">
            <th mat-header-cell *matHeaderCellDef>Inicio</th>
            <td mat-cell *matCellDef="let s">{{ s.issuedAt | date: 'dd/MM/yyyy HH:mm' }}</td>
          </ng-container>
          <ng-container matColumnDef="expiresAt">
            <th mat-header-cell *matHeaderCellDef>Expira</th>
            <td mat-cell *matCellDef="let s">{{ s.expiresAt | date: 'dd/MM/yyyy HH:mm' }}</td>
          </ng-container>
          <ng-container matColumnDef="ip">
            <th mat-header-cell *matHeaderCellDef>IP</th>
            <td mat-cell *matCellDef="let s">{{ s.ip || '—' }}</td>
          </ng-container>
          <ng-container matColumnDef="terminalId">
            <th mat-header-cell *matHeaderCellDef>Terminal</th>
            <td mat-cell *matCellDef="let s">{{ s.terminalId ?? '—' }}</td>
          </ng-container>
          <ng-container matColumnDef="userAgent">
            <th mat-header-cell *matHeaderCellDef>Dispositivo</th>
            <td mat-cell *matCellDef="let s" class="ua-cell">{{ s.userAgent || '—' }}</td>
          </ng-container>

          <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
          <tr mat-row *matRowDef="let row; columns: displayedColumns"></tr>
        </table>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" [mat-dialog-close]="false">Cerrar</button>
      <button
        mat-flat-button
        type="button"
        class="destructive"
        [disabled]="loading() || revoking() || sessions().length === 0"
        (click)="revokeAll()"
      >
        <mat-icon>logout</mat-icon>
        Revocar todas
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    .content {
      min-width: 520px;
      max-height: 60vh;
    }
    .sessions-table {
      width: 100%;
    }
    .ua-cell {
      max-width: 220px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .empty {
      margin: 16px 0;
      color: var(--mat-sys-on-surface-variant);
    }
    .destructive {
      --mat-button-filled-container-color: var(--mat-sys-error);
      --mat-button-filled-label-text-color: var(--mat-sys-on-error);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class UserSessionsDialog implements OnInit {
  protected readonly data = inject<UserSessionsDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(MatDialogRef<UserSessionsDialog, boolean>);
  private readonly usersService = inject(UsersService);
  private readonly confirmDialog = inject(ConfirmDialogService);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly displayedColumns = ['issuedAt', 'expiresAt', 'ip', 'terminalId', 'userAgent'];
  protected readonly sessions = signal<readonly SessionResponse[]>([]);
  protected readonly loading = signal(true);
  protected readonly revoking = signal(false);

  ngOnInit(): void {
    this.loadSessions();
  }

  protected revokeAll(): void {
    this.confirmDialog
      .confirm({
        title: 'Revocar sesiones',
        message: `¿Revocar todas las sesiones activas de "${this.data.username}"? El usuario deberá iniciar sesión nuevamente.`,
        confirmLabel: 'Sí, revocar',
        destructive: true,
      })
      .pipe(
        filter(Boolean),
        switchMap(() => {
          this.revoking.set(true);
          return this.usersService.revokeSessions(this.data.userId);
        }),
      )
      .subscribe({
        next: () => {
          this.revoking.set(false);
          this.snackBar.open('Sesiones revocadas correctamente.', 'Cerrar', { duration: 4000 });
          this.dialogRef.close(true);
        },
        error: () => {
          this.revoking.set(false);
        },
      });
  }

  private loadSessions(): void {
    this.loading.set(true);
    this.usersService.listSessions(this.data.userId).subscribe({
      next: (list) => {
        this.sessions.set(list);
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
      },
    });
  }
}
