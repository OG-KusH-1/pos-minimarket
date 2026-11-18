import { FormControl } from '@angular/forms';
import { CategoryResponse } from '../../../api/models/category-response';
import {
  extractFileIdFromImageUrl,
  flattenCategories,
  formatMarginPct,
  formatUnitCost,
  integerValidator,
  isValidEanCheckDigit,
  trimmedMinLength,
  validateBarcodeValue,
} from './product-validators';

describe('product-validators', () => {
  describe('isValidEanCheckDigit y validateBarcodeValue', () => {
    it('valida EAN-13 y EAN-8 con dígito verificador GS1 correcto', () => {
      expect(isValidEanCheckDigit('7801234567894')).toBe(true);
      expect(isValidEanCheckDigit('7801234567890')).toBe(false);
      expect(isValidEanCheckDigit('12345670')).toBe(true);
      expect(isValidEanCheckDigit('12345678')).toBe(false);
      expect(isValidEanCheckDigit('123')).toBe(false);
    });

    it('acepta códigos válidos y rechaza formatos o check digits inválidos por tipo', () => {
      expect(validateBarcodeValue('7801234567894', 'EAN13')).toBeNull();
      expect(validateBarcodeValue('7801234567899', 'EAN13')).toEqual({ ean13Invalid: true });

      expect(validateBarcodeValue('12345670', 'EAN8')).toBeNull();
      expect(validateBarcodeValue('12345679', 'EAN8')).toEqual({ ean8Invalid: true });

      // UPC-A válido: 036000291452 (prefijado con 0 da EAN-13 válido)
      expect(validateBarcodeValue('036000291452', 'UPC_A')).toBeNull();
      expect(validateBarcodeValue('036000291459', 'UPC_A')).toEqual({ upcaInvalid: true });

      expect(validateBarcodeValue('INT001', 'INTERNAL')).toBeNull();
      expect(validateBarcodeValue('2000123', 'SCALE_PLU')).toBeNull();
      expect(validateBarcodeValue('ab', 'INTERNAL')).toEqual({ barcodeFormat: true });
      expect(validateBarcodeValue('   ', 'EAN13')).toEqual({ required: true });
    });
  });

  describe('validadores auxiliares y formateadores', () => {
    it('trimmedMinLength ignora espacios alrededor', () => {
      const validator = trimmedMinLength(3);
      expect(validator(new FormControl('   ', { nonNullable: true }))).toEqual({ required: true });
      expect(validator(new FormControl(' ab ', { nonNullable: true }))).toEqual({
        minlength: { requiredLength: 3, actualLength: 2 },
      });
      expect(validator(new FormControl(' abc ', { nonNullable: true }))).toBeNull();
    });

    it('integerValidator acepta vacíos y enteros y rechaza decimales', () => {
      expect(integerValidator(new FormControl(null))).toBeNull();
      expect(integerValidator(new FormControl(15))).toBeNull();
      expect(integerValidator(new FormControl(15.5))).toEqual({ integer: true });
    });

    it('flattenCategories aplana árbol de 2 niveles preservando jerarquía', () => {
      const tree: CategoryResponse[] = [
        {
          id: 1,
          name: 'Bebidas',
          active: true,
          children: [
            { id: 2, name: 'Gaseosas', active: true },
            { id: 3, name: 'Jugos', active: false },
          ],
        },
      ];
      expect(flattenCategories(tree)).toEqual([
        { id: 1, label: 'Bebidas', active: true, isChild: false },
        { id: 2, label: 'Bebidas › Gaseosas', active: true, isChild: true },
        { id: 3, label: 'Bebidas › Jugos', active: false, isChild: true },
      ]);
    });

    it('extractFileIdFromImageUrl y formateadores manejan valores nulos y válidos', () => {
      expect(extractFileIdFromImageUrl('/api/v1/files/42/content')).toBe(42);
      expect(extractFileIdFromImageUrl('https://host/api/v1/files/7/content?v=1')).toBe(7);
      expect(extractFileIdFromImageUrl('/otra/ruta')).toBeNull();
      expect(extractFileIdFromImageUrl(null)).toBeNull();

      expect(formatUnitCost(null)).toBe('—');
      expect(formatUnitCost(1250.5)).toContain('$');
      expect(formatMarginPct(undefined)).toBe('—');
      expect(formatMarginPct(32.5)).toContain('%');
    });
  });
});
