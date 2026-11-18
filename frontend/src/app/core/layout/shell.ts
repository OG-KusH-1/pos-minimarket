import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatToolbarModule } from '@angular/material/toolbar';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { LOGIN_URL } from '../auth/auth.guard';
import { AuthService } from '../auth/auth.service';
import { StoreContextService } from '../store-context/store-context.service';
import { NAV_GROUPS, filterNavGroups } from './nav-items';

/**
 * Layout de la aplicación autenticada: barra superior, menú lateral y área de contenido.
 *
 * Decisiones de diseño:
 * - El menú se dibuja recorriendo `NAV_GROUPS` (única fuente de verdad); este componente no
 *   conoce rutas ni permisos concretos.
 * - El nombre del usuario y del local salen de `AuthService` y `StoreContextService`; sin sesión
 *   o sin local se muestran los textos "sin sesión" / "sin seleccionar".
 * - El menú se filtra con `filterNavGroups` dentro de un `computed`: solo se muestran los ítems
 *   para los que el usuario tiene alguno de los permisos requeridos (y los grupos no vacíos).
 *   Es solo presentación; la autorización real es del backend y de los guards de ruta.
 * - El menú lateral es fijo (`mode="side"`) con botón para ocultarlo; no hay comportamiento
 *   responsive específico para móviles (el POS se opera en escritorio/tablet).
 */
@Component({
  selector: 'pos-shell',
  imports: [
    MatButtonModule,
    MatIconModule,
    MatListModule,
    MatSidenavModule,
    MatToolbarModule,
    RouterLink,
    RouterLinkActive,
    RouterOutlet,
  ],
  templateUrl: './shell.html',
  styleUrl: './shell.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Shell {
  /** Nombre de la aplicación mostrado en la barra superior. */
  protected readonly appName = 'POS Minimarket';
  private readonly auth = inject(AuthService);
  private readonly storeContext = inject(StoreContextService);
  private readonly router = inject(Router);

  /** Indica si hay una sesión activa para mostrar la acción de cierre de sesión. */
  protected readonly isAuthenticated = this.auth.isAuthenticated;
  /** Evita doble envío mientras se cierra la sesión. */
  protected readonly loggingOut = signal(false);

  /** Local activo para la barra superior. */
  protected readonly storeName = computed(
    () => `Local: ${this.storeContext.activeStore()?.name ?? 'sin seleccionar'}`,
  );
  /** Usuario autenticado para la barra superior. */
  protected readonly userName = computed(
    () => `Usuario: ${this.auth.user()?.fullName ?? 'sin sesión'}`,
  );
  /** Estado de apertura del menú lateral. */
  protected readonly menuOpen = signal(true);
  /** Menú visible para el usuario actual (se recalcula al cambiar sus permisos). */
  protected readonly groups = computed(() =>
    filterNavGroups(NAV_GROUPS, (permissions) => this.auth.hasAnyPermission(permissions)),
  );

  /** Abre o cierra el menú lateral. */
  protected toggleMenu(): void {
    this.menuOpen.update((open) => !open);
  }

  /** Cierra la sesión actual (`POST /auth/logout`) y redirige a `/login` (L-06). */
  protected async logout(): Promise<void> {
    if (this.loggingOut()) {
      return;
    }
    this.loggingOut.set(true);
    try {
      await firstValueFrom(this.auth.logout());
      await this.router.navigateByUrl(LOGIN_URL);
    } finally {
      this.loggingOut.set(false);
    }
  }
}
