-- Inicializacion de la BD de las pruebas de integracion (AbstractIntegrationIT, T16): replica lo
-- esencial de docker/postgres/init/01-roles-y-extensiones.sh (roles, esquema, extensiones y
-- default privileges) con contrasenas de prueba. Lo ejecuta Testcontainers como superusuario
-- antes de que Flyway (pos_owner) migre el esquema completo.
CREATE ROLE pos_owner LOGIN PASSWORD 'owner-test' NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE ROLE pos_app LOGIN PASSWORD 'app-test' NOSUPERUSER NOCREATEDB NOCREATEROLE;

ALTER DATABASE pos OWNER TO pos_owner;
ALTER SCHEMA public OWNER TO pos_owner;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO pos_app;

CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;
CREATE EXTENSION IF NOT EXISTS unaccent WITH SCHEMA public;

ALTER DEFAULT PRIVILEGES FOR ROLE pos_owner IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO pos_app;
ALTER DEFAULT PRIVILEGES FOR ROLE pos_owner IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO pos_app;
