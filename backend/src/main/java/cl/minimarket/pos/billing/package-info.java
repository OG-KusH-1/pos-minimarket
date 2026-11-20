/**
 * Módulo <b>Facturación</b> ({@code billing}).
 *
 * <p>Documentos tributarios simulados (boleta, boleta exenta, nota de crédito). Reacciona a los
 * eventos de {@code sales} y usa el puerto {@code ElectronicInvoiceProvider}.
 *
 * <p>Dependencias permitidas (columna "Depende de" de A.4 del plan): {@code shared}, {@code audit},
 * {@code organization}, {@code sales}.
 */
@ApplicationModule(
        displayName = "Facturación",
        allowedDependencies = {"shared", "audit", "organization", "sales"}
)
package cl.minimarket.pos.billing;

import org.springframework.modulith.ApplicationModule;
