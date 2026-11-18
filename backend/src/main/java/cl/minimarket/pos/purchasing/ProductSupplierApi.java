package cl.minimarket.pos.purchasing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * API pública del módulo {@code purchasing} para proveedores y asociaciones producto-proveedor
 * ({@code PLAN.md} A.4).
 *
 * <p>Permite a {@code dataexchange} (importación masiva), {@code replenishment} (elección de
 * proveedor en sugerencias) y a las capas de órdenes/recepciones operar sobre proveedores y
 * condiciones de compra sin acoplarse a entidades internas del módulo.
 */
public interface ProductSupplierApi {

    /**
     * Crea o actualiza la asociación entre un producto y un proveedor ({@code product_suppliers}).
     *
     * <p>Si el par {@code (productId, supplierId)} no existe, crea la relación activa (rechazando
     * productos de tipo {@code PACK} con 400 {@code VALIDATION_ERROR}). Si ya existe, actualiza
     * sus condiciones de compra, la reactiva si estaba inactiva y mantiene consistencia de
     * proveedor preferido único por producto. Toda operación queda auditada como
     * {@code PRODUCT_SUPPLIER_CHANGED}.
     *
     * @param command datos de la asociación producto-proveedor
     * @return ficha resultante de la asociación
     */
    ProductSupplierInfo upsert(ProductSupplierCommand command);

    /**
     * Busca la asociación vigente (activa o inactiva) entre un producto y un proveedor.
     *
     * @param productId  identificador del producto
     * @param supplierId identificador del proveedor
     * @return asociación encontrada o vacío si no existe
     */
    Optional<ProductSupplierInfo> find(Long productId, Long supplierId);

    /**
     * Lista los proveedores candidatos activos para un producto (relación y proveedor ambos activos),
     * ordenados según la prioridad de reabastecimiento ({@code PLAN.md} C.4): primero el preferido,
     * luego menor costo unitario neto efectivo, menor lead time efectivo y menor identificador.
     *
     * @param productId identificador del producto
     * @return lista ordenada de candidatos activos
     */
    List<ProductSupplierCandidate> candidates(Long productId);

    /**
     * Busca un proveedor por su identificador.
     *
     * @param supplierId identificador del proveedor
     * @return datos básicos del proveedor o vacío si no existe
     */
    Optional<SupplierRef> findSupplierById(Long supplierId);

    /**
     * Busca un proveedor por su RUT (acepta cualquier formato; se normaliza con módulo 11).
     *
     * @param rut RUT del proveedor
     * @return datos básicos del proveedor o vacío si no existe
     */
    Optional<SupplierRef> findSupplierByRut(String rut);

    /**
     * Comando de alta o actualización de una asociación producto-proveedor vía {@link #upsert}.
     *
     * @param productId            identificador del producto (no puede ser {@code PACK})
     * @param supplierId           identificador del proveedor
     * @param supplierSku          SKU del proveedor (opcional; {@code null} conserva el actual en actualización)
     * @param purchaseUnitName     nombre de la unidad de compra (p. ej. {@code "Caja"}, {@code "Unidad"})
     * @param unitsPerPurchaseUnit unidades base contenidas en una unidad de compra (&gt; 0)
     * @param costPerPurchaseUnit  costo neto por unidad de compra (&ge; 0)
     * @param minOrderQty          cantidad mínima de pedido en unidades de compra (&ge; 1)
     * @param leadTimeDaysOverride días de reposición específicos (opcional; {@code null} conserva el actual en actualización)
     * @param isPreferred          si debe marcarse como preferido (en actualización, {@code null} conserva el actual)
     */
    record ProductSupplierCommand(
            Long productId,
            Long supplierId,
            String supplierSku,
            String purchaseUnitName,
            BigDecimal unitsPerPurchaseUnit,
            BigDecimal costPerPurchaseUnit,
            BigDecimal minOrderQty,
            Integer leadTimeDaysOverride,
            Boolean isPreferred
    ) {
        public ProductSupplierCommand {
            Objects.requireNonNull(productId, "productId");
            Objects.requireNonNull(supplierId, "supplierId");
            Objects.requireNonNull(purchaseUnitName, "purchaseUnitName");
            Objects.requireNonNull(unitsPerPurchaseUnit, "unitsPerPurchaseUnit");
            Objects.requireNonNull(costPerPurchaseUnit, "costPerPurchaseUnit");
            Objects.requireNonNull(minOrderQty, "minOrderQty");
        }
    }

    /**
     * Proyección pública de una asociación producto-proveedor.
     */
    record ProductSupplierInfo(
            Long id,
            Long productId,
            Long supplierId,
            String supplierSku,
            String purchaseUnitName,
            BigDecimal unitsPerPurchaseUnit,
            BigDecimal costPerPurchaseUnit,
            BigDecimal unitCostNet,
            BigDecimal minOrderQty,
            Integer leadTimeDaysOverride,
            boolean isPreferred,
            Instant lastPurchaseAt,
            boolean active
    ) {
    }

    /**
     * Candidato activo de abastecimiento para un producto ({@code PLAN.md} C.4).
     */
    record ProductSupplierCandidate(
            Long id,
            Long productId,
            Long supplierId,
            String supplierName,
            String purchaseUnitName,
            BigDecimal unitsPerPurchaseUnit,
            BigDecimal costPerPurchaseUnit,
            BigDecimal unitCostNet,
            BigDecimal minOrderQty,
            int effectiveLeadTimeDays,
            List<Integer> deliveryWeekdays,
            boolean isPreferred
    ) {
        public ProductSupplierCandidate {
            deliveryWeekdays = deliveryWeekdays == null ? List.of() : List.copyOf(deliveryWeekdays);
        }
    }

    /**
     * Proyección pública básica de un proveedor.
     */
    record SupplierRef(
            Long id,
            String rut,
            String legalName,
            String tradeName,
            int leadTimeDays,
            List<Integer> deliveryWeekdays,
            boolean active
    ) {
        public SupplierRef {
            deliveryWeekdays = deliveryWeekdays == null ? List.of() : List.copyOf(deliveryWeekdays);
        }
    }
}
