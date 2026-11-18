-- =============================================================================
-- T14 / Migracion 3 (archivo 1 de 2): clientes y cuenta corriente, caja y turnos,
-- ventas, promociones, reabastecimiento y agregados
-- (MODELO_DATOS B.7, B.8, B.9, B.10 y B.11; erratas J y correcciones K incorporadas).
--
-- Se ejecuta como pos_owner (Flyway). Depende de T7 (users, stores, terminals,
-- supervisor_authorizations) y de T10 (products, categories, brands, taxes, lots,
-- suppliers, product_suppliers). Los permisos DML de pos_app los dan los default
-- privileges del init de Docker; ninguna tabla de este archivo necesita REVOKE.
-- Las tablas APP son append-only "por diseno del codigo" (B "Notacion").
--
-- Convenciones: las mismas de T7 y T10 (pk_, fk_<tabla>_<ref>, uq_<tabla>_<cols>,
-- ck_<tabla>_<regla>, ix_<tabla>_<cols>; enums = varchar + CHECK, ADR-22; FK
-- ON DELETE RESTRICT por defecto, RN-33). AUD = created_at, created_by,
-- updated_at, updated_by, version. APP = solo created_at (las tablas que ya traen
-- una fecha de negocio propia, occurred_at, siguen el precedente de stock_movements
-- y no duplican created_at).
--
-- Orden de creacion: el de dependencias, no el de las secciones de B, para que no
-- haga falta ninguna FK hacia adelante dentro de este archivo:
--   B.7 customers -> B.8 cash_sessions, cash_movements, cash_session_counts
--   -> B.10 promotions (+stores, +items) -> B.9 sales, sale_lines, sale_line_taxes,
--   sale_discounts, card_authorizations, sale_payments, sale_lot_allocations,
--   suspended_sales, sale_returns (+lines, +refunds), tax_documents
--   -> B.7 customer_account_movements -> B.11 replenishment_* y daily_product_sales.
-- Las FK que apuntan a tablas creadas despues (y que viven en T10) estan en
-- V2026120100142__cross_fks.sql.
-- =============================================================================

-- =============================================================================
-- B.7 CLIENTES (cuenta corriente: el movimiento se crea mas abajo por sus FK a ventas)
-- =============================================================================

-- -----------------------------------------------------------------------------
-- customers
-- -----------------------------------------------------------------------------
CREATE TABLE customers (
    id             bigint GENERATED ALWAYS AS IDENTITY,
    rut            varchar(12),
    name           varchar(150)  NOT NULL,
    email          varchar(254),
    phone          varchar(30),
    address        varchar(250),
    credit_enabled boolean       NOT NULL DEFAULT false,
    credit_limit   numeric(14,0) NOT NULL DEFAULT 0,
    credit_balance numeric(14,0) NOT NULL DEFAULT 0,   -- deuda vigente (fiado)
    notes          text,
    active         boolean       NOT NULL DEFAULT true,
    created_at     timestamptz   NOT NULL DEFAULT now(),
    created_by     bigint,
    updated_at     timestamptz   NOT NULL DEFAULT now(),
    updated_by     bigint,
    version        bigint        NOT NULL DEFAULT 0,
    CONSTRAINT pk_customers PRIMARY KEY (id),
    CONSTRAINT fk_customers_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_customers_updated_by FOREIGN KEY (updated_by) REFERENCES users (id),
    CONSTRAINT ck_customers_credit_limit CHECK (credit_limit >= 0),
    CONSTRAINT ck_customers_credit_balance CHECK (credit_balance >= 0)
);
-- El RUT es opcional; el valido (modulo 11) se verifica en servicio (RN-30).
CREATE UNIQUE INDEX uq_customers_rut ON customers (rut) WHERE rut IS NOT NULL;
-- Misma expresion que ix_products_name_trgm: la consulta debe usar f_unaccent(lower(name)).
CREATE INDEX ix_customers_name_trgm ON customers USING gin (f_unaccent(lower(name)) gin_trgm_ops);

COMMENT ON TABLE customers IS 'Clientes, con o sin fiado. credit_balance es la deuda vigente; credit_balance + cargo <= credit_limit lo valida el servicio (RN-15). Se desactivan, no se borran.';

-- =============================================================================
-- B.8 CAJA Y TURNOS
-- =============================================================================

-- -----------------------------------------------------------------------------
-- cash_sessions (turnos de caja)
-- -----------------------------------------------------------------------------
CREATE TABLE cash_sessions (
    id                      bigint GENERATED ALWAYS AS IDENTITY,
    number                  varchar(24)   NOT NULL,
    store_id                bigint        NOT NULL,
    terminal_id             bigint        NOT NULL,
    cashier_user_id         bigint        NOT NULL,
    status                  varchar(10)   NOT NULL DEFAULT 'OPEN',
    business_date           date          NOT NULL,
    opened_at               timestamptz   NOT NULL DEFAULT now(),
    opening_amount          numeric(14,0) NOT NULL,
    closed_at               timestamptz,
    closed_by               bigint,
    expected_total          numeric(14,0),
    counted_total           numeric(14,0),
    difference_total        numeric(14,0),
    difference_approved_by  bigint,
    difference_approved_at  timestamptz,
    closing_notes           varchar(500),
    created_at              timestamptz   NOT NULL DEFAULT now(),
    created_by              bigint,
    updated_at              timestamptz   NOT NULL DEFAULT now(),
    updated_by              bigint,
    version                 bigint        NOT NULL DEFAULT 0,
    CONSTRAINT pk_cash_sessions PRIMARY KEY (id),
    CONSTRAINT uq_cash_sessions_number UNIQUE (number),
    CONSTRAINT fk_cash_sessions_store FOREIGN KEY (store_id) REFERENCES stores (id),
    CONSTRAINT fk_cash_sessions_terminal FOREIGN KEY (terminal_id) REFERENCES terminals (id),
    CONSTRAINT fk_cash_sessions_cashier FOREIGN KEY (cashier_user_id) REFERENCES users (id),
    CONSTRAINT fk_cash_sessions_closed_by FOREIGN KEY (closed_by) REFERENCES users (id),
    CONSTRAINT fk_cash_sessions_difference_approved_by FOREIGN KEY (difference_approved_by) REFERENCES users (id),
    CONSTRAINT fk_cash_sessions_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_cash_sessions_updated_by FOREIGN KEY (updated_by) REFERENCES users (id),
    CONSTRAINT ck_cash_sessions_status CHECK (status IN ('OPEN', 'CLOSED')),
    CONSTRAINT ck_cash_sessions_opening_amount CHECK (opening_amount >= 0),
    -- RN-23: quien aprueba la diferencia de cierre no es el cajero.
    CONSTRAINT ck_cash_sessions_approver_differs CHECK (difference_approved_by IS NULL OR difference_approved_by <> cashier_user_id)
);
-- RN-10: una terminal y un cajero tienen como maximo un turno abierto (tambien sustenta RN-09).
CREATE UNIQUE INDEX uq_cash_sessions_open_terminal ON cash_sessions (terminal_id) WHERE status = 'OPEN';
CREATE UNIQUE INDEX uq_cash_sessions_open_cashier ON cash_sessions (cashier_user_id) WHERE status = 'OPEN';
CREATE INDEX ix_cash_sessions_store_date ON cash_sessions (store_id, business_date);
CREATE INDEX ix_cash_sessions_cashier_date ON cash_sessions (cashier_user_id, business_date);

COMMENT ON TABLE cash_sessions IS 'Turnos de caja. Un turno OPEN por terminal y por cajero (RN-10). Los totales de cierre se calculan con la formula de B.8.';

-- -----------------------------------------------------------------------------
-- cash_movements (ingresos y retiros de efectivo; APP)
-- -----------------------------------------------------------------------------
CREATE TABLE cash_movements (
    id               bigint GENERATED ALWAYS AS IDENTITY,
    cash_session_id  bigint        NOT NULL,
    movement_type    varchar(10)   NOT NULL,
    amount           numeric(14,0) NOT NULL,
    reason           varchar(250)  NOT NULL,
    authorization_id bigint,
    user_id          bigint        NOT NULL,
    idempotency_key  uuid          NOT NULL,
    occurred_at      timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_cash_movements PRIMARY KEY (id),
    CONSTRAINT uq_cash_movements_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT fk_cash_movements_cash_session FOREIGN KEY (cash_session_id) REFERENCES cash_sessions (id),
    CONSTRAINT fk_cash_movements_authorization FOREIGN KEY (authorization_id) REFERENCES supervisor_authorizations (id),
    CONSTRAINT fk_cash_movements_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_cash_movements_type CHECK (movement_type IN ('CASH_IN', 'CASH_OUT')),
    CONSTRAINT ck_cash_movements_amount CHECK (amount > 0)
);

COMMENT ON TABLE cash_movements IS 'Ingresos (CASH_IN) y retiros (CASH_OUT) de efectivo dentro de un turno. Append-only; idempotente (ADR-14).';

-- -----------------------------------------------------------------------------
-- cash_session_counts (arqueo; APP, se escribe al cerrar)
-- -----------------------------------------------------------------------------
CREATE TABLE cash_session_counts (
    id                  bigint GENERATED ALWAYS AS IDENTITY,
    cash_session_id     bigint        NOT NULL,
    payment_method      varchar(20)   NOT NULL,
    expected_amount     numeric(14,0) NOT NULL,
    counted_amount      numeric(14,0) NOT NULL,
    difference          numeric(14,0) NOT NULL,
    denomination_detail jsonb,
    notes               varchar(250),
    created_at          timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_cash_session_counts PRIMARY KEY (id),
    CONSTRAINT uq_cash_session_counts_session_method UNIQUE (cash_session_id, payment_method),
    CONSTRAINT fk_cash_session_counts_cash_session FOREIGN KEY (cash_session_id) REFERENCES cash_sessions (id) ON DELETE CASCADE,
    CONSTRAINT ck_cash_session_counts_method CHECK (payment_method IN ('CASH', 'DEBIT', 'CREDIT', 'TRANSFER', 'CUSTOMER_CREDIT')),
    CONSTRAINT ck_cash_session_counts_difference CHECK (difference = counted_amount - expected_amount)
);

COMMENT ON TABLE cash_session_counts IS 'Arqueo por medio de pago al cerrar el turno. difference = counted_amount - expected_amount.';

-- =============================================================================
-- B.10 PROMOCIONES (antes de B.9 porque sale_discounts las referencia)
-- =============================================================================

-- -----------------------------------------------------------------------------
-- promotions
-- -----------------------------------------------------------------------------
CREATE TABLE promotions (
    id                        bigint GENERATED ALWAYS AS IDENTITY,
    name                      varchar(120)  NOT NULL,
    description               varchar(500),
    promotion_type            varchar(20)   NOT NULL,
    priority                  int           NOT NULL DEFAULT 100,
    stackable                 boolean       NOT NULL DEFAULT false,
    valid_from                timestamptz   NOT NULL,
    valid_to                  timestamptz,
    days_of_week              smallint[],
    time_from                 time,
    time_to                   time,
    all_stores                boolean       NOT NULL DEFAULT true,
    percent                   numeric(7,4),
    amount                    numeric(14,0),
    buy_qty                   int,
    pay_qty                   int,
    min_qty                   numeric(12,3),
    combo_price               numeric(14,0),
    max_applications_per_sale int,
    active                    boolean       NOT NULL DEFAULT true,
    created_at                timestamptz   NOT NULL DEFAULT now(),
    created_by                bigint,
    updated_at                timestamptz   NOT NULL DEFAULT now(),
    updated_by                bigint,
    version                   bigint        NOT NULL DEFAULT 0,
    CONSTRAINT pk_promotions PRIMARY KEY (id),
    CONSTRAINT fk_promotions_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_promotions_updated_by FOREIGN KEY (updated_by) REFERENCES users (id),
    CONSTRAINT ck_promotions_type CHECK (promotion_type IN ('PERCENT', 'FIXED_AMOUNT', 'BUY_X_PAY_Y', 'QUANTITY_PRICE', 'COMBO')),
    -- CK por tipo. Cada condicion exige explicitamente IS NOT NULL: en un CHECK un NULL
    -- cuenta como "cumple", asi que sin ello una promocion sin valor pasaria.
    CONSTRAINT ck_promotions_percent CHECK (promotion_type <> 'PERCENT'
        OR (percent IS NOT NULL AND percent > 0 AND percent <= 100)),
    CONSTRAINT ck_promotions_fixed_amount CHECK (promotion_type <> 'FIXED_AMOUNT'
        OR (amount IS NOT NULL AND amount > 0)),
    CONSTRAINT ck_promotions_buy_x_pay_y CHECK (promotion_type <> 'BUY_X_PAY_Y'
        OR (buy_qty IS NOT NULL AND pay_qty IS NOT NULL AND buy_qty > pay_qty AND pay_qty >= 1)),
    -- Precio unitario desde N unidades: min_qty = N, amount = precio unitario.
    CONSTRAINT ck_promotions_quantity_price CHECK (promotion_type <> 'QUANTITY_PRICE'
        OR (min_qty IS NOT NULL AND min_qty > 0 AND amount IS NOT NULL AND amount > 0)),
    CONSTRAINT ck_promotions_combo CHECK (promotion_type <> 'COMBO'
        OR (combo_price IS NOT NULL AND combo_price > 0)),
    CONSTRAINT ck_promotions_valid_range CHECK (valid_to IS NULL OR valid_to > valid_from)
);
CREATE INDEX ix_promotions_active_valid ON promotions (active, valid_from, valid_to);

COMMENT ON TABLE promotions IS 'Promociones. El motor aplica por priority ascendente; las no acumulables toman, por linea, la de mayor beneficio (B.10). Se desactivan, no se borran.';

-- -----------------------------------------------------------------------------
-- promotion_stores  (hija de reemplazo: DELETE + INSERT al reeditar; sin AUD)
-- -----------------------------------------------------------------------------
CREATE TABLE promotion_stores (
    promotion_id bigint NOT NULL,
    store_id     bigint NOT NULL,
    CONSTRAINT pk_promotion_stores PRIMARY KEY (promotion_id, store_id),
    CONSTRAINT fk_promotion_stores_promotion FOREIGN KEY (promotion_id) REFERENCES promotions (id) ON DELETE CASCADE,
    CONSTRAINT fk_promotion_stores_store FOREIGN KEY (store_id) REFERENCES stores (id)
);

COMMENT ON TABLE promotion_stores IS 'Locales donde rige una promocion con all_stores=false. Se reescribe completa al reeditar la promocion.';

-- -----------------------------------------------------------------------------
-- promotion_items  (hija de reemplazo; sin AUD)
-- -----------------------------------------------------------------------------
CREATE TABLE promotion_items (
    id           bigint GENERATED ALWAYS AS IDENTITY,
    promotion_id bigint        NOT NULL,
    target_type  varchar(10)   NOT NULL,
    product_id   bigint,
    category_id  bigint,
    brand_id     bigint,
    required_qty numeric(12,3) NOT NULL DEFAULT 1,
    CONSTRAINT pk_promotion_items PRIMARY KEY (id),
    CONSTRAINT fk_promotion_items_promotion FOREIGN KEY (promotion_id) REFERENCES promotions (id) ON DELETE CASCADE,
    CONSTRAINT fk_promotion_items_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT fk_promotion_items_category FOREIGN KEY (category_id) REFERENCES categories (id),
    CONSTRAINT fk_promotion_items_brand FOREIGN KEY (brand_id) REFERENCES brands (id),
    CONSTRAINT ck_promotion_items_target_type CHECK (target_type IN ('PRODUCT', 'CATEGORY', 'BRAND')),
    CONSTRAINT ck_promotion_items_one_target CHECK (num_nonnulls(product_id, category_id, brand_id) = 1),
    -- Coherencia con target_type: la unica columna informada es la que dice el tipo.
    CONSTRAINT ck_promotion_items_target_coherent CHECK (
        (target_type = 'PRODUCT') = (product_id IS NOT NULL)
        AND (target_type = 'CATEGORY') = (category_id IS NOT NULL)
        AND (target_type = 'BRAND') = (brand_id IS NOT NULL))
);
CREATE INDEX ix_promotion_items_product ON promotion_items (product_id);
CREATE INDEX ix_promotion_items_category ON promotion_items (category_id);
CREATE INDEX ix_promotion_items_brand ON promotion_items (brand_id);

COMMENT ON TABLE promotion_items IS 'Alcance de una promocion (producto, categoria o marca). Los COMBO solo usan PRODUCT: se valida en servicio. Se reescribe completa al reeditar.';

-- =============================================================================
-- B.9 VENTAS
-- =============================================================================

-- -----------------------------------------------------------------------------
-- sales
-- -----------------------------------------------------------------------------
CREATE TABLE sales (
    id                  bigint GENERATED ALWAYS AS IDENTITY,
    number              varchar(24)   NOT NULL,
    store_id            bigint        NOT NULL,
    terminal_id         bigint        NOT NULL,
    cash_session_id     bigint        NOT NULL,
    cashier_user_id     bigint        NOT NULL,
    customer_id         bigint,
    status              varchar(20)   NOT NULL DEFAULT 'COMPLETED',
    sold_at             timestamptz   NOT NULL DEFAULT now(),
    business_date       date          NOT NULL,
    idempotency_key     uuid          NOT NULL,
    subtotal_gross      numeric(14,0) NOT NULL,
    discount_total      numeric(14,0) NOT NULL DEFAULT 0,
    total_gross         numeric(14,0) NOT NULL,
    net_total           numeric(14,0) NOT NULL,
    tax_total           numeric(14,0) NOT NULL,
    exempt_total        numeric(14,0) NOT NULL DEFAULT 0,
    rounding_adjustment numeric(4,0)  NOT NULL DEFAULT 0,
    total_payable       numeric(14,0) NOT NULL,
    -- Lo que entrego el cliente: SUM(COALESCE(sale_payments.tendered_amount, sale_payments.amount)).
    paid_total          numeric(14,0) NOT NULL,
    change_total        numeric(14,0) NOT NULL DEFAULT 0,
    age_confirmed       boolean       NOT NULL DEFAULT false,
    email_to            varchar(254),
    created_at          timestamptz   NOT NULL DEFAULT now(),
    created_by          bigint,
    updated_at          timestamptz   NOT NULL DEFAULT now(),
    updated_by          bigint,
    version             bigint        NOT NULL DEFAULT 0,
    CONSTRAINT pk_sales PRIMARY KEY (id),
    CONSTRAINT uq_sales_number UNIQUE (number),
    CONSTRAINT uq_sales_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT fk_sales_store FOREIGN KEY (store_id) REFERENCES stores (id),
    CONSTRAINT fk_sales_terminal FOREIGN KEY (terminal_id) REFERENCES terminals (id),
    CONSTRAINT fk_sales_cash_session FOREIGN KEY (cash_session_id) REFERENCES cash_sessions (id),
    CONSTRAINT fk_sales_cashier FOREIGN KEY (cashier_user_id) REFERENCES users (id),
    CONSTRAINT fk_sales_customer FOREIGN KEY (customer_id) REFERENCES customers (id),
    CONSTRAINT fk_sales_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_sales_updated_by FOREIGN KEY (updated_by) REFERENCES users (id),
    CONSTRAINT ck_sales_status CHECK (status IN ('COMPLETED', 'VOIDED', 'PARTIALLY_RETURNED', 'RETURNED')),
    -- RN-07: totales coherentes. Que SUM(sale_payments.amount) = total_payable no se puede
    -- expresar como CK (cruza tablas): lo valida el servicio.
    CONSTRAINT ck_sales_total_gross CHECK (total_gross = subtotal_gross - discount_total),
    CONSTRAINT ck_sales_tax_breakdown CHECK (net_total + tax_total + exempt_total = total_gross),
    CONSTRAINT ck_sales_rounding_range CHECK (rounding_adjustment BETWEEN -5 AND 4),
    CONSTRAINT ck_sales_total_payable CHECK (total_payable = total_gross + rounding_adjustment),
    CONSTRAINT ck_sales_paid_vs_change CHECK (paid_total - change_total = total_payable)
);
CREATE INDEX ix_sales_store_date ON sales (store_id, business_date);
CREATE INDEX ix_sales_cash_session ON sales (cash_session_id);
CREATE INDEX ix_sales_cashier_date ON sales (cashier_user_id, business_date);
CREATE INDEX ix_sales_customer ON sales (customer_id) WHERE customer_id IS NOT NULL;

COMMENT ON TABLE sales IS 'Cabecera de venta. Los CK implementan RN-07; la unicidad de idempotency_key es la ultima barrera contra duplicados (ADR-14, RN-28).';

-- -----------------------------------------------------------------------------
-- sale_lines  (APP, salvo returned_quantity)
-- -----------------------------------------------------------------------------
CREATE TABLE sale_lines (
    id                  bigint GENERATED ALWAYS AS IDENTITY,
    sale_id             bigint        NOT NULL,
    line_number         smallint      NOT NULL,
    -- store_id y business_date son copia desnormalizada de sales (para reportes sin join);
    -- por eso store_id no lleva FK propia: ya la tiene sales.store_id.
    store_id            bigint        NOT NULL,
    business_date       date          NOT NULL,
    product_id          bigint        NOT NULL,
    sku                 varchar(40)   NOT NULL,        -- snapshot
    product_name        varchar(200)  NOT NULL,        -- snapshot
    scanned_code        varchar(20),
    sale_type           varchar(10)   NOT NULL,
    quantity            numeric(14,3) NOT NULL,
    stock_product_id    bigint        NOT NULL,        -- el base, si el producto es un pack
    stock_quantity      numeric(14,3) NOT NULL,        -- quantity x factor
    unit_price_gross    numeric(12,0) NOT NULL,
    line_subtotal_gross numeric(14,0) NOT NULL,
    discount_amount     numeric(14,0) NOT NULL DEFAULT 0,   -- manual + promocion + prorrateo
    line_total_gross    numeric(14,0) NOT NULL,
    line_net            numeric(14,0) NOT NULL,
    line_tax            numeric(14,0) NOT NULL,
    line_exempt         numeric(14,0) NOT NULL DEFAULT 0,
    unit_cost           numeric(14,4) NOT NULL,
    line_cost           numeric(16,2) NOT NULL,
    restricted          boolean       NOT NULL DEFAULT false,
    returned_quantity   numeric(14,3) NOT NULL DEFAULT 0,   -- unica columna que se actualiza
    created_at          timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_sale_lines PRIMARY KEY (id),
    CONSTRAINT uq_sale_lines_sale_line_number UNIQUE (sale_id, line_number),
    CONSTRAINT fk_sale_lines_sale FOREIGN KEY (sale_id) REFERENCES sales (id) ON DELETE CASCADE,
    CONSTRAINT fk_sale_lines_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT fk_sale_lines_stock_product FOREIGN KEY (stock_product_id) REFERENCES products (id),
    CONSTRAINT ck_sale_lines_sale_type CHECK (sale_type IN ('UNIT', 'WEIGHT', 'PACK')),
    CONSTRAINT ck_sale_lines_quantity CHECK (quantity > 0),
    -- RN-07 / RN-11: el descuento nunca deja la linea bajo $0.
    CONSTRAINT ck_sale_lines_total_gross CHECK (line_total_gross = line_subtotal_gross - discount_amount),
    CONSTRAINT ck_sale_lines_total_non_negative CHECK (line_total_gross >= 0),
    CONSTRAINT ck_sale_lines_tax_breakdown CHECK (line_net + line_tax + line_exempt = line_total_gross),
    -- RN-13: lo devuelto acumulado por linea no excede lo vendido.
    CONSTRAINT ck_sale_lines_returned_quantity CHECK (returned_quantity BETWEEN 0 AND quantity)
);
CREATE INDEX ix_sale_lines_store_date_product ON sale_lines (store_id, business_date, product_id);
CREATE INDEX ix_sale_lines_stock_product_date ON sale_lines (stock_product_id, business_date);

COMMENT ON TABLE sale_lines IS 'Lineas de venta con snapshot de SKU, nombre, precio y costo. Append-only salvo returned_quantity (RN-13). line_exempt es la porcion exenta de IVA.';

-- -----------------------------------------------------------------------------
-- sale_line_taxes  (APP)
-- -----------------------------------------------------------------------------
CREATE TABLE sale_line_taxes (
    id           bigint GENERATED ALWAYS AS IDENTITY,
    sale_line_id bigint        NOT NULL,
    tax_id       bigint        NOT NULL,
    tax_code     varchar(30)   NOT NULL,   -- snapshot
    rate         numeric(7,4)  NOT NULL,   -- snapshot
    base_amount  numeric(14,0) NOT NULL,
    tax_amount   numeric(14,0) NOT NULL,
    created_at   timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_sale_line_taxes PRIMARY KEY (id),
    CONSTRAINT uq_sale_line_taxes_line_tax UNIQUE (sale_line_id, tax_id),
    CONSTRAINT fk_sale_line_taxes_sale_line FOREIGN KEY (sale_line_id) REFERENCES sale_lines (id) ON DELETE CASCADE,
    CONSTRAINT fk_sale_line_taxes_tax FOREIGN KEY (tax_id) REFERENCES taxes (id)
);

COMMENT ON TABLE sale_line_taxes IS 'Desglose de impuestos por linea de venta, con la tasa vigente al vender (snapshot).';

-- -----------------------------------------------------------------------------
-- sale_discounts  (APP)
-- -----------------------------------------------------------------------------
CREATE TABLE sale_discounts (
    id               bigint GENERATED ALWAYS AS IDENTITY,
    sale_id          bigint        NOT NULL,
    sale_line_id     bigint,                  -- NULL = descuento al total
    source           varchar(10)   NOT NULL,
    promotion_id     bigint,
    discount_kind    varchar(10)   NOT NULL,
    value            numeric(12,4) NOT NULL,
    amount           numeric(14,0) NOT NULL,
    reason           varchar(250),
    applied_by       bigint        NOT NULL,
    authorization_id bigint,
    created_at       timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_sale_discounts PRIMARY KEY (id),
    CONSTRAINT fk_sale_discounts_sale FOREIGN KEY (sale_id) REFERENCES sales (id) ON DELETE CASCADE,
    CONSTRAINT fk_sale_discounts_sale_line FOREIGN KEY (sale_line_id) REFERENCES sale_lines (id) ON DELETE CASCADE,
    CONSTRAINT fk_sale_discounts_promotion FOREIGN KEY (promotion_id) REFERENCES promotions (id),
    CONSTRAINT fk_sale_discounts_applied_by FOREIGN KEY (applied_by) REFERENCES users (id),
    CONSTRAINT fk_sale_discounts_authorization FOREIGN KEY (authorization_id) REFERENCES supervisor_authorizations (id),
    CONSTRAINT ck_sale_discounts_source CHECK (source IN ('MANUAL', 'PROMOTION')),
    CONSTRAINT ck_sale_discounts_kind CHECK (discount_kind IN ('PERCENT', 'AMOUNT')),
    CONSTRAINT ck_sale_discounts_amount CHECK (amount > 0),
    CONSTRAINT ck_sale_discounts_promotion_source CHECK ((source = 'PROMOTION') = (promotion_id IS NOT NULL))
);
CREATE INDEX ix_sale_discounts_promotion ON sale_discounts (promotion_id);

COMMENT ON TABLE sale_discounts IS 'Descuentos aplicados (manuales o de promocion), a una linea o al total de la venta. Los manuales sobre el limite del rol referencian el override (RN-11, RN-26).';

-- -----------------------------------------------------------------------------
-- card_authorizations  (autorizaciones pedidas a la pasarela antes de cerrar la venta)
-- -----------------------------------------------------------------------------
CREATE TABLE card_authorizations (
    id                 bigint GENERATED ALWAYS AS IDENTITY,
    store_id           bigint        NOT NULL,
    terminal_id        bigint        NOT NULL,
    cash_session_id    bigint        NOT NULL,
    method             varchar(10)   NOT NULL,
    amount             numeric(14,0) NOT NULL,
    idempotency_key    uuid          NOT NULL,
    status             varchar(15)   NOT NULL,
    gateway_reference  varchar(60),
    authorization_code varchar(20),
    card_brand         varchar(20),
    card_last4         char(4),                -- nunca se guarda el PAN
    sale_id            bigint,                 -- se completa al cerrar la venta
    message            varchar(250),
    created_at         timestamptz   NOT NULL DEFAULT now(),
    created_by         bigint,
    updated_at         timestamptz   NOT NULL DEFAULT now(),
    updated_by         bigint,
    version            bigint        NOT NULL DEFAULT 0,
    CONSTRAINT pk_card_authorizations PRIMARY KEY (id),
    CONSTRAINT uq_card_authorizations_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT fk_card_authorizations_store FOREIGN KEY (store_id) REFERENCES stores (id),
    CONSTRAINT fk_card_authorizations_terminal FOREIGN KEY (terminal_id) REFERENCES terminals (id),
    CONSTRAINT fk_card_authorizations_cash_session FOREIGN KEY (cash_session_id) REFERENCES cash_sessions (id),
    CONSTRAINT fk_card_authorizations_sale FOREIGN KEY (sale_id) REFERENCES sales (id),
    CONSTRAINT fk_card_authorizations_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_card_authorizations_updated_by FOREIGN KEY (updated_by) REFERENCES users (id),
    CONSTRAINT ck_card_authorizations_method CHECK (method IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_card_authorizations_amount CHECK (amount > 0),
    CONSTRAINT ck_card_authorizations_status CHECK (status IN ('APPROVED', 'DECLINED', 'ERROR', 'CAPTURED', 'VOIDED'))
);
-- Autorizaciones aprobadas que aun no se asociaron a una venta (candidatas a reversar).
CREATE INDEX ix_card_authorizations_orphan_approved ON card_authorizations (status)
    WHERE status = 'APPROVED' AND sale_id IS NULL;

COMMENT ON TABLE card_authorizations IS 'Autorizaciones de tarjeta pedidas a la pasarela antes de cerrar la venta (ADR-14). sale_id se completa al cerrar; las aprobadas sin venta se reversan.';

-- -----------------------------------------------------------------------------
-- sale_payments  (APP, salvo status)
-- -----------------------------------------------------------------------------
CREATE TABLE sale_payments (
    id                    bigint GENERATED ALWAYS AS IDENTITY,
    sale_id               bigint        NOT NULL,
    method                varchar(20)   NOT NULL,
    -- Monto APLICADO a la venta; en efectivo = entregado - vuelto, ya con el redondeo (RN-07, RN-08).
    amount                numeric(14,0) NOT NULL,
    tendered_amount       numeric(14,0),                   -- entregado; solo CASH
    change_amount         numeric(14,0) NOT NULL DEFAULT 0, -- vuelto; solo CASH
    status                varchar(10)   NOT NULL DEFAULT 'APPROVED',
    gateway_reference     varchar(60),
    authorization_code    varchar(20),
    card_brand            varchar(20),
    card_last4            char(4),                          -- nunca se guarda el PAN
    transfer_reference    varchar(60),
    card_authorization_id bigint,
    created_at            timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_sale_payments PRIMARY KEY (id),
    CONSTRAINT fk_sale_payments_sale FOREIGN KEY (sale_id) REFERENCES sales (id) ON DELETE CASCADE,
    CONSTRAINT fk_sale_payments_card_authorization FOREIGN KEY (card_authorization_id) REFERENCES card_authorizations (id),
    CONSTRAINT ck_sale_payments_method CHECK (method IN ('CASH', 'DEBIT', 'CREDIT', 'TRANSFER', 'CUSTOMER_CREDIT')),
    CONSTRAINT ck_sale_payments_status CHECK (status IN ('APPROVED', 'REVERSED')),
    CONSTRAINT ck_sale_payments_amount CHECK (amount > 0),
    CONSTRAINT ck_sale_payments_tendered_covers_amount CHECK (method <> 'CASH' OR tendered_amount >= amount),
    -- RN-08: solo el efectivo tiene monto entregado (y por tanto vuelto).
    CONSTRAINT ck_sale_payments_tendered_only_cash CHECK ((method = 'CASH') = (tendered_amount IS NOT NULL)),
    CONSTRAINT ck_sale_payments_change CHECK (change_amount = COALESCE(tendered_amount, amount) - amount),
    CONSTRAINT ck_sale_payments_card_last4 CHECK (card_last4 ~ '^[0-9]{4}$')
);
-- Una autorizacion de tarjeta respalda como maximo un pago.
CREATE UNIQUE INDEX uq_sale_payments_card_authorization ON sale_payments (card_authorization_id)
    WHERE card_authorization_id IS NOT NULL;

COMMENT ON TABLE sale_payments IS 'Pagos de una venta. amount es lo aplicado a la venta (SUM = total_payable, RN-07) y tendered_amount lo entregado en efectivo. Nunca se guarda el PAN.';

-- -----------------------------------------------------------------------------
-- sale_lot_allocations  (APP)
-- -----------------------------------------------------------------------------
CREATE TABLE sale_lot_allocations (
    id           bigint GENERATED ALWAYS AS IDENTITY,
    sale_line_id bigint        NOT NULL,
    lot_id       bigint        NOT NULL,
    quantity     numeric(14,3) NOT NULL,
    created_at   timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_sale_lot_allocations PRIMARY KEY (id),
    CONSTRAINT fk_sale_lot_allocations_sale_line FOREIGN KEY (sale_line_id) REFERENCES sale_lines (id) ON DELETE CASCADE,
    CONSTRAINT fk_sale_lot_allocations_lot FOREIGN KEY (lot_id) REFERENCES lots (id),
    CONSTRAINT ck_sale_lot_allocations_quantity CHECK (quantity > 0)
);

COMMENT ON TABLE sale_lot_allocations IS 'Lotes de los que salio cada linea de venta (FEFO, RN-03). Permite devolver al lote de origen.';

-- -----------------------------------------------------------------------------
-- suspended_sales  (borrado fisico al retomar o expirar; no afectan stock)
-- -----------------------------------------------------------------------------
CREATE TABLE suspended_sales (
    id          bigint GENERATED ALWAYS AS IDENTITY,
    store_id    bigint       NOT NULL,
    terminal_id bigint       NOT NULL,
    user_id     bigint       NOT NULL,
    label       varchar(60),
    customer_id bigint,
    -- {schemaVersion, lines:[{productId, qty, scannedCode, manualDiscount}], totalDiscount}
    cart        jsonb        NOT NULL,
    expires_at  timestamptz  NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    created_by  bigint,
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    updated_by  bigint,
    version     bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_suspended_sales PRIMARY KEY (id),
    CONSTRAINT fk_suspended_sales_store FOREIGN KEY (store_id) REFERENCES stores (id),
    CONSTRAINT fk_suspended_sales_terminal FOREIGN KEY (terminal_id) REFERENCES terminals (id),
    CONSTRAINT fk_suspended_sales_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_suspended_sales_customer FOREIGN KEY (customer_id) REFERENCES customers (id),
    CONSTRAINT fk_suspended_sales_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_suspended_sales_updated_by FOREIGN KEY (updated_by) REFERENCES users (id)
);
CREATE INDEX ix_suspended_sales_terminal_created ON suspended_sales (terminal_id, created_at);

COMMENT ON TABLE suspended_sales IS 'Ventas en espera (carrito serializado). Se borran fisicamente al retomar o expirar; no reservan stock.';

-- -----------------------------------------------------------------------------
-- sale_returns  (anulacion o devolucion; APP)
-- -----------------------------------------------------------------------------
CREATE TABLE sale_returns (
    id                bigint GENERATED ALWAYS AS IDENTITY,
    number            varchar(24)   NOT NULL,
    sale_id           bigint        NOT NULL,
    return_type       varchar(10)   NOT NULL,
    store_id          bigint        NOT NULL,
    terminal_id       bigint        NOT NULL,
    cash_session_id   bigint        NOT NULL,
    reason_code       varchar(30)   NOT NULL,
    reason_text       varchar(500),
    refund_total      numeric(14,0) NOT NULL,
    authorization_id  bigint,
    idempotency_key   uuid          NOT NULL,
    created_by        bigint        NOT NULL,
    created_at        timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_sale_returns PRIMARY KEY (id),
    CONSTRAINT uq_sale_returns_number UNIQUE (number),
    CONSTRAINT uq_sale_returns_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT fk_sale_returns_sale FOREIGN KEY (sale_id) REFERENCES sales (id),
    CONSTRAINT fk_sale_returns_store FOREIGN KEY (store_id) REFERENCES stores (id),
    CONSTRAINT fk_sale_returns_terminal FOREIGN KEY (terminal_id) REFERENCES terminals (id),
    CONSTRAINT fk_sale_returns_cash_session FOREIGN KEY (cash_session_id) REFERENCES cash_sessions (id),
    CONSTRAINT fk_sale_returns_authorization FOREIGN KEY (authorization_id) REFERENCES supervisor_authorizations (id),
    CONSTRAINT fk_sale_returns_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT ck_sale_returns_type CHECK (return_type IN ('VOID', 'RETURN')),
    CONSTRAINT ck_sale_returns_reason_code CHECK (reason_code IN
        ('CUSTOMER_REQUEST', 'DEFECTIVE', 'EXPIRED', 'WRONG_ITEM', 'CASHIER_ERROR', 'PRICE_ERROR', 'OTHER'))
);
CREATE INDEX ix_sale_returns_sale ON sale_returns (sale_id);
CREATE INDEX ix_sale_returns_store_created ON sale_returns (store_id, created_at);

COMMENT ON TABLE sale_returns IS 'Anulaciones (VOID, total y solo con el turno original abierto) y devoluciones (RETURN) de ventas (RN-12). Append-only.';

-- -----------------------------------------------------------------------------
-- sale_return_lines  (APP)
-- -----------------------------------------------------------------------------
CREATE TABLE sale_return_lines (
    id               bigint GENERATED ALWAYS AS IDENTITY,
    sale_return_id   bigint        NOT NULL,
    sale_line_id     bigint        NOT NULL,
    quantity         numeric(14,3) NOT NULL,
    stock_quantity   numeric(14,3) NOT NULL,
    restock          boolean       NOT NULL DEFAULT true,
    refund_amount    numeric(14,0) NOT NULL,   -- proporcional a sale_lines.line_total_gross (RN-14)
    refund_net       numeric(14,0) NOT NULL,
    refund_tax       numeric(14,0) NOT NULL,
    created_at       timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_sale_return_lines PRIMARY KEY (id),
    CONSTRAINT fk_sale_return_lines_sale_return FOREIGN KEY (sale_return_id) REFERENCES sale_returns (id) ON DELETE CASCADE,
    CONSTRAINT fk_sale_return_lines_sale_line FOREIGN KEY (sale_line_id) REFERENCES sale_lines (id),
    CONSTRAINT ck_sale_return_lines_quantity CHECK (quantity > 0)
);

COMMENT ON TABLE sale_return_lines IS 'Lineas devueltas. Con restock=false se crea una merma DAMAGED pendiente (shrinkages.sale_return_line_id, B.6).';

-- -----------------------------------------------------------------------------
-- sale_return_refunds  (APP)
-- -----------------------------------------------------------------------------
CREATE TABLE sale_return_refunds (
    id                bigint GENERATED ALWAYS AS IDENTITY,
    sale_return_id    bigint        NOT NULL,
    method            varchar(20)   NOT NULL,
    amount            numeric(14,0) NOT NULL,
    gateway_reference varchar(60),
    created_at        timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_sale_return_refunds PRIMARY KEY (id),
    CONSTRAINT fk_sale_return_refunds_sale_return FOREIGN KEY (sale_return_id) REFERENCES sale_returns (id) ON DELETE CASCADE,
    CONSTRAINT ck_sale_return_refunds_method CHECK (method IN ('CASH', 'DEBIT', 'CREDIT', 'TRANSFER', 'CUSTOMER_CREDIT')),
    CONSTRAINT ck_sale_return_refunds_amount CHECK (amount > 0)
);

COMMENT ON TABLE sale_return_refunds IS 'Como se reembolsa una devolucion, por medio de pago (RN-14).';

-- -----------------------------------------------------------------------------
-- tax_documents  (boletas y notas de credito electronicas)
-- -----------------------------------------------------------------------------
CREATE TABLE tax_documents (
    id                     bigint GENERATED ALWAYS AS IDENTITY,
    store_id               bigint        NOT NULL,
    document_type          varchar(20)   NOT NULL,
    sii_type_code          smallint      NOT NULL,   -- 39 boleta, 41 boleta exenta, 61 nota de credito
    folio                  bigint        NOT NULL,
    sale_id                bigint,
    sale_return_id         bigint,
    referenced_document_id bigint,
    status                 varchar(10)   NOT NULL DEFAULT 'PENDING',
    provider               varchar(30)   NOT NULL,
    provider_track_id      varchar(60),
    net_amount             numeric(14,0) NOT NULL,
    exempt_amount          numeric(14,0) NOT NULL,
    tax_amount             numeric(14,0) NOT NULL,
    total_amount           numeric(14,0) NOT NULL,
    payload                jsonb         NOT NULL,
    attempts               int           NOT NULL DEFAULT 0,
    last_error             text,
    issued_at              timestamptz,
    created_at             timestamptz   NOT NULL DEFAULT now(),
    created_by             bigint,
    updated_at             timestamptz   NOT NULL DEFAULT now(),
    updated_by             bigint,
    version                bigint        NOT NULL DEFAULT 0,
    CONSTRAINT pk_tax_documents PRIMARY KEY (id),
    CONSTRAINT uq_tax_documents_type_folio UNIQUE (document_type, folio),
    CONSTRAINT fk_tax_documents_store FOREIGN KEY (store_id) REFERENCES stores (id),
    CONSTRAINT fk_tax_documents_sale FOREIGN KEY (sale_id) REFERENCES sales (id),
    CONSTRAINT fk_tax_documents_sale_return FOREIGN KEY (sale_return_id) REFERENCES sale_returns (id),
    CONSTRAINT fk_tax_documents_referenced_document FOREIGN KEY (referenced_document_id) REFERENCES tax_documents (id),
    CONSTRAINT fk_tax_documents_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_tax_documents_updated_by FOREIGN KEY (updated_by) REFERENCES users (id),
    CONSTRAINT ck_tax_documents_document_type CHECK (document_type IN ('BOLETA', 'BOLETA_EXENTA', 'CREDIT_NOTE')),
    CONSTRAINT ck_tax_documents_sii_type_code CHECK (sii_type_code IN (39, 41, 61)),
    CONSTRAINT ck_tax_documents_status CHECK (status IN ('PENDING', 'ISSUED', 'REJECTED', 'ERROR')),
    -- Un documento nace de una venta o de una devolucion, nunca de ambas ni de ninguna.
    CONSTRAINT ck_tax_documents_one_origin CHECK (num_nonnulls(sale_id, sale_return_id) = 1)
);
-- Cola de reintento: solo los documentos pendientes o con error.
CREATE INDEX ix_tax_documents_retry ON tax_documents (status) WHERE status IN ('PENDING', 'ERROR');
CREATE INDEX ix_tax_documents_sale ON tax_documents (sale_id);

COMMENT ON TABLE tax_documents IS 'Documentos tributarios electronicos (boleta, boleta exenta, nota de credito). Folios unicos por tipo y sin huecos (RN-29).';

-- =============================================================================
-- B.7 CUENTA CORRIENTE DE CLIENTES (aqui por sus FK a sales y sale_returns)
-- =============================================================================

-- -----------------------------------------------------------------------------
-- customer_account_movements  (APP)
-- -----------------------------------------------------------------------------
CREATE TABLE customer_account_movements (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    customer_id     bigint        NOT NULL,
    store_id        bigint        NOT NULL,
    movement_type   varchar(15)   NOT NULL,
    amount          numeric(14,0) NOT NULL,   -- positivo aumenta la deuda
    balance_after   numeric(14,0) NOT NULL,
    sale_id         bigint,
    sale_return_id  bigint,
    payment_method  varchar(20),
    cash_session_id bigint,
    user_id         bigint        NOT NULL,
    idempotency_key uuid,                     -- los abonos son idempotentes (ADR-14)
    notes           varchar(250),
    occurred_at     timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_customer_account_movements PRIMARY KEY (id),
    CONSTRAINT uq_customer_account_movements_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT fk_customer_account_movements_customer FOREIGN KEY (customer_id) REFERENCES customers (id),
    CONSTRAINT fk_customer_account_movements_store FOREIGN KEY (store_id) REFERENCES stores (id),
    CONSTRAINT fk_customer_account_movements_sale FOREIGN KEY (sale_id) REFERENCES sales (id),
    CONSTRAINT fk_customer_account_movements_sale_return FOREIGN KEY (sale_return_id) REFERENCES sale_returns (id),
    CONSTRAINT fk_customer_account_movements_cash_session FOREIGN KEY (cash_session_id) REFERENCES cash_sessions (id),
    CONSTRAINT fk_customer_account_movements_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_customer_account_movements_type CHECK (movement_type IN ('CHARGE', 'PAYMENT', 'REVERSAL', 'ADJUSTMENT')),
    CONSTRAINT ck_customer_account_movements_payment_method CHECK (payment_method IS NULL
        OR payment_method IN ('CASH', 'DEBIT', 'CREDIT', 'TRANSFER', 'CUSTOMER_CREDIT')),
    CONSTRAINT ck_customer_account_movements_amount CHECK (amount <> 0),
    CONSTRAINT ck_customer_account_movements_balance_after CHECK (balance_after >= 0)
);
CREATE INDEX ix_customer_account_movements_customer_occurred ON customer_account_movements (customer_id, occurred_at DESC);
CREATE INDEX ix_customer_account_movements_cash_session ON customer_account_movements (cash_session_id);

COMMENT ON TABLE customer_account_movements IS 'Libro de la cuenta corriente (fiado) de cada cliente. amount > 0 aumenta la deuda; balance_after es la deuda resultante (RN-15). Append-only.';

-- =============================================================================
-- B.11 REABASTECIMIENTO Y AGREGADOS
-- =============================================================================

-- -----------------------------------------------------------------------------
-- replenishment_params  (parametros por alcance; una columna NULL = hereda del alcance superior)
-- -----------------------------------------------------------------------------
CREATE TABLE replenishment_params (
    id                  bigint GENERATED ALWAYS AS IDENTITY,
    scope               varchar(10)  NOT NULL,
    store_id            bigint,
    category_id         bigint,
    product_id          bigint,
    window_days         int,
    min_history_days    int,
    service_level_z     numeric(5,3),
    safety_min_days     numeric(6,2),
    review_period_days  int,
    outlier_k           numeric(5,2),
    shelf_life_factor   numeric(4,3),
    enabled             boolean,
    created_at          timestamptz  NOT NULL DEFAULT now(),
    created_by          bigint,
    updated_at          timestamptz  NOT NULL DEFAULT now(),
    updated_by          bigint,
    version             bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_replenishment_params PRIMARY KEY (id),
    CONSTRAINT fk_replenishment_params_store FOREIGN KEY (store_id) REFERENCES stores (id),
    CONSTRAINT fk_replenishment_params_category FOREIGN KEY (category_id) REFERENCES categories (id),
    CONSTRAINT fk_replenishment_params_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT fk_replenishment_params_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_replenishment_params_updated_by FOREIGN KEY (updated_by) REFERENCES users (id),
    CONSTRAINT ck_replenishment_params_scope CHECK (scope IN ('GLOBAL', 'STORE', 'CATEGORY', 'PRODUCT')),
    -- Coherencia scope <-> FK. B no detalla la matriz; se interpreta asi (ver informe T14):
    --   GLOBAL   : sin local, categoria ni producto.
    --   STORE    : local obligatorio; sin categoria ni producto.
    --   CATEGORY : categoria obligatoria; sin producto; local opcional (global o por local).
    --   PRODUCT  : producto obligatorio; sin categoria; local opcional.
    CONSTRAINT ck_replenishment_params_scope_fk CHECK (
        (scope = 'GLOBAL'   AND store_id IS NULL     AND category_id IS NULL     AND product_id IS NULL)
     OR (scope = 'STORE'    AND store_id IS NOT NULL AND category_id IS NULL     AND product_id IS NULL)
     OR (scope = 'CATEGORY' AND category_id IS NOT NULL AND product_id IS NULL)
     OR (scope = 'PRODUCT'  AND product_id IS NOT NULL  AND category_id IS NULL))
);
-- Una sola fila por alcance; COALESCE(...,0) hace unicas tambien las filas con columnas NULL.
CREATE UNIQUE INDEX uq_replenishment_params_scope ON replenishment_params
    (scope, COALESCE(store_id, 0), COALESCE(category_id, 0), COALESCE(product_id, 0));

COMMENT ON TABLE replenishment_params IS 'Parametros de reabastecimiento (C.2) por alcance GLOBAL > STORE > CATEGORY > PRODUCT. Una columna NULL hereda del alcance superior. Se borran fisicamente via su endpoint.';

-- -----------------------------------------------------------------------------
-- replenishment_runs
-- -----------------------------------------------------------------------------
CREATE TABLE replenishment_runs (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    store_id        bigint       NOT NULL,
    as_of_date      date         NOT NULL,
    status          varchar(15)  NOT NULL,
    params_snapshot jsonb        NOT NULL,
    lines_count     int          NOT NULL DEFAULT 0,
    started_at      timestamptz  NOT NULL,
    finished_at     timestamptz,
    error           text,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    created_by      bigint,
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    updated_by      bigint,
    version         bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_replenishment_runs PRIMARY KEY (id),
    CONSTRAINT fk_replenishment_runs_store FOREIGN KEY (store_id) REFERENCES stores (id),
    CONSTRAINT fk_replenishment_runs_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_replenishment_runs_updated_by FOREIGN KEY (updated_by) REFERENCES users (id),
    CONSTRAINT ck_replenishment_runs_status CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED'))
);

COMMENT ON TABLE replenishment_runs IS 'Corridas del calculo de reabastecimiento por local. params_snapshot guarda los parametros efectivos usados.';

-- -----------------------------------------------------------------------------
-- replenishment_lines
-- -----------------------------------------------------------------------------
CREATE TABLE replenishment_lines (
    id                       bigint GENERATED ALWAYS AS IDENTITY,
    run_id                   bigint        NOT NULL,
    product_id               bigint        NOT NULL,
    supplier_id              bigint,
    product_supplier_id      bigint,
    valid_days               int           NOT NULL,
    censored_days            int           NOT NULL,
    avg_daily_demand         numeric(14,4) NOT NULL,
    demand_std               numeric(14,4) NOT NULL,
    lead_time_days           int           NOT NULL,
    review_period_days       int           NOT NULL,
    on_hand                  numeric(14,3) NOT NULL,
    in_transit               numeric(14,3) NOT NULL,
    safety_stock             numeric(14,4) NOT NULL,
    reorder_point            numeric(14,4) NOT NULL,
    target_level             numeric(14,4) NOT NULL,
    suggested_qty            numeric(14,3) NOT NULL,
    suggested_purchase_units numeric(12,3) NOT NULL,
    final_purchase_units     numeric(12,3),
    unit_cost_net            numeric(14,4),             -- snapshot para estimatedCost
    estimated_stockout_date  date,
    days_of_cover            numeric(10,2),
    status                   varchar(15)   NOT NULL,
    flags                    varchar(30)[] NOT NULL DEFAULT '{}'::varchar[],
    created_at               timestamptz   NOT NULL DEFAULT now(),
    created_by               bigint,
    updated_at               timestamptz   NOT NULL DEFAULT now(),
    updated_by               bigint,
    version                  bigint        NOT NULL DEFAULT 0,
    CONSTRAINT pk_replenishment_lines PRIMARY KEY (id),
    CONSTRAINT uq_replenishment_lines_run_product UNIQUE (run_id, product_id),
    CONSTRAINT fk_replenishment_lines_run FOREIGN KEY (run_id) REFERENCES replenishment_runs (id) ON DELETE CASCADE,
    CONSTRAINT fk_replenishment_lines_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT fk_replenishment_lines_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers (id),
    CONSTRAINT fk_replenishment_lines_product_supplier FOREIGN KEY (product_supplier_id) REFERENCES product_suppliers (id),
    CONSTRAINT fk_replenishment_lines_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_replenishment_lines_updated_by FOREIGN KEY (updated_by) REFERENCES users (id),
    CONSTRAINT ck_replenishment_lines_status CHECK (status IN
        ('SUGGESTED', 'NO_ACTION', 'NO_SUPPLIER', 'EXCLUDED', 'ACCEPTED', 'DISCARDED')),
    -- Enum replenishment_flag (B.14) en un arreglo: cada elemento debe ser uno de sus valores.
    CONSTRAINT ck_replenishment_lines_flags CHECK (flags <@ ARRAY['NEW_PRODUCT', 'SHORT_HISTORY', 'CENSORED_DAYS',
        'OUTLIERS_CAPPED', 'SHORT_SHELF_LIFE', 'CAPPED_BY_UPPER', 'ZERO_DEMAND', 'MOQ_APPLIED',
        'LEAD_TIME_ZERO']::varchar[])
);

COMMENT ON TABLE replenishment_lines IS 'Sugerencia de compra por producto dentro de una corrida (C.2). Una linea por producto y corrida.';

-- -----------------------------------------------------------------------------
-- daily_product_sales  (agregado diario; lo escribe solo el modulo reporting)
-- -----------------------------------------------------------------------------
CREATE TABLE daily_product_sales (
    store_id      bigint        NOT NULL,
    product_id    bigint        NOT NULL,
    sale_date     date          NOT NULL,
    qty_sold      numeric(14,3) NOT NULL,   -- neto de devoluciones, en unidad base (packs consolidados)
    gross_amount  numeric(14,0) NOT NULL,
    net_amount    numeric(14,0) NOT NULL,
    cost_amount   numeric(16,2) NOT NULL,
    transactions  int           NOT NULL,
    opening_qty   numeric(14,3) NOT NULL,
    closing_qty   numeric(14,3) NOT NULL,
    -- had_stockout = opening_qty = 0 OR existe un movimiento del dia con balance_after = 0.
    had_stockout  boolean       NOT NULL,
    refreshed_at  timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_daily_product_sales PRIMARY KEY (store_id, product_id, sale_date),
    CONSTRAINT fk_daily_product_sales_store FOREIGN KEY (store_id) REFERENCES stores (id),
    CONSTRAINT fk_daily_product_sales_product FOREIGN KEY (product_id) REFERENCES products (id)
);

COMMENT ON TABLE daily_product_sales IS 'Ventas diarias por local y producto, base de la demanda del reabastecimiento. El job de las 02:00 recalcula los ultimos 3 dias.';
