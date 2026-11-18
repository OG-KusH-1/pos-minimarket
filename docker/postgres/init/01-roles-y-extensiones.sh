#!/bin/sh
# Inicializacion de PostgreSQL para POS Minimarket (ADR-18, MODELO_DATOS "Notacion").
#
# Se ejecuta UNA sola vez, al crear el volumen de datos, como superusuario y
# contra la base POSTGRES_DB (que la imagen ya creo). Para repetirlo hay que
# borrar el volumen (`docker compose down -v`).
#
# Contrasenas: llegan por variables de entorno del contenedor
# (POS_OWNER_PASSWORD, POS_APP_PASSWORD) y se pasan a psql con `-v`; el
# bloque SQL las usa con :'var', que las escapa como literal (sin inyeccion
# ni secretos en el codigo). El heredoc va entre comillas para que el shell
# no expanda nada dentro del SQL.
#
# Fuera de alcance: los REVOKE sobre audit_log y stored_files los hace la
# migracion de T7, porque esas tablas aun no existen aqui.
set -eu

: "${POS_OWNER_PASSWORD:?POS_OWNER_PASSWORD no definida}"
: "${POS_APP_PASSWORD:?POS_APP_PASSWORD no definida}"

psql -v ON_ERROR_STOP=1 \
     --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
     -v owner_pw="$POS_OWNER_PASSWORD" \
     -v app_pw="$POS_APP_PASSWORD" \
     -v dbname="$POSTGRES_DB" <<'EOSQL'

-- 0. Evita que las contrasenas de CREATE ROLE queden en el log del servidor
--    (solo afecta a esta sesion; requiere superusuario, que es quien corre el init).
SET log_statement = 'none';

-- 1. pos_owner: dueno del esquema, lo usa Flyway. Puede crear objetos, pero no
--    es superusuario ni puede crear roles/bases.
CREATE ROLE pos_owner LOGIN PASSWORD :'owner_pw'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;

-- 2. pos_app: usuario de runtime de la aplicacion. Login, sin privilegios
--    especiales; su acceso a datos se concede mas abajo (solo DML).
CREATE ROLE pos_app LOGIN PASSWORD :'app_pw'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;

-- 3. La base pasa a ser propiedad de pos_owner; solo los roles de la
--    aplicacion pueden conectarse (se retira el CONNECT por defecto de PUBLIC).
ALTER DATABASE :"dbname" OWNER TO pos_owner;
REVOKE CONNECT ON DATABASE :"dbname" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"dbname" TO pos_owner, pos_app;

-- 4. Esquema public propiedad de pos_owner. Se retira todo a PUBLIC (incluido
--    el CREATE heredado de versiones antiguas) y pos_app solo recibe USAGE:
--    puede usar objetos, jamas crearlos. pos_owner, como dueno, conserva todo.
ALTER SCHEMA public OWNER TO pos_owner;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO pos_app;

-- 5. Extensiones (las crea el superusuario; viven en public). pg_trgm para
--    busqueda por similitud y unaccent para busquedas sin tildes (f_unaccent, T7).
--    EXECUTE sobre sus funciones ya es de PUBLIC por defecto.
CREATE EXTENSION IF NOT EXISTS pg_trgm  WITH SCHEMA public;
CREATE EXTENSION IF NOT EXISTS unaccent WITH SCHEMA public;

-- 6. Privilegios por defecto: todo objeto que pos_owner (Flyway) cree en public
--    queda con DML completo para pos_app y uso de secuencias. Los REVOKE
--    puntuales (audit_log, stored_files) son responsabilidad de la migracion T7.
ALTER DEFAULT PRIVILEGES FOR ROLE pos_owner IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO pos_app;
ALTER DEFAULT PRIVILEGES FOR ROLE pos_owner IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO pos_app;
EOSQL
