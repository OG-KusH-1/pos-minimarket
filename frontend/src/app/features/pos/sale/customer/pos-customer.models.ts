import { CustomerResponse } from '../../../../api/models/customer-response';

/** Estado del fiado de un cliente derivado de `creditEnabled` (`HU-POS-16`). */
export type CustomerCreditState = 'ENABLED' | 'DISABLED';

/**
 * Determina el estado del fiado de un cliente a partir de sus datos de la API.
 *
 * Un cliente con `creditEnabled=false` no puede venderse a fiado: el backend respondería
 * `CREDIT_NOT_ENABLED` al registrar la venta (`API.md` F.2, fila de `POST /sales`).
 */
export function customerCreditState(customer: CustomerResponse): CustomerCreditState {
  return customer.creditEnabled === true ? 'ENABLED' : 'DISABLED';
}

/** Etiqueta en español del estado del fiado de un cliente. */
export function customerCreditStateLabel(customer: CustomerResponse): string {
  return customerCreditState(customer) === 'ENABLED' ? 'Fiado habilitado' : 'Sin fiado habilitado';
}
