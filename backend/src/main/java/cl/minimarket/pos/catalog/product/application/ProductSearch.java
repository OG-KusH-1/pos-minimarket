package cl.minimarket.pos.catalog.product.application;

import cl.minimarket.pos.catalog.ProductCatalogApi;

import java.util.List;

/**
 * Puerto interno del módulo {@code catalog} para búsqueda rápida de productos activos en el POS.
 *
 * <p>T28 provee la implementación completa con índice trigram y {@code f_unaccent} ({@code catalog/scale}).
 */
public interface ProductSearch {

    /**
     * Busca productos activos coincidentes con {@code q} hasta {@code limit} resultados.
     *
     * @param q     texto de búsqueda
     * @param limit máximo de elementos a devolver
     * @return lista de productos activos
     */
    List<ProductCatalogApi.ProductSnapshot> search(String q, int limit);
}
