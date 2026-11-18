/**
 * Módulo <b>Facturación</b> ({@code billing}).
 *
 * <p>Documentos tributarios simulados (boleta, factura, nota de crédito). Reacciona a los eventos de {@code sales} y usa el puerto {@code ElectronicInvoiceProvider}.
 *
 * <p>Dependencias permitidas (columna "Depende de" de A.4 del plan): {@code shared}, {@code organization}, {@code sales}.
 * Para cambiarlas hay que actualizar primero el plan (A.4) y luego este archivo.
 */
@ApplicationModule(displayName = "Facturación", allowedDependencies = {"shared", "organization", "sales"})
package cl.minimarket.pos.billing;

import org.springframework.modulith.ApplicationModule;
