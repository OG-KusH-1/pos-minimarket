-- =============================================================================
-- T10 / Migracion 2: catalogo, proveedores y compras, inventario y mermas
-- (MODELO_DATOS B.3, B.4, B.5 y B.6; erratas J y correcciones K incorporadas).
--
-- Se ejecuta como pos_owner (Flyway). Depende de T7 (users, stores, stored_files
-- y f_unaccent). Los permisos DML de pos_app los dan los default privileges del
-- init de Docker; ninguna de las tablas de este archivo necesita REVOKE (solo
-- audit_log y stored_files, en T7). Las tablas APP (price_history,
-- stock_movements, stock_adjustment_lines, product_taxes) son append-only "por
-- diseno del codigo" (B "Notacion").
--
-- Convenciones: las mismas de T7 (pk_, fk_<tabla>_<ref>, uq_<tabla>_<cols>,
-- ck_<tabla>_<regla>, ix_<tabla>_<cols>; enums = varchar + CHECK, ADR-22; FK
-- ON DELETE RESTRICT por defecto, RN-33). AUD = created_at, created_by,
-- updated_at, updated_by, version.
--
-- FK pendientes hacia tablas que aun no existen (se crean en T14, migracion de
-- B.7 a B.11). La columna existe, sin FK:
--   -- FK pendiente (T14): purchase_orders.replenishment_run_id -> replenishment_runs.id ON DELETE SET NULL
--   -- FK pendiente (T14): purchase_order_lines.replenishment_line_id -> replenishment_lines.id ON DELETE SET NULL
--   -- FK pendiente (T14): shrinkages.sale_return_line_id -> sale_return_lines.id
--
-- Referencia circular lots <-> goods_receipt_lines: ambas viven aqui, asi que
-- se resuelve en este archivo. goods_receipt_lines se crea sin la FK lot_id,
-- luego lots (con fk_lots_goods_receipt_line) y al final se agrega
-- fk_goods_receipt_lines_lot con ALTER TABLE. No queda nada pendiente para T14.
--
-- Orden de creacion: B.3 -> B.4 -> B.5 (+ ALTER circular) -> B.6.
-- =============================================================================

-- =============================================================================
-- B.3 CATALOGO
-- =============================================================================

-- -----------------------------------------------------------------------------
-- categories (dos niveles; "el padre no puede tener a su vez padre" es regla de servicio)
-- -----------------------------------------------------------------------------
CREATE TABLE categories (
    id         bigint GENERATED ALWAYS AS IDENTITY,
    parent_id  bigint,
    name       varchar(100) NOT NULL,
    code       varchar(20),
    active     boolean      NOT NULL DEFAULT true,
    created_at timestamptz  NOT NULL DEFAULT now(),
    created_by bigint,
    updated_at timestamptz  NOT NULL DEFAULT now(),
    updated_by bigint,
    version    bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_categories PRIMARY KEY (id),
    CONSTRAINT fk_categories_parent FOREIGN KEY (parent_id) REFERENCES categories (id),
    CONSTRAINT fk_categories_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_categories_updated_by FOREIGN KEY (updated_by) REFERENCES users (id)
);
-- Nombre unico por padre e insensible a mayusculas; COALESCE(...,0) hace que las
-- categorias raiz (parent_id NULL) tambien sean unicas entre si (ningun id es 0).
CREATE UNIQUE INDEX uq_categories_parent_name_lower ON categories (COALESCE(parent_id, 0), lower(name));

COMMENT ON TABLE categories IS 'Categorias de producto en dos niveles (categoria y subcategoria). Se desactivan, no se borran.';

-- -----------------------------------------------------------------------------
-- brands
-- -----------------------------------------------------------------------------
CREATE TABLE brands (
    id         bigint GENERATED ALWAYS AS IDENTITY,
    name       varchar(100) NOT NULL,
    active     boolean      NOT NULL DEFAULT true,
    created_at timestamptz  NOT NULL DEFAULT now(),
    created_by bigint,
    updated_at timestamptz  NOT NULL DEFAULT now(),
    updated_by bigint,
    version    bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_brands PRIMARY KEY (id),
    CONSTRAINT fk_brands_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_brands_updated_by FOREIGN KEY (updated_by) REFERENCES users (id)
);
CREATE UNIQUE INDEX uq_brands_name_lower ON brands (lower(name));

COMMENT ON TABLE brands IS 'Marcas de producto. Se desactivan, no se borran.';

-- -----------------------------------------------------------------------------
-- taxes (IVA e impuestos adicionales ILA/IABA; las tasas de semilla las carga T15)
-- -----------------------------------------------------------------------------
CREATE TABLE taxes (
    id         bigint GENERATED ALWAYS AS IDENTITY,
    code       varchar(30)  NOT NULL,   -- IVA, ILA_DESTILADOS, ILA_VINOS_CERVEZAS, ...
    name       varchar(100) NOT NULL,
    tax_type   varchar(15)  NOT NULL,
    rate       numeric(7,4) NOT NULL,   -- porcentaje, p. ej. 19.0000
    sii_code   varchar(10),
    valid_from date         NOT NULL,
    valid_to   date,
    active     boolean      NOT NULL DEFAULT true,
    created_at timestamptz  NOT NULL DEFAULT now(),
    created_by bigint,
    updated_at timestamptz  NOT NULL DEFAULT now(),
    updated_by bigint,
    version    bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_taxes PRIMARY KEY (id),
    CONSTRAINT uq_taxes_code UNIQUE (code),
    CONSTRAINT fk_taxes_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_taxes_updated_by FOREIGN KEY (updated_by) REFERENCES users (id),
    CONSTRAINT ck_taxes_rate CHECK (rate >= 0 AND rate <= 100),
    CONSTRAINT ck_taxes_tax_type CHECK (tax_type IN ('VAT', 'ADDITIONAL'))
);

COMMENT ON TABLE taxes IS 'Impuestos configurables (IVA y adicionales). Las tasas son datos, no codigo (ADR-30); se verifican contra la normativa vigente al sembrar (R9).';

-- -----------------------------------------------------------------------------
-- products
-- -----------------------------------------------------------------------------
CREATE TABLE products (
    id                    bigint GENERATED ALWAYS AS IDENTITY,
    sku                   varchar(40)    NOT NULL,
    name                  varchar(200)   NOT NULL,
    description           text,
    brand_id              bigint,
    category_id           bigint         NOT NULL,
    sale_type             varchar(10)    NOT NULL,
    unit_of_measure       varchar(5)     NOT NULL,
    -- Precio CON impuestos incluidos (ADR-30); en pesables es por kg. CLP sin decimales.
    price_gross           numeric(12,0)  NOT NULL DEFAULT 0,
    -- Costo promedio ponderado global (ADR-15). Solo lo cambian recepcion y carga inicial (RN-16).
    avg_cost              numeric(14,4)  NOT NULL DEFAULT 0,
    last_cost             numeric(14,4),
    vat_exempt            boolean        NOT NULL DEFAULT false,
    restricted_type       varchar(10),
    lot_controlled        boolean        NOT NULL DEFAULT false,
    shelf_life_days       int,
    image_file_id         bigint,
    shortcut_code         varchar(10),
    allow_manual_discount boolean        NOT NULL DEFAULT true,
    active                boolean        NOT NULL DEFAULT true,
    created_at            timestamptz    NOT NULL DEFAULT now(),
    created_by            bigint,
    updated_at            timestamptz    NOT NULL DEFAULT now(),
    updated_by            bigint,
    version               bigint         NOT NULL DEFAULT 0,
    CONSTRAINT pk_products PRIMARY KEY (id),
    CONSTRAINT uq_products_sku UNIQUE (sku),
    CONSTRAINT fk_products_brand FOREIGN KEY (brand_id) REFERENCES brands (id),
    CONSTRAINT fk_products_category FOREIGN KEY (category_id) REFERENCES categories (id),
    CONSTRAINT fk_products_image_file FOREIGN KEY (image_file_id) REFERENCES stored_files (id) ON DELETE SET NULL,
    CONSTRAINT fk_products_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_products_updated_by FOREIGN KEY (updated_by) REFERENCES users (id),
    CONSTRAINT ck_products_sale_type CHECK (sale_type IN ('UNIT', 'WEIGHT', 'PACK')),
    CONSTRAINT ck_products_unit_of_measure CHECK (unit_of_measure IN ('UNIT', 'KG')),
    CONSTRAINT ck_products_restricted_type CHECK (restricted_type IS NULL OR restricted_type IN ('ALCOHOL', 'TOBACCO', 'OTHER')),
    CONSTRAINT ck_products_price_non_negative CHECK (price_gross >= 0),
    CONSTRAINT ck_products_avg_cost_non_negative CHECK (avg_cost >= 0),
    -- Un producto es pesable si y solo si se mide en kg.
    CONSTRAINT ck_products_weight_uom CHECK ((sale_type = 'WEIGHT') = (unit_of_measure = 'KG')),
    -- RN-05: un PACK no tiene stock, por tanto tampoco lotes.
    CONSTRAINT ck_products_pack_no_lots CHECK (sale_type <> 'PACK' OR lot_controlled = false),
    CONSTRAINT ck_products_shelf_life CHECK (shelf_life_days IS NULL OR shelf_life_days > 0)
);
CREATE UNIQUE INDEX uq_products_shortcut_code ON products (shortcut_code) WHERE shortcut_code IS NOT NULL;
-- Busqueda por nombre sin tildes ni mayusculas con ILIKE '%texto%'. La consulta debe
-- usar exactamente la misma expresion: f_unaccent(lower(name)) (ver f_unaccent en T7).
CREATE INDEX ix_products_name_trgm ON products USING gin (f_unaccent(lower(name)) gin_trgm_ops);
CREATE INDEX ix_products_category ON products (category_id);
CREATE INDEX ix_products_brand ON products (brand_id);
CREATE INDEX ix_products_active ON products (active);

COMMENT ON TABLE products IS 'Catalogo de productos. Se desactivan, no se borran. vat_exempt y product_taxes solo cambian por PUT /products/{id}/taxes.';

-- -----------------------------------------------------------------------------
-- product_barcodes
-- -----------------------------------------------------------------------------
CREATE TABLE product_barcodes (
    id           bigint GENERATED ALWAYS AS IDENTITY,
    product_id   bigint      NOT NULL,
    code         varchar(20) NOT NULL,
    barcode_type varchar(10) NOT NULL,
    is_primary   boolean     NOT NULL DEFAULT false,
    created_at   timestamptz NOT NULL DEFAULT now(),
    created_by   bigint,
    updated_at   timestamptz NOT NULL DEFAULT now(),
    updated_by   bigint,
    version      bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_product_barcodes PRIMARY KEY (id),
    CONSTRAINT fk_product_barcodes_product FOREIGN KEY (product_id) REFERENCES products (id) ON DELETE CASCADE,
    CONSTRAINT fk_product_barcodes_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_product_barcodes_updated_by FOREIGN KEY (updated_by) REFERENCES users (id),
    CONSTRAINT ck_product_barcodes_type CHECK (barcode_type IN ('EAN13', 'EAN8', 'UPC_A', 'INTERNAL', 'SCALE_PLU'))
);
-- Los PLU de balanza viven en su propio espacio de codigos (pueden coincidir con un
-- codigo corto de otro tipo), por eso hay dos unicos parciales separados.
-- Digitos verificadores EAN13/EAN8 y la regla "SCALE_PLU solo en WEIGHT o balanza con
-- precio embebido" se validan en servicio (B.3).
CREATE UNIQUE INDEX uq_product_barcodes_code ON product_barcodes (code) WHERE barcode_type <> 'SCALE_PLU';
CREATE UNIQUE INDEX uq_product_barcodes_scale_plu ON product_barcodes (code) WHERE barcode_type = 'SCALE_PLU';
-- Un producto tiene como maximo un codigo principal.
CREATE UNIQUE INDEX uq_product_barcodes_primary ON product_barcodes (product_id) WHERE is_primary;

COMMENT ON TABLE product_barcodes IS 'Codigos de barras de un producto (EAN, UPC, internos y PLU de balanza). Se pueden borrar fisicamente via su endpoint.';

-- -----------------------------------------------------------------------------
-- product_packs  (PK = id del producto pack; ADR-17)
-- -----------------------------------------------------------------------------
CREATE TABLE product_packs (
    product_id      bigint        NOT NULL,   -- el pack
    base_product_id bigint        NOT NULL,
    factor          numeric(12,3) NOT NULL,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    created_by      bigint,
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    updated_by      bigint,
    version         bigint        NOT NULL DEFAULT 0,
    CONSTRAINT pk_product_packs PRIMARY KEY (product_id),
    CONSTRAINT fk_product_packs_product FOREIGN KEY (product_id) REFERENCES products (id) ON DELETE CASCADE,
    CONSTRAINT fk_product_packs_base_product FOREIGN KEY (base_product_id) REFERENCES products (id),
    CONSTRAINT fk_product_packs_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_product_packs_updated_by FOREIGN KEY (updated_by) REFERENCES users (id),
    -- Factor entero positivo: un pack contiene unidades completas del base.
    CONSTRAINT ck_product_packs_factor CHECK (factor > 0 AND factor = trunc(factor)),
    CONSTRAINT ck_product_packs_not_self CHECK (product_id <> base_product_id)
);
CREATE INDEX ix_product_packs_base_product ON product_packs (base_product_id);

COMMENT ON TABLE product_packs IS 'Definicion de packs: vender N packs descuenta N x factor del producto base (ADR-17). Que el base sea UNIT y no sea pack se valida en servicio.';

-- -----------------------------------------------------------------------------
-- product_taxes  (APP; reemplazo de coleccion: DELETE + INSERT en una transaccion)
-- -----------------------------------------------------------------------------
CREATE TABLE product_taxes (
    product_id bigint      NOT NULL,
    tax_id     bigint      NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_product_taxes PRIMARY KEY (product_id, tax_id),
    CONSTRAINT fk_product_taxes_product FOREIGN KEY (product_id) REFERENCES products (id) ON DELETE CASCADE,
    CONSTRAINT fk_product_taxes_tax FOREIGN KEY (tax_id) REFERENCES taxes (id)
);

COMMENT ON TABLE product_taxes IS 'Impuestos aplicables a cada producto. vat_exempt=true implica que el IVA no figura aqui (si pueden figurar los ADDITIONAL). Se reemplaza por coleccion completa.';

-- -----------------------------------------------------------------------------
-- price_history  (APP)
-- -----------------------------------------------------------------------------
CREATE TABLE price_history (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    product_id      bigint        NOT NULL,
    change_type     varchar(20)   NOT NULL,
    old_price_gross numeric(12,0),
    new_price_gross numeric(12,0),
    old_cost        numeric(14,4),
    new_cost        numeric(14,4),
    source_type     varchar(30),             -- sin CHECK: B.14 no lista valores
    source_id       bigint,                  -- sin FK: apunta a tablas de distintos modulos
    reason          varchar(250),
    changed_by      bigint,
    changed_at      timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_price_history PRIMARY KEY (id),
    CONSTRAINT fk_price_history_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT fk_price_history_changed_by FOREIGN KEY (changed_by) REFERENCES users (id),
    CONSTRAINT ck_price_history_change_type CHECK (change_type IN
        ('SALE_PRICE', 'COST_MANUAL', 'COST_RECEIPT', 'COST_INITIAL'))
);
CREATE INDEX ix_price_history_product_changed ON price_history (product_id, changed_at DESC);

COMMENT ON TABLE price_history IS 'Historial de cambios de precio de venta y de costo (RN-16, RN-35). Append-only.';

-- -----------------------------------------------------------------------------
-- scale_barcode_configs  (codigos de balanza con precio o peso embebido)
-- -----------------------------------------------------------------------------
CREATE TABLE scale_barcode_configs (
    id             bigint GENERATED ALWAYS AS IDENTITY,
    name           varchar(60)  NOT NULL,
    prefix         varchar(3)   NOT NULL,   -- p. ej. "20" a "29"
    embedded_type  varchar(10)  NOT NULL,
    plu_start      smallint     NOT NULL,
    plu_length     smallint     NOT NULL,
    value_start    smallint     NOT NULL,
    value_length   smallint     NOT NULL,
    value_decimals smallint     NOT NULL,   -- peso: 3; precio: 0
    active         boolean      NOT NULL DEFAULT true,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    created_by     bigint,
    updated_at     timestamptz  NOT NULL DEFAULT now(),
    updated_by     bigint,
    version        bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_scale_barcode_configs PRIMARY KEY (id),
    CONSTRAINT uq_scale_barcode_configs_prefix UNIQUE (prefix),
    CONSTRAINT fk_scale_barcode_configs_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_scale_barcode_configs_updated_by FOREIGN KEY (updated_by) REFERENCES users (id),
    CONSTRAINT ck_scale_barcode_configs_embedded_type CHECK (embedded_type IN ('PRICE', 'WEIGHT')),
    -- Un EAN-13 tiene 13 posiciones: PLU y valor deben caber dentro.
    CONSTRAINT ck_scale_barcode_configs_plu_range CHECK (plu_start + plu_length <= 13),
    CONSTRAINT ck_scale_barcode_configs_value_range CHECK (value_start + value_length <= 13)
);

COMMENT ON TABLE scale_barcode_configs IS 'Formato de los codigos EAN-13 de balanza (prefijo, posicion del PLU y del valor embebido). El digito verificador se valida en servicio.';

-- =============================================================================
-- B.4 PROVEEDORES Y COMPRAS
-- =============================================================================

-- -----------------------------------------------------------------------------
-- suppliers
-- -----------------------------------------------------------------------------
CREATE TABLE suppliers (
    id                 bigint GENERATED ALWAYS AS IDENTITY,
    rut                varchar(12)  NOT NULL,
    legal_name         varchar(200) NOT NULL,
    trade_name         varchar(200),
    business_activity  varchar(200),
    address            varchar(250),
    commune            varchar(100),
    city               varchar(100),
    phone              varchar(30),
    email              varchar(254),
    contact_name       varchar(150),
    contact_phone      varchar(30),
    contact_email      varchar(254),
    payment_terms_type varchar(20)  NOT NULL,
    payment_terms_days int          NOT NULL DEFAULT 0,
    lead_time_days     int          NOT NULL DEFAULT 1,
    -- Dias ISO de entrega (1 = lunes ... 7 = domingo).
    delivery_weekdays  smallint[]   NOT NULL DEFAULT '{}'::smallint[],
    notes              text,
    active             boolean      NOT NULL DEFAULT true,
    created_at         timestamptz  NOT NULL DEFAULT now(),
    created_by         bigint,
    updated_at         timestamptz  NOT NULL DEFAULT now(),
    updated_by         bigint,
    version            bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_suppliers PRIMARY KEY (id),
    CONSTRAINT uq_suppliers_rut UNIQUE (rut),
    CONSTRAINT fk_suppliers_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_suppliers_updated_by FOREIGN KEY (updated_by) REFERENCES users (id),
    CONSTRAINT ck_suppliers_payment_terms_type CHECK (payment_terms_type IN ('CASH_ON_DELIVERY', 'CREDIT')),
    CONSTRAINT ck_suppliers_lead_time CHECK (lead_time_days >= 0),
    CONSTRAINT ck_suppliers_payment_terms_days CHECK (payment_terms_days >= 0),
    CONSTRAINT ck_suppliers_delivery_weekdays CHECK (delivery_weekdays <@ '{1,2,3,4,5,6,7}'::smallint[])
);

COMMENT ON TABLE suppliers IS 'Proveedores. El RUT valido (modulo 11) se verifica en servicio (RN-30). Se desactivan, no se borran.';

-- -----------------------------------------------------------------------------
-- product_suppliers
-- -----------------------------------------------------------------------------
CREATE TABLE product_suppliers (
    id                       bigint GENERATED ALWAYS AS IDENTITY,
    product_id               bigint        NOT NULL,
    supplier_id              bigint        NOT NULL,
    supplier_sku             varchar(40),
    purchase_unit_name       varchar(30)   NOT NULL DEFAULT 'Unidad',
    units_per_purchase_unit  numeric(12,3) NOT NULL DEFAULT 1,
    cost_per_purchase_unit   numeric(14,4) NOT NULL,   -- neto
    min_order_qty            numeric(12,3) NOT NULL DEFAULT 1,   -- en unidades de compra
    lead_time_days_override  int,
    is_preferred             boolean       NOT NULL DEFAULT false,
    last_purchase_at         timestamptz,
    active                   boolean       NOT NULL DEFAULT true,
    created_at               timestamptz   NOT NULL DEFAULT now(),
    created_by               bigint,
    updated_at               timestamptz   NOT NULL DEFAULT now(),
    updated_by               bigint,
    version                  bigint        NOT NULL DEFAULT 0,
    CONSTRAINT pk_product_suppliers PRIMARY KEY (id),
    CONSTRAINT uq_product_suppliers_product_supplier UNIQUE (product_id, supplier_id),
    CONSTRAINT fk_product_suppliers_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT fk_product_suppliers_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers (id),
    CONSTRAINT fk_product_suppliers_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_product_suppliers_updated_by FOREIGN KEY (updated_by) REFERENCES users (id),
    CONSTRAINT ck_product_suppliers_units CHECK (units_per_purchase_unit > 0),
    CONSTRAINT ck_product_suppliers_cost CHECK (cost_per_purchase_unit >= 0),
    CONSTRAINT ck_product_suppliers_min_order CHECK (min_order_qty >= 1)
);
-- A lo sumo un proveedor preferido ACTIVO por producto.
CREATE UNIQUE INDEX uq_product_suppliers_preferred ON product_suppliers (product_id) WHERE is_preferred AND active;

COMMENT ON TABLE product_suppliers IS 'Proveedores de cada producto, con unidad de compra, costo neto y minimo de pedido. Que el producto no sea PACK se valida en servicio.';

-- -----------------------------------------------------------------------------
-- purchase_orders
-- -----------------------------------------------------------------------------
CREATE TABLE purchase_orders (
    id                    bigint GENERATED ALWAYS AS IDENTITY,
    number                varchar(24)   NOT NULL,
    store_id              bigint        NOT NULL,
    supplier_id           bigint        NOT NULL,
    status                varchar(20)   NOT NULL DEFAULT 'DRAFT',
    origin                varchar(15)   NOT NULL DEFAULT 'MANUAL',
    replenishment_run_id  bigint,       -- FK pendiente (T14): replenishment_runs.id ON DELETE SET NULL
    expected_date         date,
    sent_at               timestamptz,
    sent_by               bigint,
    cancelled_at          timestamptz,
    cancel_reason         varchar(250),
    notes                 text,
    -- net_total = SUM(line_net_total); tax_total = SUM(round(line_net_total x tasa IVA vigente / 100, 0));
    -- total = net_total + tax_total. Los calcula el servicio (no hay CK: la tasa vive en taxes).
    net_total             numeric(14,0) NOT NULL DEFAULT 0,
    tax_total             numeric(14,0) NOT NULL DEFAULT 0,
    total                 numeric(14,0) NOT NULL DEFAULT 0,
    created_at            timestamptz   NOT NULL DEFAULT now(),
    created_by            bigint,
    updated_at            timestamptz   NOT NULL DEFAULT now(),
    updated_by            bigint,
    version               bigint        NOT NULL DEFAULT 0,
    CONSTRAINT pk_purchase_orders PRIMARY KEY (id),
    CONSTRAINT uq_purchase_orders_number UNIQUE (number),
    CONSTRAINT fk_purchase_orders_store FOREIGN KEY (store_id) REFERENCES stores (id),
    CONSTRAINT fk_purchase_orders_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers (id),
    CONSTRAINT fk_purchase_orders_sent_by FOREIGN KEY (sent_by) REFERENCES users (id),
    CONSTRAINT fk_purchase_orders_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_purchase_orders_updated_by FOREIGN KEY (updated_by) REFERENCES users (id),
    CONSTRAINT ck_purchase_orders_status CHECK (status IN ('DRAFT', 'SENT', 'PARTIALLY_RECEIVED', 'RECEIVED', 'CANCELLED')),
    CONSTRAINT ck_purchase_orders_origin CHECK (origin IN ('MANUAL', 'REPLENISHMENT'))
);
CREATE INDEX ix_purchase_orders_store_status ON purchase_orders (store_id, status);
CREATE INDEX ix_purchase_orders_supplier_created ON purchase_orders (supplier_id, created_at DESC);

COMMENT ON TABLE purchase_orders IS 'Ordenes de compra. Solo se editan en DRAFT (RN-18). Totales calculados por el servicio.';

-- -----------------------------------------------------------------------------
-- purchase_order_lines
-- -----------------------------------------------------------------------------
CREATE TABLE purchase_order_lines (
    id                       bigint GENERATED ALWAYS AS IDENTITY,
    purchase_order_id        bigint        NOT NULL,
    product_id               bigint        NOT NULL,
    product_supplier_id      bigint,
    qty_purchase_units       numeric(12,3) NOT NULL,
    units_per_purchase_unit  numeric(12,3) NOT NULL,
    qty_ordered              numeric(14,3) NOT NULL,   -- en unidades base
    unit_cost_net            numeric(14,4) NOT NULL,   -- por unidad base
    qty_received             numeric(14,3) NOT NULL DEFAULT 0,
    line_net_total           numeric(14,0) NOT NULL,
    replenishment_line_id    bigint,       -- FK pendiente (T14): replenishment_lines.id ON DELETE SET NULL
    created_at               timestamptz   NOT NULL DEFAULT now(),
    created_by               bigint,
    updated_at               timestamptz   NOT NULL DEFAULT now(),
    updated_by               bigint,
    version                  bigint        NOT NULL DEFAULT 0,
    CONSTRAINT pk_purchase_order_lines PRIMARY KEY (id),
    CONSTRAINT uq_purchase_order_lines_order_product UNIQUE (purchase_order_id, product_id),
    CONSTRAINT fk_purchase_order_lines_order FOREIGN KEY (purchase_order_id) REFERENCES purchase_orders (id) ON DELETE CASCADE,
    CONSTRAINT fk_purchase_order_lines_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT fk_purchase_order_lines_product_supplier FOREIGN KEY (product_supplier_id) REFERENCES product_suppliers (id) ON DELETE SET NULL,
    CONSTRAINT fk_purchase_order_lines_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_purchase_order_lines_updated_by FOREIGN KEY (updated_by) REFERENCES users (id),
    CONSTRAINT ck_purchase_order_lines_qty_purchase CHECK (qty_purchase_units > 0),
    CONSTRAINT ck_purchase_order_lines_qty_received CHECK (qty_received >= 0),
    -- El producto de dos numeric(_,3) tiene hasta 6 decimales y qty_ordered solo 3: se compara
    -- contra el producto redondeado a 3 decimales (B.4).
    CONSTRAINT ck_purchase_order_lines_qty_ordered CHECK (qty_ordered = round(qty_purchase_units * units_per_purchase_unit, 3))
);

COMMENT ON TABLE purchase_order_lines IS 'Lineas de orden de compra (una por producto). Se borran fisicamente solo mientras la OC esta en DRAFT.';

-- -----------------------------------------------------------------------------
-- goods_receipts
-- -----------------------------------------------------------------------------
CREATE TABLE goods_receipts (
    id                       bigint GENERATED ALWAYS AS IDENTITY,
    number                   varchar(24)   NOT NULL,
    purchase_order_id        bigint,
    store_id                 bigint        NOT NULL,
    supplier_id              bigint        NOT NULL,
    status                   varchar(15)   NOT NULL DEFAULT 'DRAFT',
    supplier_document_type   varchar(20)   NOT NULL,
    supplier_document_number varchar(30),
    received_at              timestamptz,
    received_by              bigint,
    net_total                numeric(14,0) NOT NULL DEFAULT 0,
    notes                    text,
    created_at               timestamptz   NOT NULL DEFAULT now(),
    created_by               bigint,
    updated_at               timestamptz   NOT NULL DEFAULT now(),
    updated_by               bigint,
    version                  bigint        NOT NULL DEFAULT 0,
    CONSTRAINT pk_goods_receipts PRIMARY KEY (id),
    CONSTRAINT uq_goods_receipts_number UNIQUE (number),
    CONSTRAINT fk_goods_receipts_purchase_order FOREIGN KEY (purchase_order_id) REFERENCES purchase_orders (id),
    CONSTRAINT fk_goods_receipts_store FOREIGN KEY (store_id) REFERENCES stores (id),
    CONSTRAINT fk_goods_receipts_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers (id),
    CONSTRAINT fk_goods_receipts_received_by FOREIGN KEY (received_by) REFERENCES users (id),
    CONSTRAINT fk_goods_receipts_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_goods_receipts_updated_by FOREIGN KEY (updated_by) REFERENCES users (id),
    CONSTRAINT ck_goods_receipts_status CHECK (status IN ('DRAFT', 'CONFIRMED', 'CANCELLED')),
    CONSTRAINT ck_goods_receipts_document_type CHECK (supplier_document_type IN ('INVOICE', 'DISPATCH_GUIDE', 'NONE'))
);
CREATE INDEX ix_goods_receipts_store_received ON goods_receipts (store_id, received_at DESC);
CREATE INDEX ix_goods_receipts_purchase_order ON goods_receipts (purchase_order_id);

COMMENT ON TABLE goods_receipts IS 'Recepciones de mercaderia (con o sin orden de compra). Al confirmar actualizan stock, lotes y costo promedio.';

-- -----------------------------------------------------------------------------
-- goods_receipt_lines  (lot_id -> lots se agrega con ALTER mas abajo: referencia circular)
-- -----------------------------------------------------------------------------
CREATE TABLE goods_receipt_lines (
    id                     bigint GENERATED ALWAYS AS IDENTITY,
    goods_receipt_id       bigint        NOT NULL,
    purchase_order_line_id bigint,
    product_id             bigint        NOT NULL,
    qty_expected           numeric(14,3),
    qty_received           numeric(14,3) NOT NULL,
    qty_rejected           numeric(14,3) NOT NULL DEFAULT 0,
    difference_reason      varchar(250),
    unit_cost_net          numeric(14,4) NOT NULL,
    lot_number             varchar(40),
    expiry_date            date,
    lot_id                 bigint,       -- FK fk_goods_receipt_lines_lot: ver ALTER TABLE tras crear lots
    created_at             timestamptz   NOT NULL DEFAULT now(),
    created_by             bigint,
    updated_at             timestamptz   NOT NULL DEFAULT now(),
    updated_by             bigint,
    version                bigint        NOT NULL DEFAULT 0,
    CONSTRAINT pk_goods_receipt_lines PRIMARY KEY (id),
    CONSTRAINT fk_goods_receipt_lines_receipt FOREIGN KEY (goods_receipt_id) REFERENCES goods_receipts (id) ON DELETE CASCADE,
    CONSTRAINT fk_goods_receipt_lines_purchase_order_line FOREIGN KEY (purchase_order_line_id) REFERENCES purchase_order_lines (id),
    CONSTRAINT fk_goods_receipt_lines_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT fk_goods_receipt_lines_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_goods_receipt_lines_updated_by FOREIGN KEY (updated_by) REFERENCES users (id),
    CONSTRAINT ck_goods_receipt_lines_qty_received CHECK (qty_received >= 0),
    CONSTRAINT ck_goods_receipt_lines_qty_rejected CHECK (qty_rejected >= 0),
    -- Al confirmar entra al stock qty_received - qty_rejected (A.5).
    CONSTRAINT ck_goods_receipt_lines_rejected_le_received CHECK (qty_rejected <= qty_received)
);

COMMENT ON TABLE goods_receipt_lines IS 'Lineas de recepcion. Lo rechazado no entra al stock. Se borran fisicamente solo mientras la recepcion esta en DRAFT.';

-- =============================================================================
-- B.5 INVENTARIO
-- =============================================================================

-- -----------------------------------------------------------------------------
-- stock  (PK compuesta; sin @Version: se modifica solo por UPDATE condicional, ADR-13)
-- -----------------------------------------------------------------------------
CREATE TABLE stock (
    store_id         bigint        NOT NULL,
    product_id       bigint        NOT NULL,
    quantity         numeric(14,3) NOT NULL DEFAULT 0,
    min_qty          numeric(14,3),
    max_qty          numeric(14,3),
    last_movement_at timestamptz,
    updated_at       timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_stock PRIMARY KEY (store_id, product_id),
    CONSTRAINT fk_stock_store FOREIGN KEY (store_id) REFERENCES stores (id),
    CONSTRAINT fk_stock_product FOREIGN KEY (product_id) REFERENCES products (id),
    -- RN-01: el stock nunca es negativo.
    CONSTRAINT ck_stock_quantity_non_negative CHECK (quantity >= 0),
    CONSTRAINT ck_stock_min_qty CHECK (min_qty IS NULL OR min_qty >= 0),
    -- RN-34: min_qty <= max_qty cuando ambos existen.
    CONSTRAINT ck_stock_max_qty CHECK (max_qty IS NULL OR min_qty IS NULL OR max_qty >= min_qty)
);
CREATE INDEX ix_stock_product ON stock (product_id);
-- Alertas de stock bajo: solo las filas con minimo definido y cantidad en o bajo el.
CREATE INDEX ix_stock_low ON stock (store_id) WHERE min_qty IS NOT NULL AND quantity <= min_qty;

COMMENT ON TABLE stock IS 'Existencias por local y producto (fuente de verdad de la disponibilidad, ADR-16). Solo hay filas de productos no PACK; se crean al crear producto/local.';

-- -----------------------------------------------------------------------------
-- lots
-- -----------------------------------------------------------------------------
CREATE TABLE lots (
    id                    bigint GENERATED ALWAYS AS IDENTITY,
    store_id              bigint        NOT NULL,
    product_id            bigint        NOT NULL,
    lot_number            varchar(40)   NOT NULL,
    expiry_date           date,
    quantity              numeric(14,3) NOT NULL DEFAULT 0,
    unit_cost             numeric(14,4),
    received_at           timestamptz   NOT NULL,
    goods_receipt_line_id bigint,
    status                varchar(10)   NOT NULL DEFAULT 'ACTIVE',
    created_at            timestamptz   NOT NULL DEFAULT now(),
    created_by            bigint,
    updated_at            timestamptz   NOT NULL DEFAULT now(),
    updated_by            bigint,
    version               bigint        NOT NULL DEFAULT 0,
    CONSTRAINT pk_lots PRIMARY KEY (id),
    CONSTRAINT fk_lots_store FOREIGN KEY (store_id) REFERENCES stores (id),
    CONSTRAINT fk_lots_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT fk_lots_goods_receipt_line FOREIGN KEY (goods_receipt_line_id) REFERENCES goods_receipt_lines (id) ON DELETE SET NULL,
    CONSTRAINT fk_lots_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_lots_updated_by FOREIGN KEY (updated_by) REFERENCES users (id),
    CONSTRAINT ck_lots_quantity_non_negative CHECK (quantity >= 0),
    CONSTRAINT ck_lots_status CHECK (status IN ('ACTIVE', 'DEPLETED'))
);
-- Un lote se identifica por local, producto, numero y vencimiento; 'infinity' hace que
-- los lotes sin vencimiento tambien sean unicos por numero.
CREATE UNIQUE INDEX uq_lots_store_product_number_expiry
    ON lots (store_id, product_id, lot_number, COALESCE(expiry_date, 'infinity'::date));
-- FEFO (RN-03): lotes con existencia, ordenados por vencimiento (NULL al final) e id.
CREATE INDEX ix_lots_fefo ON lots (store_id, product_id, expiry_date NULLS LAST, id) WHERE quantity > 0;
-- Alertas de vencimiento.
CREATE INDEX ix_lots_expiry ON lots (expiry_date) WHERE quantity > 0;

COMMENT ON TABLE lots IS 'Lotes de productos con lot_controlled. Invariante RN-04: SUM(lots.quantity) = stock.quantity por local, validada por servicio y por test de consistencia.';

-- Cierre de la referencia circular goods_receipt_lines <-> lots.
ALTER TABLE goods_receipt_lines
    ADD CONSTRAINT fk_goods_receipt_lines_lot FOREIGN KEY (lot_id) REFERENCES lots (id);

-- -----------------------------------------------------------------------------
-- stock_movements  (kardex, APP). Futuro: particionar por mes.
-- -----------------------------------------------------------------------------
CREATE TABLE stock_movements (
    id             bigint GENERATED ALWAYS AS IDENTITY,
    store_id       bigint        NOT NULL,
    product_id     bigint        NOT NULL,
    lot_id         bigint,
    movement_type  varchar(25)   NOT NULL,
    quantity_delta numeric(14,3) NOT NULL,
    balance_after  numeric(14,3) NOT NULL,
    unit_cost      numeric(14,4) NOT NULL,
    source_type    varchar(30)   NOT NULL,
    source_id      bigint        NOT NULL,   -- sin FK: apunta a tablas de distintos modulos
    source_line_id bigint,                   -- sin FK, idem
    reason         varchar(250),
    user_id        bigint        NOT NULL,
    occurred_at    timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_stock_movements PRIMARY KEY (id),
    CONSTRAINT fk_stock_movements_store FOREIGN KEY (store_id) REFERENCES stores (id),
    CONSTRAINT fk_stock_movements_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT fk_stock_movements_lot FOREIGN KEY (lot_id) REFERENCES lots (id),
    CONSTRAINT fk_stock_movements_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_stock_movements_delta_non_zero CHECK (quantity_delta <> 0),
    CONSTRAINT ck_stock_movements_balance_non_negative CHECK (balance_after >= 0),
    CONSTRAINT ck_stock_movements_type CHECK (movement_type IN
        ('INITIAL', 'PURCHASE_RECEIPT', 'SALE', 'SALE_VOID', 'SALE_RETURN', 'SHRINKAGE',
         'ADJUSTMENT_IN', 'ADJUSTMENT_OUT', 'TRANSFER_OUT', 'TRANSFER_IN')),
    CONSTRAINT ck_stock_movements_source_type CHECK (source_type IN
        ('INITIAL_LOAD', 'GOODS_RECEIPT', 'SALE', 'SALE_RETURN', 'SHRINKAGE', 'STOCK_ADJUSTMENT',
         'INVENTORY_COUNT', 'STOCK_TRANSFER'))
);
CREATE INDEX ix_stock_movements_store_product_occurred ON stock_movements (store_id, product_id, occurred_at DESC);
CREATE INDEX ix_stock_movements_source ON stock_movements (source_type, source_id);
CREATE INDEX ix_stock_movements_type_occurred ON stock_movements (movement_type, occurred_at);

COMMENT ON TABLE stock_movements IS 'Kardex: un movimiento por cada variacion de stock, con saldo resultante (RN-02). Append-only.';

-- -----------------------------------------------------------------------------
-- stock_adjustments  (ajustes manuales fuera de una toma)
-- -----------------------------------------------------------------------------
CREATE TABLE stock_adjustments (
    id                bigint GENERATED ALWAYS AS IDENTITY,
    number            varchar(24)   NOT NULL,
    store_id          bigint        NOT NULL,
    reason_code       varchar(30)   NOT NULL,
    notes             varchar(500),
    status            varchar(20)   NOT NULL,
    total_cost_impact numeric(16,2) NOT NULL,
    requested_by      bigint        NOT NULL,
    approved_by       bigint,
    approved_at       timestamptz,
    rejection_reason  varchar(250),
    created_at        timestamptz   NOT NULL DEFAULT now(),
    created_by        bigint,
    updated_at        timestamptz   NOT NULL DEFAULT now(),
    updated_by        bigint,
    version           bigint        NOT NULL DEFAULT 0,
    CONSTRAINT pk_stock_adjustments PRIMARY KEY (id),
    CONSTRAINT uq_stock_adjustments_number UNIQUE (number),
    CONSTRAINT fk_stock_adjustments_store FOREIGN KEY (store_id) REFERENCES stores (id),
    CONSTRAINT fk_stock_adjustments_requested_by FOREIGN KEY (requested_by) REFERENCES users (id),
    CONSTRAINT fk_stock_adjustments_approved_by FOREIGN KEY (approved_by) REFERENCES users (id),
    CONSTRAINT fk_stock_adjustments_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_stock_adjustments_updated_by FOREIGN KEY (updated_by) REFERENCES users (id),
    CONSTRAINT ck_stock_adjustments_status CHECK (status IN ('PENDING_APPROVAL', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_stock_adjustments_reason CHECK (reason_code IN
        ('COUNT_CORRECTION', 'SYSTEM_ERROR', 'FOUND_GOODS', 'MISSING_GOODS', 'LOT_CORRECTION', 'OTHER')),
    -- RN-22: el aprobador es distinto del solicitante.
    CONSTRAINT ck_stock_adjustments_approver_differs CHECK (approved_by IS NULL OR approved_by <> requested_by)
);
CREATE INDEX ix_stock_adjustments_store_status ON stock_adjustments (store_id, status);

COMMENT ON TABLE stock_adjustments IS 'Ajustes manuales de stock con aprobacion (RN-22). El stock cambia al aprobarse.';

-- -----------------------------------------------------------------------------
-- stock_adjustment_lines  (APP: inmutables, se crean con la cabecera)
-- -----------------------------------------------------------------------------
CREATE TABLE stock_adjustment_lines (
    id                bigint GENERATED ALWAYS AS IDENTITY,
    adjustment_id     bigint        NOT NULL,
    product_id        bigint        NOT NULL,
    lot_id            bigint,
    quantity_delta    numeric(14,3) NOT NULL,
    unit_cost         numeric(14,4) NOT NULL,
    stock_movement_id bigint,
    created_at        timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_stock_adjustment_lines PRIMARY KEY (id),
    CONSTRAINT fk_stock_adjustment_lines_adjustment FOREIGN KEY (adjustment_id) REFERENCES stock_adjustments (id) ON DELETE CASCADE,
    CONSTRAINT fk_stock_adjustment_lines_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT fk_stock_adjustment_lines_lot FOREIGN KEY (lot_id) REFERENCES lots (id),
    CONSTRAINT fk_stock_adjustment_lines_stock_movement FOREIGN KEY (stock_movement_id) REFERENCES stock_movements (id),
    CONSTRAINT ck_stock_adjustment_lines_delta CHECK (quantity_delta <> 0)
);

COMMENT ON TABLE stock_adjustment_lines IS 'Lineas de un ajuste de stock. Se crean junto con la cabecera; stock_movement_id se completa al aprobar.';

-- -----------------------------------------------------------------------------
-- inventory_counts  (tomas de inventario)
-- -----------------------------------------------------------------------------
CREATE TABLE inventory_counts (
    id               bigint GENERATED ALWAYS AS IDENTITY,
    number           varchar(24)  NOT NULL,
    store_id         bigint       NOT NULL,
    count_type       varchar(10)  NOT NULL,
    category_id      bigint,
    status           varchar(15)  NOT NULL DEFAULT 'OPEN',
    blind            boolean      NOT NULL DEFAULT true,
    started_by       bigint       NOT NULL,
    submitted_by     bigint,
    submitted_at     timestamptz,
    approved_by      bigint,
    approved_at      timestamptz,
    rejection_reason varchar(250),
    notes            text,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    created_by       bigint,
    updated_at       timestamptz  NOT NULL DEFAULT now(),
    updated_by       bigint,
    version          bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_inventory_counts PRIMARY KEY (id),
    CONSTRAINT uq_inventory_counts_number UNIQUE (number),
    CONSTRAINT fk_inventory_counts_store FOREIGN KEY (store_id) REFERENCES stores (id),
    CONSTRAINT fk_inventory_counts_category FOREIGN KEY (category_id) REFERENCES categories (id),
    CONSTRAINT fk_inventory_counts_started_by FOREIGN KEY (started_by) REFERENCES users (id),
    CONSTRAINT fk_inventory_counts_submitted_by FOREIGN KEY (submitted_by) REFERENCES users (id),
    CONSTRAINT fk_inventory_counts_approved_by FOREIGN KEY (approved_by) REFERENCES users (id),
    CONSTRAINT fk_inventory_counts_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_inventory_counts_updated_by FOREIGN KEY (updated_by) REFERENCES users (id),
    CONSTRAINT ck_inventory_counts_type CHECK (count_type IN ('FULL', 'CYCLIC')),
    CONSTRAINT ck_inventory_counts_status CHECK (status IN ('OPEN', 'SUBMITTED', 'APPROVED', 'REJECTED', 'CANCELLED')),
    -- Una toma ciclica siempre tiene categoria; una completa nunca.
    CONSTRAINT ck_inventory_counts_cyclic_category CHECK ((count_type = 'CYCLIC') = (category_id IS NOT NULL))
);
-- A lo sumo una toma en curso (OPEN o SUBMITTED) por local y categoria; COALESCE(...,0)
-- cubre la toma completa (sin categoria).
CREATE UNIQUE INDEX uq_inventory_counts_open_scope ON inventory_counts (store_id, COALESCE(category_id, 0))
    WHERE status IN ('OPEN', 'SUBMITTED');

COMMENT ON TABLE inventory_counts IS 'Tomas de inventario (completas o ciclicas). Que quien aprueba no sea started_by ni counted_by (RN-20) se valida en servicio.';

-- -----------------------------------------------------------------------------
-- inventory_count_lines
-- -----------------------------------------------------------------------------
CREATE TABLE inventory_count_lines (
    id                bigint GENERATED ALWAYS AS IDENTITY,
    count_id          bigint        NOT NULL,
    product_id        bigint        NOT NULL,
    lot_id            bigint,
    theoretical_qty   numeric(14,3),   -- stock en el instante del conteo
    counted_qty       numeric(14,3),
    unit_cost         numeric(14,4),
    counted_by        bigint,
    counted_at        timestamptz,
    stock_movement_id bigint,
    created_at        timestamptz   NOT NULL DEFAULT now(),
    created_by        bigint,
    updated_at        timestamptz   NOT NULL DEFAULT now(),
    updated_by        bigint,
    version           bigint        NOT NULL DEFAULT 0,
    CONSTRAINT pk_inventory_count_lines PRIMARY KEY (id),
    CONSTRAINT fk_inventory_count_lines_count FOREIGN KEY (count_id) REFERENCES inventory_counts (id) ON DELETE CASCADE,
    CONSTRAINT fk_inventory_count_lines_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT fk_inventory_count_lines_lot FOREIGN KEY (lot_id) REFERENCES lots (id),
    CONSTRAINT fk_inventory_count_lines_counted_by FOREIGN KEY (counted_by) REFERENCES users (id),
    CONSTRAINT fk_inventory_count_lines_stock_movement FOREIGN KEY (stock_movement_id) REFERENCES stock_movements (id),
    CONSTRAINT fk_inventory_count_lines_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_inventory_count_lines_updated_by FOREIGN KEY (updated_by) REFERENCES users (id),
    CONSTRAINT ck_inventory_count_lines_counted_qty CHECK (counted_qty IS NULL OR counted_qty >= 0)
);
CREATE UNIQUE INDEX uq_inventory_count_lines_count_product_lot ON inventory_count_lines (count_id, product_id, COALESCE(lot_id, 0));

COMMENT ON TABLE inventory_count_lines IS 'Lineas de una toma. Al aprobar se aplica delta = counted_qty - theoretical_qty, de modo que las ventas ocurridas durante la toma se conservan.';

-- -----------------------------------------------------------------------------
-- stock_transfers
-- -----------------------------------------------------------------------------
CREATE TABLE stock_transfers (
    id                   bigint GENERATED ALWAYS AS IDENTITY,
    number               varchar(24)  NOT NULL,
    origin_store_id      bigint       NOT NULL,
    destination_store_id bigint       NOT NULL,
    status               varchar(15)  NOT NULL DEFAULT 'DRAFT',
    sent_at              timestamptz,
    sent_by              bigint,
    received_at          timestamptz,
    received_by          bigint,
    notes                text,
    created_at           timestamptz  NOT NULL DEFAULT now(),
    created_by           bigint,
    updated_at           timestamptz  NOT NULL DEFAULT now(),
    updated_by           bigint,
    version              bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_stock_transfers PRIMARY KEY (id),
    CONSTRAINT uq_stock_transfers_number UNIQUE (number),
    CONSTRAINT fk_stock_transfers_origin_store FOREIGN KEY (origin_store_id) REFERENCES stores (id),
    CONSTRAINT fk_stock_transfers_destination_store FOREIGN KEY (destination_store_id) REFERENCES stores (id),
    CONSTRAINT fk_stock_transfers_sent_by FOREIGN KEY (sent_by) REFERENCES users (id),
    CONSTRAINT fk_stock_transfers_received_by FOREIGN KEY (received_by) REFERENCES users (id),
    CONSTRAINT fk_stock_transfers_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_stock_transfers_updated_by FOREIGN KEY (updated_by) REFERENCES users (id),
    CONSTRAINT ck_stock_transfers_status CHECK (status IN ('DRAFT', 'SENT', 'RECEIVED', 'CANCELLED')),
    CONSTRAINT ck_stock_transfers_distinct_stores CHECK (origin_store_id <> destination_store_id)
);
CREATE INDEX ix_stock_transfers_destination_status ON stock_transfers (destination_store_id, status);
CREATE INDEX ix_stock_transfers_origin_status ON stock_transfers (origin_store_id, status);

COMMENT ON TABLE stock_transfers IS 'Transferencias entre locales (RN-19): descuenta en origen al enviar y suma en destino al recibir.';

-- -----------------------------------------------------------------------------
-- stock_transfer_lines
-- -----------------------------------------------------------------------------
CREATE TABLE stock_transfer_lines (
    id                bigint GENERATED ALWAYS AS IDENTITY,
    transfer_id       bigint        NOT NULL,
    product_id        bigint        NOT NULL,
    origin_lot_id     bigint,
    lot_number        varchar(40),
    expiry_date       date,
    qty_sent          numeric(14,3) NOT NULL,
    qty_received      numeric(14,3),
    difference_reason varchar(250),
    unit_cost         numeric(14,4) NOT NULL,
    created_at        timestamptz   NOT NULL DEFAULT now(),
    created_by        bigint,
    updated_at        timestamptz   NOT NULL DEFAULT now(),
    updated_by        bigint,
    version           bigint        NOT NULL DEFAULT 0,
    CONSTRAINT pk_stock_transfer_lines PRIMARY KEY (id),
    CONSTRAINT fk_stock_transfer_lines_transfer FOREIGN KEY (transfer_id) REFERENCES stock_transfers (id) ON DELETE CASCADE,
    CONSTRAINT fk_stock_transfer_lines_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT fk_stock_transfer_lines_origin_lot FOREIGN KEY (origin_lot_id) REFERENCES lots (id),
    CONSTRAINT fk_stock_transfer_lines_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_stock_transfer_lines_updated_by FOREIGN KEY (updated_by) REFERENCES users (id),
    CONSTRAINT ck_stock_transfer_lines_qty_sent CHECK (qty_sent > 0),
    CONSTRAINT ck_stock_transfer_lines_qty_received CHECK (qty_received IS NULL OR qty_received BETWEEN 0 AND qty_sent)
);

COMMENT ON TABLE stock_transfer_lines IS 'Lineas de transferencia. La diferencia enviado/recibido genera una merma TRANSFER_DIFFERENCE en el origen.';

-- =============================================================================
-- B.6 MERMAS
-- =============================================================================

-- -----------------------------------------------------------------------------
-- shrinkages
-- -----------------------------------------------------------------------------
CREATE TABLE shrinkages (
    id                  bigint GENERATED ALWAYS AS IDENTITY,
    number              varchar(24)   NOT NULL,
    store_id            bigint        NOT NULL,
    product_id          bigint        NOT NULL,
    lot_id              bigint,
    shrinkage_type      varchar(25)   NOT NULL,
    quantity            numeric(14,3) NOT NULL,
    unit_cost           numeric(14,4) NOT NULL,
    total_cost          numeric(16,2) NOT NULL,
    reason              varchar(500),
    evidence_file_id    bigint,
    responsible_user_id bigint        NOT NULL,
    reported_by         bigint        NOT NULL,
    status              varchar(20)   NOT NULL,
    approved_by         bigint,
    approved_at         timestamptz,
    rejection_reason    varchar(250),
    transfer_line_id    bigint,
    sale_return_line_id bigint,       -- FK pendiente (T14): sale_return_lines.id
    -- RN-21: la autoaprobacion deja approved_by en NULL y auto_approved=true (no viola
    -- el CK approved_by <> reported_by).
    auto_approved       boolean       NOT NULL DEFAULT false,
    -- false = merma sistemica (TRANSFER_DIFFERENCE o devolucion sin reingreso): nunca
    -- genera movimiento de stock, ni siquiera al aprobarse.
    affects_stock       boolean       NOT NULL DEFAULT true,
    stock_movement_id   bigint,
    occurred_at         timestamptz   NOT NULL,
    created_at          timestamptz   NOT NULL DEFAULT now(),
    created_by          bigint,
    updated_at          timestamptz   NOT NULL DEFAULT now(),
    updated_by          bigint,
    version             bigint        NOT NULL DEFAULT 0,
    CONSTRAINT pk_shrinkages PRIMARY KEY (id),
    CONSTRAINT uq_shrinkages_number UNIQUE (number),
    CONSTRAINT fk_shrinkages_store FOREIGN KEY (store_id) REFERENCES stores (id),
    CONSTRAINT fk_shrinkages_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT fk_shrinkages_lot FOREIGN KEY (lot_id) REFERENCES lots (id),
    CONSTRAINT fk_shrinkages_evidence_file FOREIGN KEY (evidence_file_id) REFERENCES stored_files (id) ON DELETE SET NULL,
    CONSTRAINT fk_shrinkages_responsible_user FOREIGN KEY (responsible_user_id) REFERENCES users (id),
    CONSTRAINT fk_shrinkages_reported_by FOREIGN KEY (reported_by) REFERENCES users (id),
    CONSTRAINT fk_shrinkages_approved_by FOREIGN KEY (approved_by) REFERENCES users (id),
    CONSTRAINT fk_shrinkages_transfer_line FOREIGN KEY (transfer_line_id) REFERENCES stock_transfer_lines (id),
    CONSTRAINT fk_shrinkages_stock_movement FOREIGN KEY (stock_movement_id) REFERENCES stock_movements (id),
    CONSTRAINT fk_shrinkages_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_shrinkages_updated_by FOREIGN KEY (updated_by) REFERENCES users (id),
    CONSTRAINT ck_shrinkages_type CHECK (shrinkage_type IN
        ('EXPIRED', 'DAMAGED', 'BROKEN', 'THEFT_LOSS', 'INTERNAL_CONSUMPTION', 'RECEIVING_ERROR', 'TRANSFER_DIFFERENCE')),
    CONSTRAINT ck_shrinkages_status CHECK (status IN ('PENDING_APPROVAL', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_shrinkages_quantity_positive CHECK (quantity > 0),
    CONSTRAINT ck_shrinkages_approver_differs CHECK (approved_by IS NULL OR approved_by <> reported_by),
    CONSTRAINT ck_shrinkages_transfer_difference_no_stock CHECK (shrinkage_type <> 'TRANSFER_DIFFERENCE' OR affects_stock = false),
    CONSTRAINT ck_shrinkages_approved_has_approver CHECK (status <> 'APPROVED' OR approved_by IS NOT NULL OR auto_approved),
    -- Una merma que no afecta stock solo existe como derivada de una transferencia o de una devolucion.
    CONSTRAINT ck_shrinkages_no_stock_has_origin CHECK (affects_stock OR transfer_line_id IS NOT NULL OR sale_return_line_id IS NOT NULL)
);
CREATE INDEX ix_shrinkages_store_occurred ON shrinkages (store_id, occurred_at);
CREATE INDEX ix_shrinkages_product_occurred ON shrinkages (product_id, occurred_at);
CREATE INDEX ix_shrinkages_type_occurred ON shrinkages (shrinkage_type, occurred_at);
CREATE INDEX ix_shrinkages_pending ON shrinkages (status) WHERE status = 'PENDING_APPROVAL';

COMMENT ON TABLE shrinkages IS 'Mermas (manuales y sistemicas). El stock se descuenta al aprobarse (RN-21), salvo affects_stock=false. Los reportes cuentan solo las APPROVED.';
