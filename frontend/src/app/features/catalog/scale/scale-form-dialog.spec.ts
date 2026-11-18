import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideApiConfiguration } from '../../../api/api-configuration';
import { ScaleFormDialog, buildEan13Preview } from './scale-form-dialog';

function flushJson(req: TestRequest, data: unknown): void {
  if (req.request.responseType === 'blob') {
    req.flush(new Blob([JSON.stringify(data)], { type: 'application/json' }));
  } else {
    req.flush(data as Record<string, unknown>);
  }
}

describe('ScaleFormDialog', () => {
  let fixture: ComponentFixture<ScaleFormDialog>;
  let component: ScaleFormDialog;
  let http: HttpTestingController;
  let dialogClose: ReturnType<typeof vi.fn>;

  async function createDialog(config?: Record<string, unknown>): Promise<void> {
    dialogClose = vi.fn();
    await TestBed.configureTestingModule({
      imports: [ScaleFormDialog],
      providers: [
        provideApiConfiguration(''),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: MatDialogRef, useValue: { close: dialogClose } },
        { provide: MAT_DIALOG_DATA, useValue: config ? { config } : {} },
      ],
    }).compileComponents();

    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ScaleFormDialog);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  afterEach(() => {
    http?.verify();
    TestBed.resetTestingModule();
  });

  it('valida que el prefijo tenga 2 o 3 dígitos y el largo + inicio no supere 13', async () => {
    await createDialog();
    const form = (component as unknown as { form: ScaleFormDialog['form'] }).form;

    form.controls.name.setValue('Balanza frutas');
    form.controls.prefix.setValue('2');
    expect(form.controls.prefix.hasError('pattern')).toBe(true);

    form.controls.prefix.setValue('20');
    expect(form.controls.prefix.valid).toBe(true);

    // pluStart(9) + pluLength(5) = 14 > 13
    form.controls.pluStart.setValue(9);
    form.controls.pluLength.setValue(5);
    expect(form.controls.pluLength.hasError('rangeExceeded')).toBe(true);
    expect(form.invalid).toBe(true);

    // valueStart(8) + valueLength(6) = 14 > 13
    form.controls.pluStart.setValue(2);
    form.controls.pluLength.setValue(5);
    form.controls.valueStart.setValue(8);
    form.controls.valueLength.setValue(6);
    expect(form.controls.valueLength.hasError('rangeExceeded')).toBe(true);
    expect(form.invalid).toBe(true);

    // Rango válido: 20PPPPPWWWWWC
    form.controls.valueStart.setValue(7);
    form.controls.valueLength.setValue(5);
    expect(form.valid).toBe(true);
  });

  it('genera la vista previa EAN-13 y detecta superposición', () => {
    const valid = buildEan13Preview({
      prefix: '20',
      embeddedType: 'WEIGHT',
      pluStart: 2,
      pluLength: 5,
      valueStart: 7,
      valueLength: 5,
    });
    expect(valid.pattern).toBe('20PPPPPWWWWWC');
    expect(valid.hasOverlap).toBe(false);

    const overlap = buildEan13Preview({
      prefix: '20',
      embeddedType: 'PRICE',
      pluStart: 1,
      pluLength: 5,
      valueStart: 5,
      valueLength: 5,
    });
    expect(overlap.hasOverlap).toBe(true);
  });

  it('envía POST /api/v1/scale-barcode-configs al crear una configuración válida', async () => {
    await createDialog();
    const internal = component as unknown as {
      form: ScaleFormDialog['form'];
      submit(): void;
    };

    internal.form.setValue({
      name: 'Balanza peso kg',
      prefix: '20',
      embeddedType: 'WEIGHT',
      pluStart: 2,
      pluLength: 5,
      valueStart: 7,
      valueLength: 5,
      valueDecimals: 3,
    });

    internal.submit();

    const req = http.expectOne('/api/v1/scale-barcode-configs');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      name: 'Balanza peso kg',
      prefix: '20',
      embeddedType: 'WEIGHT',
      pluStart: 2,
      pluLength: 5,
      valueStart: 7,
      valueLength: 5,
      valueDecimals: 3,
    });

    flushJson(req, { id: 1, ...req.request.body, active: true });
    await fixture.whenStable();
    expect(dialogClose).toHaveBeenCalledWith(expect.objectContaining({ id: 1, prefix: '20' }));
  });

  it('envía PUT /api/v1/scale-barcode-configs/{id} en modo edición', async () => {
    await createDialog({
      id: 4,
      name: 'Balanza precio',
      prefix: '21',
      embeddedType: 'PRICE',
      pluStart: 2,
      pluLength: 5,
      valueStart: 7,
      valueLength: 5,
      valueDecimals: 0,
      active: true,
    });

    const internal = component as unknown as {
      form: ScaleFormDialog['form'];
      submit(): void;
    };
    internal.form.controls.name.setValue('Balanza precio rotisería');
    internal.submit();

    const req = http.expectOne('/api/v1/scale-barcode-configs/4');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body.name).toBe('Balanza precio rotisería');
    flushJson(req, { id: 4, ...req.request.body, active: true });
    await fixture.whenStable();
    expect(dialogClose).toHaveBeenCalledWith(
      expect.objectContaining({ id: 4, name: 'Balanza precio rotisería' }),
    );
  });
});
