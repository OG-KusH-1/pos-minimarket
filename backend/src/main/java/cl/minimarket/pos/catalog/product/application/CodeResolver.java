package cl.minimarket.pos.catalog.product.application;

import cl.minimarket.pos.catalog.ProductCatalogApi;

import java.util.Optional;

/**
 * Puerto interno del módulo {@code catalog} para resolver códigos escaneados o ingresados en POS.
 *
 * <p>T28 provee la implementación completa con soporte para códigos de balanza ({@code catalog/scale}).
 */
public interface CodeResolver {

    /**
     * Resuelve un código a un producto activo y su cantidad/precio embebido si corresponde.
     *
     * @param code código escaneado o ingresado
     * @return resultado o vacío si no existe coincidencia
     */
    Optional<ProductCatalogApi.ScanResult> resolve(String code);
}
