-- =============================================================================
-- T7 / Migracion 1: organizacion, configuracion, seguridad y auditoria
-- (MODELO_DATOS B.1 y B.2; erratas J incorporadas).
--
-- Se ejecuta como pos_owner (Flyway). Las extensiones pg_trgm y unaccent y los
-- default privileges de pos_app (DML + secuencias) los crea el init de Docker
-- (docker/postgres/init); aqui solo se REVOCAN los permisos de las dos tablas
-- restringidas (audit_log y stored_files), al final del archivo.
--
-- Convenciones (PLAN A.3): pk_, fk_<tabla>_<ref>, uq_<tabla>_<cols>,
-- ck_<tabla>_<regla>, ix_<tabla>_<cols>. Enums = varchar + CHECK (ADR-22).
-- FK por defecto ON DELETE RESTRICT (RN-33); [C] = CASCADE, [N] = SET NULL.
-- AUD = created_at, created_by, updated_at, updated_by, version (JPA @Version).
--
-- Orden de creacion: users primero (todas las AUD apuntan a users.created_by;
-- users.created_by apunta a si misma), luego stored_files (created_by -> users)
-- y company (logo_file_id -> stored_files).
--
-- FK pendientes hacia modulos posteriores (T14): ninguna. Las referencias
-- audit_log.store_id / terminal_id y idempotency_keys.resource_id son
-- deliberadamente SIN FK (B las declara sin flecha: el log y la idempotencia
-- deben sobrevivir y no depender de tablas de negocio).
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 0. f_unaccent(text): unaccent "inmutable" para poder usarla en indices
--    (p. ej. trigram sobre nombres de producto, T10) y en expresiones.
--    unaccent(text) es STABLE porque depende del diccionario de search_path;
--    fijando el diccionario de forma explicita y calificada con esquema la
--    funcion pasa a ser determinista y puede declararse IMMUTABLE.
-- -----------------------------------------------------------------------------
CREATE FUNCTION f_unaccent(text) RETURNS text
    LANGUAGE sql
    IMMUTABLE
    PARALLEL SAFE
    STRICT
AS $$ SELECT public.unaccent('public.unaccent'::regdictionary, $1) $$;

COMMENT ON FUNCTION f_unaccent(text) IS
    'unaccent con diccionario explicito (public.unaccent), declarada IMMUTABLE para usarla en indices y busquedas sin tildes.';

-- -----------------------------------------------------------------------------
-- B.2 (parte 1) users
-- -----------------------------------------------------------------------------
CREATE TABLE users (
    id                  bigint GENERATED ALWAYS AS IDENTITY,
    username            varchar(50)  NOT NULL,
    email               varchar(254),
    full_name           varchar(150) NOT NULL,
    rut                 varchar(12),
    password_hash       varchar(100) NOT NULL,
    pin_hash            varchar(100),
    pin_failed_attempts int          NOT NULL DEFAULT 0,
    -- Bloqueo del PIN independiente del de la clave (RN-31). pin_locked_until
    -- permite el auto-desbloqueo: vencido, el siguiente intento reinicia el contador.
    pin_locked          boolean      NOT NULL DEFAULT false,
    pin_locked_until    timestamptz,
    failed_attempts     int          NOT NULL DEFAULT 0,
    locked_until        timestamptz,
    must_change_password boolean     NOT NULL DEFAULT true,
    password_changed_at timestamptz  NOT NULL DEFAULT now(),
    last_login_at       timestamptz,
    active              boolean      NOT NULL DEFAULT true,
    created_at          timestamptz  NOT NULL DEFAULT now(),
    created_by          bigint,
    updated_at          timestamptz  NOT NULL DEFAULT now(),
    updated_by          bigint,
    version             bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT fk_users_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_users_updated_by FOREIGN KEY (updated_by) REFERENCES users (id),
    CONSTRAINT ck_users_attempts_non_negative CHECK (pin_failed_attempts >= 0 AND failed_attempts >= 0)
);
-- Unicidad insensible a mayusculas. El RUT valido (modulo 11) se verifica en servicio (RN-30).
CREATE UNIQUE INDEX uq_users_username_lower ON users (lower(username));
CREATE UNIQUE INDEX uq_users_email_lower ON users (lower(email)) WHERE email IS NOT NULL;
CREATE UNIQUE INDEX uq_users_rut ON users (rut) WHERE rut IS NOT NULL;

COMMENT ON TABLE users IS 'Usuarios del sistema (cajeros, bodegueros, supervisores, gerentes, administradores). Solo se desactivan, no se borran.';

-- -----------------------------------------------------------------------------
-- B.1 stored_files (se crea antes que company por company.logo_file_id)
-- -----------------------------------------------------------------------------
CREATE TABLE stored_files (
    id            bigint GENERATED ALWAYS AS IDENTITY,
    storage_key   varchar(200) NOT NULL,
    original_name varchar(255) NOT NULL,
    content_type  varchar(100) NOT NULL,
    size_bytes    bigint       NOT NULL,
    sha256        char(64)     NOT NULL,
    category      varchar(30)  NOT NULL,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    created_by    bigint,
    -- Unica excepcion al "sin soft delete": limpieza de huerfanos (solo IMPORT y
    -- EXPORT con mas de 30 dias, job de T11). Es la unica columna que pos_app
    -- puede actualizar (ver GRANT al final).
    deleted_at    timestamptz,
    CONSTRAINT pk_stored_files PRIMARY KEY (id),
    CONSTRAINT uq_stored_files_storage_key UNIQUE (storage_key),
    CONSTRAINT fk_stored_files_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT ck_stored_files_size_positive CHECK (size_bytes > 0),
    CONSTRAINT ck_stored_files_category CHECK (category IN
        ('PRODUCT_IMAGE', 'SHRINKAGE_EVIDENCE', 'COMPANY_LOGO', 'IMPORT', 'EXPORT'))
);

COMMENT ON TABLE stored_files IS 'Metadata de archivos almacenados (el binario vive en disco). Append-only; solo deleted_at es actualizable por pos_app y nunca se borran filas.';

-- -----------------------------------------------------------------------------
-- B.1 company
-- -----------------------------------------------------------------------------
CREATE TABLE company (
    id                bigint GENERATED ALWAYS AS IDENTITY,
    rut               varchar(12)  NOT NULL,
    legal_name        varchar(200) NOT NULL,
    trade_name        varchar(200) NOT NULL,
    business_activity varchar(200) NOT NULL,   -- giro
    address           varchar(250) NOT NULL,
    commune           varchar(100) NOT NULL,
    city              varchar(100) NOT NULL,
    phone             varchar(30),
    email             varchar(254),
    logo_file_id      bigint,
    timezone          varchar(40)  NOT NULL DEFAULT 'America/Santiago',
    currency          char(3)      NOT NULL DEFAULT 'CLP',
    created_at        timestamptz  NOT NULL DEFAULT now(),
    created_by        bigint,
    updated_at        timestamptz  NOT NULL DEFAULT now(),
    updated_by        bigint,
    version           bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_company PRIMARY KEY (id),
    CONSTRAINT fk_company_logo_file FOREIGN KEY (logo_file_id) REFERENCES stored_files (id) ON DELETE SET NULL,
    CONSTRAINT fk_company_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_company_updated_by FOREIGN KEY (updated_by) REFERENCES users (id)
);
-- Singleton: el indice unico sobre una expresion constante admite una sola fila.
CREATE UNIQUE INDEX uq_company_singleton ON company ((true));

COMMENT ON TABLE company IS 'Datos de la unica empresa (singleton). El RUT valido se verifica en servicio.';

-- -----------------------------------------------------------------------------
-- B.1 stores
-- -----------------------------------------------------------------------------
CREATE TABLE stores (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    company_id      bigint       NOT NULL,
    code            varchar(10)  NOT NULL,
    name            varchar(120) NOT NULL,
    address         varchar(250) NOT NULL,
    commune         varchar(100) NOT NULL,
    city            varchar(100) NOT NULL,
    phone           varchar(30),
    sii_branch_code varchar(20),
    active          boolean      NOT NULL DEFAULT true,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    created_by      bigint,
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    updated_by      bigint,
    version         bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_stores PRIMARY KEY (id),
    CONSTRAINT uq_stores_code UNIQUE (code),
    CONSTRAINT fk_stores_company FOREIGN KEY (company_id) REFERENCES company (id),
    CONSTRAINT fk_stores_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_stores_updated_by FOREIGN KEY (updated_by) REFERENCES users (id)
);
CREATE INDEX ix_stores_active ON stores (active);

COMMENT ON TABLE stores IS 'Locales (sucursales) de la empresa. Se desactivan, no se borran.';

-- -----------------------------------------------------------------------------
-- B.1 terminals
-- -----------------------------------------------------------------------------
CREATE TABLE terminals (
    id           bigint GENERATED ALWAYS AS IDENTITY,
    store_id     bigint       NOT NULL,
    code         varchar(20)  NOT NULL,
    name         varchar(80)  NOT NULL,
    active       boolean      NOT NULL DEFAULT true,
    last_seen_at timestamptz,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    created_by   bigint,
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    updated_by   bigint,
    version      bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_terminals PRIMARY KEY (id),
    CONSTRAINT uq_terminals_store_code UNIQUE (store_id, code),
    CONSTRAINT fk_terminals_store FOREIGN KEY (store_id) REFERENCES stores (id),
    CONSTRAINT fk_terminals_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_terminals_updated_by FOREIGN KEY (updated_by) REFERENCES users (id)
);

COMMENT ON TABLE terminals IS 'Cajas fisicas (terminales POS) de cada local.';

-- -----------------------------------------------------------------------------
-- B.1 settings
-- -----------------------------------------------------------------------------
CREATE TABLE settings (
    id          bigint GENERATED ALWAYS AS IDENTITY,
    scope       varchar(10)  NOT NULL,
    store_id    bigint,
    key         varchar(100) NOT NULL,
    value       jsonb        NOT NULL,
    value_type  varchar(10)  NOT NULL,
    description varchar(250),
    created_at  timestamptz  NOT NULL DEFAULT now(),
    created_by  bigint,
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    updated_by  bigint,
    version     bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_settings PRIMARY KEY (id),
    -- La fila GLOBAL no tiene local; la fila STORE siempre lo tiene.
    CONSTRAINT ck_settings_scope_store CHECK ((scope = 'GLOBAL') = (store_id IS NULL)),
    CONSTRAINT ck_settings_scope CHECK (scope IN ('GLOBAL', 'STORE')),
    CONSTRAINT ck_settings_value_type CHECK (value_type IN ('STRING', 'NUMBER', 'BOOLEAN', 'JSON')),
    -- ON DELETE CASCADE: al eliminar un local (solo posible si no tiene datos) se van sus overrides.
    CONSTRAINT fk_settings_store FOREIGN KEY (store_id) REFERENCES stores (id) ON DELETE CASCADE,
    CONSTRAINT fk_settings_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_settings_updated_by FOREIGN KEY (updated_by) REFERENCES users (id)
);
-- Un solo valor global por clave y un solo override por clave y local. Dos indices
-- parciales porque un UNIQUE normal no considera iguales los NULL.
CREATE UNIQUE INDEX uq_settings_key_global ON settings (key) WHERE store_id IS NULL;
CREATE UNIQUE INDEX uq_settings_key_store ON settings (key, store_id) WHERE store_id IS NOT NULL;

COMMENT ON TABLE settings IS 'Parametros clave-valor (jsonb) con alcance GLOBAL o por local (STORE). Claves, rangos y tipos: MODELO_DATOS B.1; se validan en servicio.';

-- -----------------------------------------------------------------------------
-- B.1 document_sequences
-- -----------------------------------------------------------------------------
CREATE TABLE document_sequences (
    id            bigint GENERATED ALWAYS AS IDENTITY,
    sequence_type varchar(30) NOT NULL,
    store_id      bigint,
    prefix        varchar(10) NOT NULL,
    next_value    bigint      NOT NULL DEFAULT 1,
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_document_sequences PRIMARY KEY (id),
    CONSTRAINT fk_document_sequences_store FOREIGN KEY (store_id) REFERENCES stores (id),
    CONSTRAINT ck_document_sequences_next_value CHECK (next_value >= 1),
    CONSTRAINT ck_document_sequences_type CHECK (sequence_type IN
        ('SALE', 'SALE_RETURN', 'PURCHASE_ORDER', 'GOODS_RECEIPT', 'TRANSFER', 'INVENTORY_COUNT',
         'STOCK_ADJUSTMENT', 'SHRINKAGE', 'CASH_SESSION', 'FOLIO_BOLETA', 'FOLIO_CREDIT_NOTE'))
);
-- store_id NULL = secuencia global (p. ej. folios tributarios, RN-29). COALESCE(...,0)
-- hace que NULL tambien sea unico por tipo (ningun local real tiene id 0).
CREATE UNIQUE INDEX uq_document_sequences_type_store ON document_sequences (sequence_type, COALESCE(store_id, 0));

COMMENT ON TABLE document_sequences IS 'Correlativos de documentos. Se leen con SELECT ... FOR UPDATE (sin huecos, RN-29); formato {prefix}-{next_value:08d}.';

-- -----------------------------------------------------------------------------
-- B.1 import_jobs
-- -----------------------------------------------------------------------------
CREATE TABLE import_jobs (
    id          bigint GENERATED ALWAYS AS IDENTITY,
    import_type varchar(30) NOT NULL,
    file_id     bigint      NOT NULL,
    status      varchar(20) NOT NULL,
    dry_run     boolean     NOT NULL DEFAULT true,
    total_rows  int         NOT NULL DEFAULT 0,
    ok_rows     int         NOT NULL DEFAULT 0,
    error_rows  int         NOT NULL DEFAULT 0,
    errors      jsonb,                       -- [{row, field, message}]
    finished_at timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    created_by  bigint,
    updated_at  timestamptz NOT NULL DEFAULT now(),
    updated_by  bigint,
    version     bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_import_jobs PRIMARY KEY (id),
    CONSTRAINT fk_import_jobs_file FOREIGN KEY (file_id) REFERENCES stored_files (id),
    CONSTRAINT fk_import_jobs_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_import_jobs_updated_by FOREIGN KEY (updated_by) REFERENCES users (id),
    CONSTRAINT ck_import_jobs_type CHECK (import_type IN ('PRODUCTS', 'PRODUCT_SUPPLIERS', 'INITIAL_STOCK')),
    CONSTRAINT ck_import_jobs_status CHECK (status IN ('PENDING', 'VALIDATED', 'PROCESSING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_import_jobs_rows CHECK (total_rows >= 0 AND ok_rows >= 0 AND error_rows >= 0)
);

COMMENT ON TABLE import_jobs IS 'Importaciones masivas (productos, proveedores, stock inicial) con su resultado de validacion (dry_run) y errores por fila.';

-- -----------------------------------------------------------------------------
-- B.1 outbound_emails
-- -----------------------------------------------------------------------------
CREATE TABLE outbound_emails (
    id                 bigint GENERATED ALWAYS AS IDENTITY,
    to_address         varchar(254) NOT NULL,
    subject            varchar(200) NOT NULL,
    template           varchar(60)  NOT NULL,
    payload            jsonb        NOT NULL,
    attachment_file_id bigint,
    status             varchar(10)  NOT NULL DEFAULT 'PENDING',
    attempts           int          NOT NULL DEFAULT 0,
    last_error         text,
    sent_at            timestamptz,
    created_at         timestamptz  NOT NULL DEFAULT now(),
    created_by         bigint,
    updated_at         timestamptz  NOT NULL DEFAULT now(),
    updated_by         bigint,
    version            bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_outbound_emails PRIMARY KEY (id),
    CONSTRAINT fk_outbound_emails_attachment FOREIGN KEY (attachment_file_id) REFERENCES stored_files (id),
    CONSTRAINT fk_outbound_emails_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_outbound_emails_updated_by FOREIGN KEY (updated_by) REFERENCES users (id),
    CONSTRAINT ck_outbound_emails_status CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    CONSTRAINT ck_outbound_emails_attempts CHECK (attempts >= 0)
);
-- El job de envio solo consulta los pendientes, en orden de creacion.
CREATE INDEX ix_outbound_emails_pending ON outbound_emails (status, created_at) WHERE status = 'PENDING';

COMMENT ON TABLE outbound_emails IS 'Outbox de correo: se inserta en la misma transaccion del negocio y un job lo envia con reintentos.';

-- -----------------------------------------------------------------------------
-- B.1 event_publication (Spring Modulith 2.1.2, modulo spring-modulith-events-jpa)
--
-- Esquema oficial: schemas/v2/schema-postgresql.sql del jar
-- spring-modulith-events-jdbc 2.1.2. El starter JPA NO trae un script SQL: su
-- entidad (JpaEventPublication, @Table EVENT_PUBLICATION) mapea exactamente estas
-- columnas (id uuid, listener_id, event_type = nombre de clase, serialized_event,
-- publication_date, completion_date, status @Enumerated(STRING),
-- completion_attempts, last_resubmission_date). Si se actualiza Modulith,
-- revisar el esquema del nuevo jar y agregar una migracion de cambio.
-- Con spring.jpa.hibernate.ddl-auto=none/validate el framework no crea la tabla.
-- Nota: la tabla event_publication_archive (solo para completion-mode=ARCHIVE)
-- no se crea; con el modo por defecto (UPDATE) no se usa.
-- -----------------------------------------------------------------------------
CREATE TABLE event_publication (
    id                     uuid                     NOT NULL,
    listener_id            text                     NOT NULL,
    event_type             text                     NOT NULL,
    serialized_event       text                     NOT NULL,
    publication_date       timestamp with time zone NOT NULL,
    completion_date        timestamp with time zone,
    status                 text,
    completion_attempts    int,
    last_resubmission_date timestamp with time zone,
    CONSTRAINT pk_event_publication PRIMARY KEY (id)
);
CREATE INDEX event_publication_serialized_event_hash_idx ON event_publication USING hash (serialized_event);
CREATE INDEX event_publication_by_completion_date_idx ON event_publication (completion_date);

COMMENT ON TABLE event_publication IS 'Tabla tecnica de Spring Modulith (registro de publicacion de eventos). La gestiona el framework; no se modifica desde el codigo de la aplicacion.';

-- -----------------------------------------------------------------------------
-- B.2 roles
-- -----------------------------------------------------------------------------
CREATE TABLE roles (
    id          bigint GENERATED ALWAYS AS IDENTITY,
    code        varchar(30)  NOT NULL,
    name        varchar(80)  NOT NULL,
    description varchar(250),
    -- Los roles base (system=true) no se borran, pero sus permisos si se editan.
    system      boolean      NOT NULL DEFAULT false,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    created_by  bigint,
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    updated_by  bigint,
    version     bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_roles PRIMARY KEY (id),
    CONSTRAINT uq_roles_code UNIQUE (code),
    CONSTRAINT fk_roles_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_roles_updated_by FOREIGN KEY (updated_by) REFERENCES users (id)
);

COMMENT ON TABLE roles IS 'Roles de usuario. Solo se borran los roles sin usuarios (DELETE /roles/{id}); los roles base (system) no.';

-- -----------------------------------------------------------------------------
-- B.2 permissions
-- -----------------------------------------------------------------------------
CREATE TABLE permissions (
    id          bigint GENERATED ALWAYS AS IDENTITY,
    code        varchar(60)  NOT NULL,
    module      varchar(30)  NOT NULL,
    description varchar(250) NOT NULL,
    sensitive   boolean      NOT NULL DEFAULT false,
    CONSTRAINT pk_permissions PRIMARY KEY (id),
    CONSTRAINT uq_permissions_code UNIQUE (code)
);

COMMENT ON TABLE permissions IS 'Catalogo de permisos, de solo lectura: se siembra por migracion (T15) y no se modifica en runtime.';

-- -----------------------------------------------------------------------------
-- B.2 role_permissions  (APP; se reescribe por reemplazo: DELETE + INSERT)
-- -----------------------------------------------------------------------------
CREATE TABLE role_permissions (
    role_id       bigint      NOT NULL,
    permission_id bigint      NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_role_permissions PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_role_permissions_role FOREIGN KEY (role_id) REFERENCES roles (id) ON DELETE CASCADE,
    CONSTRAINT fk_role_permissions_permission FOREIGN KEY (permission_id) REFERENCES permissions (id) ON DELETE CASCADE
);

COMMENT ON TABLE role_permissions IS 'Permisos de cada rol. Se reemplaza la coleccion completa (DELETE + INSERT en una transaccion).';

-- -----------------------------------------------------------------------------
-- B.2 user_roles  (APP; reemplazo de coleccion)
-- -----------------------------------------------------------------------------
CREATE TABLE user_roles (
    user_id    bigint      NOT NULL,
    role_id    bigint      NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_user_roles PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    -- RESTRICT: un rol con usuarios no se puede borrar.
    CONSTRAINT fk_user_roles_role FOREIGN KEY (role_id) REFERENCES roles (id)
);

COMMENT ON TABLE user_roles IS 'Roles de cada usuario. El rol es global al usuario; el alcance por local lo da user_stores.';

-- -----------------------------------------------------------------------------
-- B.2 user_stores  (actualizable: solo cambia is_default; J-07)
-- -----------------------------------------------------------------------------
CREATE TABLE user_stores (
    user_id    bigint      NOT NULL,
    store_id   bigint      NOT NULL,
    is_default boolean     NOT NULL DEFAULT false,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_user_stores PRIMARY KEY (user_id, store_id),
    CONSTRAINT fk_user_stores_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_stores_store FOREIGN KEY (store_id) REFERENCES stores (id)
);
-- Un usuario tiene como maximo un local por defecto.
CREATE UNIQUE INDEX uq_user_stores_default ON user_stores (user_id) WHERE is_default;

COMMENT ON TABLE user_stores IS 'Locales a los que tiene acceso cada usuario (RN-27) y su local por defecto.';

-- -----------------------------------------------------------------------------
-- B.2 role_limits  (fail-closed, D.3)
-- -----------------------------------------------------------------------------
CREATE TABLE role_limits (
    id         bigint GENERATED ALWAYS AS IDENTITY,
    role_id    bigint       NOT NULL,
    limit_code varchar(40)  NOT NULL,
    -- NULL = sin limite (infinito). Una fila AUSENTE equivale a 0 (deniega).
    value      numeric(14,4),
    created_at timestamptz  NOT NULL DEFAULT now(),
    created_by bigint,
    updated_at timestamptz  NOT NULL DEFAULT now(),
    updated_by bigint,
    version    bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_role_limits PRIMARY KEY (id),
    CONSTRAINT uq_role_limits_role_code UNIQUE (role_id, limit_code),
    CONSTRAINT fk_role_limits_role FOREIGN KEY (role_id) REFERENCES roles (id) ON DELETE CASCADE,
    CONSTRAINT fk_role_limits_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_role_limits_updated_by FOREIGN KEY (updated_by) REFERENCES users (id),
    CONSTRAINT ck_role_limits_value CHECK (value IS NULL OR value >= 0)
);

COMMENT ON TABLE role_limits IS 'Limites numericos por rol (descuentos, retiros, aprobaciones). Semantica fail-closed: fila ausente = 0; value NULL = sin limite.';

-- -----------------------------------------------------------------------------
-- B.2 refresh_tokens  (UPDATE parcial: revoked_*, replaced_by_id, terminal_id)
-- -----------------------------------------------------------------------------
CREATE TABLE refresh_tokens (
    id                  bigint GENERATED ALWAYS AS IDENTITY,
    user_id             bigint       NOT NULL,
    token_hash          char(64)     NOT NULL,   -- SHA-256 hex; el token en claro nunca se guarda
    -- Todos los tokens rotados de una sesion comparten family_id: reusar uno revoca la familia (RN-32).
    family_id           uuid         NOT NULL,
    terminal_id         bigint,
    issued_at           timestamptz  NOT NULL DEFAULT now(),
    expires_at          timestamptz  NOT NULL,   -- vencimiento deslizante
    absolute_expires_at timestamptz  NOT NULL,   -- tope absoluto de la sesion
    revoked_at          timestamptz,
    revoked_reason      varchar(30),
    replaced_by_id      bigint,
    ip                  inet,
    user_agent          varchar(300),
    CONSTRAINT pk_refresh_tokens PRIMARY KEY (id),
    CONSTRAINT uq_refresh_tokens_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_refresh_tokens_terminal FOREIGN KEY (terminal_id) REFERENCES terminals (id) ON DELETE SET NULL,
    CONSTRAINT fk_refresh_tokens_replaced_by FOREIGN KEY (replaced_by_id) REFERENCES refresh_tokens (id) ON DELETE SET NULL,
    CONSTRAINT ck_refresh_tokens_revoked_reason CHECK (revoked_reason IS NULL OR revoked_reason IN
        ('ROTATED', 'LOGOUT', 'REUSE_DETECTED', 'ADMIN', 'USER_DISABLED'))
);
CREATE INDEX ix_refresh_tokens_user_active ON refresh_tokens (user_id) WHERE revoked_at IS NULL;
CREATE INDEX ix_refresh_tokens_family ON refresh_tokens (family_id);
CREATE INDEX ix_refresh_tokens_expires_at ON refresh_tokens (expires_at);   -- purga

COMMENT ON TABLE refresh_tokens IS 'Refresh tokens con rotacion y deteccion de reuso por familia. Solo se guarda el hash. Se purgan al vencer o revocarse (job T11).';

-- -----------------------------------------------------------------------------
-- B.2 login_attempts  (APP; purga a los 90 dias)
-- -----------------------------------------------------------------------------
CREATE TABLE login_attempts (
    id             bigint GENERATED ALWAYS AS IDENTITY,
    username       varchar(50)  NOT NULL,   -- lo digitado, exista o no el usuario
    user_id        bigint,
    method         varchar(10)  NOT NULL,
    ip             inet,
    success        boolean      NOT NULL,
    failure_reason varchar(30),
    attempted_at   timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_login_attempts PRIMARY KEY (id),
    CONSTRAINT fk_login_attempts_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_login_attempts_method CHECK (method IN ('PIN', 'PASSWORD')),
    CONSTRAINT ck_login_attempts_failure_reason CHECK (failure_reason IS NULL OR failure_reason IN
        ('BAD_CREDENTIALS', 'LOCKED', 'DISABLED', 'PIN_LOCKED', 'RATE_LIMITED'))
);
CREATE INDEX ix_login_attempts_username_attempted ON login_attempts (lower(username), attempted_at DESC);
CREATE INDEX ix_login_attempts_ip_attempted ON login_attempts (ip, attempted_at DESC);

COMMENT ON TABLE login_attempts IS 'Intentos de inicio de sesion (exitosos y fallidos) para rate limit y deteccion de abuso. Se purga a los 90 dias.';

-- -----------------------------------------------------------------------------
-- B.2 supervisor_authorizations  (solo cambian consumed_at y entity_type/entity_id)
-- -----------------------------------------------------------------------------
CREATE TABLE supervisor_authorizations (
    id            bigint GENERATED ALWAYS AS IDENTITY,
    action        varchar(40)  NOT NULL,
    status        varchar(10)  NOT NULL,
    requested_by  bigint       NOT NULL,
    authorized_by bigint,
    store_id      bigint       NOT NULL,
    terminal_id   bigint,
    method        varchar(10)  NOT NULL,
    context       jsonb        NOT NULL,
    context_hash  char(64)     NOT NULL,   -- liga el token al contexto exacto (RN-26)
    token_hash    char(64),                -- token de un solo uso; NULL si fue DENIED
    expires_at    timestamptz,
    consumed_at   timestamptz,
    entity_type   varchar(40),             -- se completa al consumir el token
    entity_id     varchar(40),
    reason        varchar(250),
    created_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_supervisor_authorizations PRIMARY KEY (id),
    CONSTRAINT uq_supervisor_authorizations_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_supervisor_authorizations_requested_by FOREIGN KEY (requested_by) REFERENCES users (id),
    CONSTRAINT fk_supervisor_authorizations_authorized_by FOREIGN KEY (authorized_by) REFERENCES users (id),
    CONSTRAINT fk_supervisor_authorizations_store FOREIGN KEY (store_id) REFERENCES stores (id),
    CONSTRAINT fk_supervisor_authorizations_terminal FOREIGN KEY (terminal_id) REFERENCES terminals (id),
    -- Segregacion de funciones (RN-26): el supervisor no puede ser quien solicita.
    CONSTRAINT ck_supervisor_authorizations_sod CHECK (authorized_by IS NULL OR authorized_by <> requested_by),
    CONSTRAINT ck_supervisor_authorizations_status CHECK (status IN ('GRANTED', 'DENIED')),
    CONSTRAINT ck_supervisor_authorizations_method CHECK (method IN ('PIN', 'PASSWORD')),
    CONSTRAINT ck_supervisor_authorizations_action CHECK (action IN
        ('DISCOUNT_ITEM_OVER_LIMIT', 'DISCOUNT_TOTAL_OVER_LIMIT', 'SALE_VOID', 'SALE_RETURN',
         'CASH_OUT_OVER_LIMIT', 'CREDIT_OVER_LIMIT', 'SUSPENDED_SALE_DELETE', 'RECEIPT_REPRINT',
         'SHRINKAGE_APPROVAL', 'STOCK_ADJUSTMENT_APPROVAL', 'CASH_DIFFERENCE_APPROVAL'))
);
CREATE INDEX ix_supervisor_authorizations_store_created ON supervisor_authorizations (store_id, created_at DESC);
CREATE INDEX ix_supervisor_authorizations_authorized_by_created ON supervisor_authorizations (authorized_by, created_at DESC);

COMMENT ON TABLE supervisor_authorizations IS 'Overrides de supervisor (autorizaciones de un solo uso, 5 min, ligadas a accion y contexto). Solo se actualiza consumed_at y entity_type/entity_id al consumirlas.';

-- -----------------------------------------------------------------------------
-- B.2 audit_log  (APP inmutable, hash encadenado; ADR-18, RN-25)
-- -----------------------------------------------------------------------------
CREATE TABLE audit_log (
    id               bigint GENERATED ALWAYS AS IDENTITY,
    occurred_at      timestamptz  NOT NULL DEFAULT now(),
    user_id          bigint,
    username         varchar(50)  NOT NULL,   -- copia: el log debe ser legible aunque el usuario cambie
    store_id         bigint,                  -- sin FK a proposito (ver cabecera)
    terminal_id      bigint,                  -- sin FK a proposito (ver cabecera)
    ip               inet,
    action           varchar(60)  NOT NULL,
    entity_type      varchar(40)  NOT NULL,
    entity_id        varchar(40)  NOT NULL,
    old_value        jsonb,
    new_value        jsonb,
    reason           varchar(500),
    authorization_id bigint,
    trace_id         varchar(40),
    -- hash = sha256(prev_hash || payload canonico). prev_hash es NULL solo en la
    -- primera fila. El servicio serializa el calculo con
    -- pg_advisory_xact_lock(hashtext('audit_log')) para que la cadena no se bifurque.
    prev_hash        char(64),
    hash             char(64)     NOT NULL,
    CONSTRAINT pk_audit_log PRIMARY KEY (id),
    CONSTRAINT fk_audit_log_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_audit_log_authorization FOREIGN KEY (authorization_id) REFERENCES supervisor_authorizations (id)
);
CREATE INDEX ix_audit_log_entity ON audit_log (entity_type, entity_id);
CREATE INDEX ix_audit_log_action_occurred ON audit_log (action, occurred_at DESC);
CREATE INDEX ix_audit_log_user_occurred ON audit_log (user_id, occurred_at DESC);
CREATE INDEX ix_audit_log_store_occurred ON audit_log (store_id, occurred_at DESC);

-- Trigger de inmutabilidad (defensa en profundidad junto con el REVOKE del final).
-- Por fila y no por sentencia: un UPDATE/DELETE que no toca filas no es una
-- violacion. Rige tambien para pos_owner; una correccion exige deshabilitar el
-- trigger de forma consciente y auditada, nunca desde la aplicacion.
CREATE FUNCTION audit_log_reject_mutation() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'audit_log es inmutable: no se permite % sobre registros de auditoria', TG_OP
        USING ERRCODE = 'integrity_constraint_violation',
              HINT = 'La auditoria es append-only (ADR-18, RN-25).';
END;
$$;

CREATE TRIGGER trg_audit_log_immutable
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_reject_mutation();

COMMENT ON TABLE audit_log IS 'Registro de auditoria inmutable con hash SHA-256 encadenado. Solo INSERT/SELECT: trigger trg_audit_log_immutable y REVOKE a pos_app.';

-- -----------------------------------------------------------------------------
-- B.2 idempotency_keys  (UPDATE parcial; se purga por expires_at, ADR-14/31)
-- -----------------------------------------------------------------------------
CREATE TABLE idempotency_keys (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    idem_key        uuid         NOT NULL,
    user_id         bigint       NOT NULL,
    endpoint        varchar(100) NOT NULL,
    request_hash    char(64)     NOT NULL,
    status          varchar(15)  NOT NULL,
    response_status int,
    response_body   jsonb,
    resource_type   varchar(40),
    resource_id     bigint,                 -- sin FK: apunta a tablas de distintos modulos
    created_at      timestamptz  NOT NULL DEFAULT now(),
    expires_at      timestamptz  NOT NULL,
    CONSTRAINT pk_idempotency_keys PRIMARY KEY (id),
    CONSTRAINT uq_idempotency_keys_user_endpoint_key UNIQUE (user_id, endpoint, idem_key),
    CONSTRAINT fk_idempotency_keys_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_idempotency_keys_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED'))
);
CREATE INDEX ix_idempotency_keys_expires_at ON idempotency_keys (expires_at);

COMMENT ON TABLE idempotency_keys IS 'Claves de idempotencia (header Idempotency-Key) con la respuesta guardada para reintentos. Se purgan al vencer.';

-- -----------------------------------------------------------------------------
-- Permisos de pos_app (B "Notacion"). Los default privileges del init le dieron
-- SELECT/INSERT/UPDATE/DELETE sobre todas las tablas; aqui se restringen las dos
-- tablas APP estrictas. Va al final para que el REVOKE actue sobre tablas ya creadas.
-- -----------------------------------------------------------------------------
REVOKE UPDATE, DELETE ON audit_log FROM pos_app;
REVOKE UPDATE, DELETE ON stored_files FROM pos_app;
-- Unica columna actualizable de stored_files: la limpieza de huerfanos marca deleted_at.
GRANT UPDATE (deleted_at) ON stored_files TO pos_app;
