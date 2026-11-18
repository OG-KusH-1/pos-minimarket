import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideApiConfiguration } from '../../../api/api-configuration';
import { AuthService } from '../../../core/auth/auth.service';
import { formatRoleLimitValue } from './role-limits.metadata';
import { RolePermissionsLimitsDialog } from './role-permissions-limits-dialog';

function flushJson(req: TestRequest, data: unknown): void {
  if (req.request.responseType === 'blob') {
    req.flush(new Blob([JSON.stringify(data)], { type: 'application/json' }));
  } else {
    req.flush(data as Record<string, unknown>);
  }
}

describe('RolePermissionsLimitsDialog', () => {
  let fixture: ComponentFixture<RolePermissionsLimitsDialog>;
  let http: HttpTestingController;
  let snackBarOpen: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    snackBarOpen = vi.fn();
    await TestBed.configureTestingModule({
      imports: [RolePermissionsLimitsDialog],
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: snackBarOpen } },
        {
          provide: AuthService,
          useValue: {
            hasPermission: (code: string) =>
              code === 'security:role:manage' || code === 'config:role-limit:manage',
          },
        },
        {
          provide: MAT_DIALOG_DATA,
          useValue: {
            role: {
              id: 3,
              code: 'SUPERVISOR',
              name: 'Supervisor',
              permissionCodes: ['sales:pos:operate'],
            },
          },
        },
      ],
    }).compileComponents();

    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(RolePermissionsLimitsDialog);
    fixture.detectChanges();

    flushJson(http.expectOne('/api/v1/permissions'), [
      { code: 'sales:pos:operate', module: 'sales', description: 'Operar POS', sensitive: false },
      { code: 'sales:sale:void', module: 'sales', description: 'Anular venta', sensitive: true },
    ]);
    flushJson(http.expectOne('/api/v1/roles/3/limits'), [
      { code: 'DISCOUNT_ITEM_MAX_PCT', value: null },
      { code: 'DISCOUNT_TOTAL_MAX_PCT', value: 15 },
      { code: 'CASH_OUT_MAX_AMOUNT', value: 50000 },
    ]);
    await fixture.whenStable();
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  it('muestra null en los límites como ∞ y formatea los valores numéricos', async () => {
    expect(formatRoleLimitValue(null, '%')).toBe('∞');
    expect(formatRoleLimitValue(undefined, 'CLP')).toBe('∞');
    expect(formatRoleLimitValue(15, '%')).toBe('15 %');
    expect(formatRoleLimitValue(50000, 'CLP')).toBe('$50.000');

    const el = fixture.nativeElement as HTMLElement;
    const tabs = Array.from(el.querySelectorAll<HTMLElement>('[role="tab"]'));
    tabs.find((t) => t.textContent?.includes('Límites'))?.click();
    TestBed.tick();
    fixture.detectChanges();
    await vi.waitFor(() => {
      const preview = el.querySelector('[data-testid="limit-preview-DISCOUNT_ITEM_MAX_PCT"]');
      expect(preview).not.toBeNull();
    });

    const itemDiscountPreview = el.querySelector(
      '[data-testid="limit-preview-DISCOUNT_ITEM_MAX_PCT"]',
    );
    const totalDiscountPreview = el.querySelector(
      '[data-testid="limit-preview-DISCOUNT_TOTAL_MAX_PCT"]',
    );
    const cashOutPreview = el.querySelector('[data-testid="limit-preview-CASH_OUT_MAX_AMOUNT"]');

    expect(itemDiscountPreview?.textContent?.trim()).toBe('∞');
    expect(totalDiscountPreview?.textContent?.trim()).toBe('15 %');
    expect(cashOutPreview?.textContent?.trim()).toBe('$50.000');
  });

  it('marca el control numérico como deshabilitado cuando el límite es ilimitado (null)', () => {
    const component = fixture.componentInstance as unknown as {
      limitsArray: {
        at(index: number): {
          controls: {
            code: { value: string };
            unlimited: { value: boolean; setValue: (v: boolean) => void };
            value: {
              disabled: boolean;
              value: number | null;
              setValue: (v: number | null) => void;
            };
          };
        };
      };
    };

    const firstRow = component.limitsArray.at(0);
    expect(firstRow.controls.code.value).toBe('DISCOUNT_ITEM_MAX_PCT');
    expect(firstRow.controls.unlimited.value).toBe(true);
    expect(firstRow.controls.value.disabled).toBe(true);

    // Al desmarcar "Sin límite", habilita el campo numérico con 0 por defecto
    firstRow.controls.unlimited.setValue(false);
    expect(firstRow.controls.value.disabled).toBe(false);
    expect(firstRow.controls.value.value).toBe(0);
  });

  it('guarda los permisos seleccionados vía PUT /api/v1/roles/{id}/permissions', async () => {
    const component = fixture.componentInstance as unknown as {
      togglePermission(code: string, checked: boolean): void;
      savePermissions(): void;
    };

    component.togglePermission('sales:sale:void', true);
    component.savePermissions();

    const req = http.expectOne('/api/v1/roles/3/permissions');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body.permissionCodes).toEqual(['sales:pos:operate', 'sales:sale:void']);
    flushJson(req, { id: 3, permissionCodes: ['sales:pos:operate', 'sales:sale:void'] });
    await fixture.whenStable();

    expect(snackBarOpen).toHaveBeenCalled();
  });

  it('guarda los límites enviando null para los ilimitados vía PUT /api/v1/roles/{id}/limits', async () => {
    const component = fixture.componentInstance as unknown as {
      saveLimits(): void;
    };

    component.saveLimits();

    const req = http.expectOne('/api/v1/roles/3/limits');
    expect(req.request.method).toBe('PUT');
    const sentLimits = req.request.body.limits as { code: string; value: number | null }[];
    expect(sentLimits.find((l) => l.code === 'DISCOUNT_ITEM_MAX_PCT')?.value).toBeNull();
    expect(sentLimits.find((l) => l.code === 'DISCOUNT_TOTAL_MAX_PCT')?.value).toBe(15);
    expect(sentLimits.find((l) => l.code === 'CASH_OUT_MAX_AMOUNT')?.value).toBe(50000);
    flushJson(req, []);
    await fixture.whenStable();

    expect(snackBarOpen).toHaveBeenCalled();
  });
});
