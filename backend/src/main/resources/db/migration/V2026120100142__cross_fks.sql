-- =============================================================================
-- T14 / Migracion 3 (archivo 2 de 2): FK hacia adelante
-- (las columnas se crearon sin FK en V2026120100101 porque su tabla destino
-- pertenece a B.7-B.11 y se crea en V2026120100141).
--
-- Se ejecuta como pos_owner (Flyway). Convenciones: las de T7/T10.
--
-- Revision de lo pendiente:
--   * B.1/B.2 (V...071): ninguna FK pendiente (lo declara su cabecera).
--   * B.3-B.6 (V...101): las tres de abajo. La circular lots <-> goods_receipt_lines
--     ya se cerro en la 101 (fk_goods_receipt_lines_lot), por eso no se repite aqui.
--   * B.7-B.11 (V...141): todas las FK se resolvieron en su propio archivo (el
--     orden de creacion sigue las dependencias), no queda ninguna pendiente.
-- =============================================================================

-- La orden de compra conserva su historia aunque se borre la corrida que la sugirio.
ALTER TABLE purchase_orders
    ADD CONSTRAINT fk_purchase_orders_replenishment_run
    FOREIGN KEY (replenishment_run_id) REFERENCES replenishment_runs (id) ON DELETE SET NULL;

ALTER TABLE purchase_order_lines
    ADD CONSTRAINT fk_purchase_order_lines_replenishment_line
    FOREIGN KEY (replenishment_line_id) REFERENCES replenishment_lines (id) ON DELETE SET NULL;

-- RESTRICT (RN-33): una linea de devolucion con merma derivada no se puede borrar.
ALTER TABLE shrinkages
    ADD CONSTRAINT fk_shrinkages_sale_return_line
    FOREIGN KEY (sale_return_line_id) REFERENCES sale_return_lines (id);
