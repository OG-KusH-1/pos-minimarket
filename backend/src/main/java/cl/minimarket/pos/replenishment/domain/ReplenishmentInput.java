package cl.minimarket.pos.replenishment.domain;

import cl.minimarket.pos.purchasing.ProductSupplierApi.ProductSupplierCandidate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Entrada completa e independiente de infraestructura para calcular una línea de reabastecimiento.
 *
 * <p>La capa de aplicación obtiene previamente el stock, el tránsito, el historial y los candidatos
 * activos de proveedor. El calculador no realiza consultas ni conoce entidades JPA, relojes o
 * repositorios.</p>
 *
 * @param productId identificador del producto
 * @param active si el producto está activo; los inactivos se excluyen sin evaluar demanda
 * @param pack si el producto es un pack; los packs se excluyen porque su demanda ya está consolidada
 * @param productCreatedOn fecha de creación del producto, límite inferior de su historia válida
 * @param asOfDate fecha de corte; la ventana termina el día anterior
 * @param demandHistory observaciones diarias disponibles, dentro o fuera de la ventana
 * @param parameters parámetros efectivos ya resueltos
 * @param suppliers candidatos activos de abastecimiento; puede estar vacía
 * @param onHand stock en mano OH en unidad base
 * @param inTransit stock en tránsito IT en unidad base
 * @param minQuantity stock mínimo {@code min_qty}, o {@code null} si no está configurado
 * @param maxQuantity stock máximo {@code max_qty}, o {@code null} si no tiene tope
 * @param shelfLifeDays vida útil en días, o {@code null} si el producto no la configura
 */
public record ReplenishmentInput(
        Long productId,
        boolean active,
        boolean pack,
        LocalDate productCreatedOn,
        LocalDate asOfDate,
        List<DemandDay> demandHistory,
        ReplenishmentParameters parameters,
        List<ProductSupplierCandidate> suppliers,
        BigDecimal onHand,
        BigDecimal inTransit,
        BigDecimal minQuantity,
        BigDecimal maxQuantity,
        Integer shelfLifeDays
) {
    public ReplenishmentInput {
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(productCreatedOn, "productCreatedOn");
        Objects.requireNonNull(asOfDate, "asOfDate");
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(onHand, "onHand");
        Objects.requireNonNull(inTransit, "inTransit");
        demandHistory = demandHistory == null ? List.of() : List.copyOf(demandHistory);
        suppliers = suppliers == null ? List.of() : List.copyOf(suppliers);
    }
}
