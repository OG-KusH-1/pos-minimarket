-- =============================================================================
-- T14 / Migracion 3 (archivo 3): indices adicionales sobre columnas FK y de
-- consulta frecuente que B no listaba (documentados en B.13 "Indices adicionales").
--
-- Se ejecuta como pos_owner (Flyway). Convencion: ix_<tabla>_<cols>.
-- PostgreSQL no indexa solas las columnas de una FK: sin estos indices, el
-- borrado en cascada del padre y las consultas por padre recorren la tabla.
--
-- Sirven a:
--   sale_payments(sale_id), sale_discounts(sale_id): detalle de venta y reembolsos.
--   sale_lot_allocations(sale_line_id): lotes de origen al devolver una linea.
--   sale_returns(cash_session_id): reembolsos del turno en el cierre de caja.
--   sale_return_lines(sale_return_id): lineas de una devolucion.
--   sale_return_lines(sale_line_id): lo devuelto acumulado por linea (RN-13).
--   sale_return_refunds(sale_return_id): reembolsos de una devolucion.
--   cash_movements(cash_session_id): ingresos y retiros del turno (formula del esperado).
--   card_authorizations(cash_session_id): autorizaciones de tarjeta del turno.
--   customer_account_movements(sale_id) parcial: cargos de una venta (reverso del fiado).
--   tax_documents(sale_return_id) parcial: nota de credito de una devolucion.
--   goods_receipt_lines(goods_receipt_id), stock_transfer_lines(transfer_id),
--   stock_adjustment_lines(adjustment_id), promotion_items(promotion_id):
--     lineas hijas por cabecera (lectura del documento y borrado en cascada).
--   user_stores(store_id): usuarios de un local (aprobadores por local, RN-27).
--   replenishment_runs(store_id, as_of_date DESC): ultima corrida de un local.
-- =============================================================================

CREATE INDEX ix_sale_payments_sale                ON sale_payments (sale_id);
CREATE INDEX ix_sale_discounts_sale               ON sale_discounts (sale_id);
CREATE INDEX ix_sale_lot_allocations_sale_line    ON sale_lot_allocations (sale_line_id);
CREATE INDEX ix_sale_returns_cash_session         ON sale_returns (cash_session_id);
CREATE INDEX ix_sale_return_lines_return          ON sale_return_lines (sale_return_id);
CREATE INDEX ix_sale_return_lines_sale_line       ON sale_return_lines (sale_line_id);
CREATE INDEX ix_sale_return_refunds_return        ON sale_return_refunds (sale_return_id);
CREATE INDEX ix_cash_movements_cash_session       ON cash_movements (cash_session_id);
CREATE INDEX ix_card_authorizations_cash_session  ON card_authorizations (cash_session_id);
CREATE INDEX ix_customer_account_movements_sale   ON customer_account_movements (sale_id) WHERE sale_id IS NOT NULL;
CREATE INDEX ix_tax_documents_sale_return         ON tax_documents (sale_return_id) WHERE sale_return_id IS NOT NULL;
CREATE INDEX ix_goods_receipt_lines_receipt       ON goods_receipt_lines (goods_receipt_id);
CREATE INDEX ix_stock_transfer_lines_transfer     ON stock_transfer_lines (transfer_id);
CREATE INDEX ix_stock_adjustment_lines_adjustment ON stock_adjustment_lines (adjustment_id);
CREATE INDEX ix_promotion_items_promotion         ON promotion_items (promotion_id);
CREATE INDEX ix_user_stores_store                 ON user_stores (store_id);
CREATE INDEX ix_replenishment_runs_store_asof     ON replenishment_runs (store_id, as_of_date DESC);
