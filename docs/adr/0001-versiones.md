# ADR 0001 - Versiones exactas del stack

- **Estado:** Propuesto (tarea T2, Fase 0)
- **Fecha de consulta de todas las fuentes:** 2026-11-12
- **Consumidores:** T3 (backend), T4 (frontend), T5 (Docker), T16 (pruebas de integración)
- **Referencias:** `docs/PLAN.md` ADR-01, 05, 07, 08, 21, 24 a 27, 32; D-02, D-03; riesgo R1.

## 1. Contexto

El plan fija el stack por familias (Spring Boot, Angular, PostgreSQL 17...) pero deja
las versiones "a verificar" en T2. Este ADR congela las versiones exactas contra
fuentes reales (Maven Central, npm, Docker Hub, endoflife.date) para que T3, T4 y T5
no las decidan por separado.

Hallazgos que cambian el punto de partida del plan:

1. Spring Boot 3.5 ya está **fuera de soporte OSS** (EOL 2026-06-30).
2. Spring Boot 4.0 sale de soporte OSS el **2026-12-31**, unas siete semanas después
   de esta fecha. Existe la línea **4.1** (GA 2026-06-30, EOL OSS 2027-07-31).
3. Angular actual (22.2.x) exige Node `^22.22.3 || ^24.15.0 || >=26`, y el Node local
   (22.20.0) **no cumple**.

## 2. Decisión: Spring Boot

**Se adopta Spring Boot 4.1.2** (misma generación mayor que D-03 "Boot 4", pero la
línea 4.1 en lugar de 4.0.x).

Evidencia:

| Línea | Última versión | EOL OSS | Soporte extendido (comercial) | Java soportado |
|-------|----------------|---------|-------------------------------|----------------|
| 3.5   | 3.5.16 (2026-06-25) | 2026-06-30 (ya vencido) | 2032-06-30 | 17-25 |
| 4.0   | 4.0.9 (2026-10-22)  | 2026-12-31 | 2027-12-31 | 17-25 |
| 4.1   | 4.1.2 (2026-10-22)  | 2027-07-31 | 2028-07-31 | 17-26 |

(Fuente: `https://endoflife.date/api/spring-boot.json`, respondió correctamente.)

Razones:

- 4.0.x dejaría el proyecto sin parches OSS a las pocas semanas de empezar; 4.1.x
  da ocho meses más y evita una migración obligada durante el desarrollo.
- No se encontró ninguna incompatibilidad bloqueante con Boot 4: todas las
  librerías sensibles a Spring/Jackson tienen una versión estable construida
  contra Boot 4.1.2 (springdoc 3.2.0 y Modulith 2.1.2 declaran como parent/dependencia
  Boot 4.1.2; logstash-logback-encoder 9.0 usa Jackson 3, `tools.jackson`).
- Se descarta 3.5.x: sin soporte OSS desde junio de 2026.
- JDK 21 es compatible (4.1 soporta 17-26), por lo que D-02 no cambia.

**Plan B** si una librería fallara en T3: bajar a **4.0.9** (springdoc 3.0.x/3.1.x y
Modulith 2.0.x son las líneas correspondientes) antes que a 3.5. Una caída a 3.5
exigiría Jackson 2, springdoc 2.x y Modulith 1.x, y no se recomienda.

**Nota:** D-03 decía "4.0.x"; esto es una desviación menor que el usuario debe
confirmar (ver sección 8).

## 3. Decisión: JDK

- `maven.compiler.release=21` (D-02). Boot 4.1 soporta 17-26, así que 21 LTS es válido.
- El JDK 22.0.2 local sirve para compilar con `release 21` mientras el usuario instala
  Temurin 21. Con `--release 21` el compilador limita la API a la de Java 21, así que
  no se cuela API de 22. Los plugins de Maven (en particular el de pruebas) se
  ejecutarán en JVM 22 localmente: aceptable para desarrollo, no para la CI.
- La CI y los contenedores usan Temurin 21 (`eclipse-temurin:21-jdk` para build y
  `eclipse-temurin:21-jre` para runtime).
- Maven no está instalado: se usa **Maven Wrapper**.

## 4. Tabla de versiones

Fecha de consulta: 2026-11-12 en todas las filas.

### 4.1 Backend (T3)

| Componente | Versión exacta | Fuente (URL) | Notas de compatibilidad |
|------------|----------------|--------------|-------------------------|
| Spring Boot (parent / BOM) | **4.1.2** | https://repo1.maven.org/maven2/org/springframework/boot/spring-boot/maven-metadata.xml (4.2.0-RC2 es pre-release y se ignora) | Spring Framework 7.0.x, Jackson 3, Hibernate 7.4, Tomcat 11. |
| Maven Wrapper (plugin) | **3.3.4** | https://repo1.maven.org/maven2/org/apache/maven/wrapper/maven-wrapper/maven-metadata.xml | Generar con `mvn wrapper:wrapper` o crear `maven-wrapper.properties` a mano. |
| Apache Maven (distribución del wrapper) | **3.9.16** | https://repo1.maven.org/maven2/org/apache/maven/apache-maven/maven-metadata.xml | Existe 3.10.0 (estable) y 4.0.0-rc-7. Se elige la última 3.9.x por ser la línea más probada con los plugins de Boot; 3.10.0 sería válida si T3 lo prefiere (no se probó). |
| springdoc-openapi-starter-webmvc-ui | **3.2.0** | https://repo1.maven.org/maven2/org/springdoc/springdoc-openapi-starter-webmvc-ui/maven-metadata.xml ; POM padre: https://repo1.maven.org/maven2/org/springdoc/springdoc-openapi/3.2.0/springdoc-openapi-3.2.0.pom | Su parent es `spring-boot-starter-parent` **4.1.2** (coincide exacto). La serie 3.x es la de Boot 4 / Jackson 3 (la 2.x es de Boot 3). Trae swagger-core 2.2.55 y swagger-ui 5.34.0. |
| Spring Modulith (BOM) | **2.1.2** | https://repo1.maven.org/maven2/org/springframework/modulith/spring-modulith-bom/maven-metadata.xml | Sus módulos dependen de `spring-boot-autoconfigure` 4.1.2 y Spring Framework 7. La serie 2.0.x corresponde a Boot 4.0 y la 2.1.x a Boot 4.1. 2.2.0-RC1 se ignora (pre-release). El BOM de Boot **no** gestiona Modulith: importar `spring-modulith-bom` en `dependencyManagement`. |
| MapStruct (`mapstruct` y `mapstruct-processor`) | **1.6.3** | https://repo1.maven.org/maven2/org/mapstruct/mapstruct/maven-metadata.xml | Última estable. 1.7.0.Beta2 es pre-release y se ignora. Declarar el procesador en `annotationProcessorPaths` de `maven-compiler-plugin`, junto con `lombok`/`lombok-mapstruct-binding` si se usa Lombok. No está en el BOM de Boot. |
| Testcontainers (BOM `org.testcontainers:testcontainers-bom`) | **2.0.5** (no fijar) | https://repo1.maven.org/maven2/org/testcontainers/testcontainers-bom/maven-metadata.xml | Boot 4.1.2 ya lo gestiona (`testcontainers.version` = 2.0.5). Existe línea 2.x: los artefactos se renombraron con prefijo `testcontainers-` (p. ej. `org.testcontainers:testcontainers-postgresql`, `testcontainers-junit-jupiter`) y la 1.x terminó en 1.21.4. Usar los artefactos 2.x. |
| Bucket4j | **8.22.0** (`com.bucket4j:bucket4j_jdk17-core`) | https://repo1.maven.org/maven2/com/bucket4j/bucket4j_jdk17-core/maven-metadata.xml | `bucket4j-core` quedó congelado en 8.10.1; el artefacto vigente es `bucket4j_jdk17-core` (requiere Java 17+). Sin dependencias de Spring ni Jackson: sin riesgo con Boot 4. No está en el BOM de Boot. |
| OpenHTMLtoPDF (`openhtmltopdf-pdfbox`) | **1.1.113** | https://repo1.maven.org/maven2/io/github/openhtmltopdf/openhtmltopdf-pdfbox/maven-metadata.xml | Depende solo de PDFBox 3.0.8 (no de Spring ni Jackson), por lo que Boot 4 no le afecta. Exige XHTML bien formado: la salida de Thymeleaf debe pasar por un parser XML (modo `XHTML`). |
| Apache POI (`poi-ooxml`) | **6.0.0** | https://repo1.maven.org/maven2/org/apache/poi/poi-ooxml/maven-metadata.xml | Rama 6.x (usa BouncyCastle `-jdk18on`). Sin dependencias de Spring. Si T3 detecta un problema con 6.0.0, la alternativa estable es 5.5.1. |
| Apache Commons CSV | **1.14.1** | https://repo1.maven.org/maven2/org/apache/commons/commons-csv/maven-metadata.xml | Sin dependencias relevantes. |
| ArchUnit (`archunit-junit5`) | **1.5.1** | https://repo1.maven.org/maven2/com/tngtech/archunit/archunit-junit5/maven-metadata.xml | Spring Modulith 2.1.2 trae `archunit` 1.4.2 transitivo. Alinear en `dependencyManagement` con `archunit` 1.5.1 para evitar dos versiones en el classpath (a validar en T3). |
| logstash-logback-encoder | **9.0** | https://repo1.maven.org/maven2/net/logstash/logback/logstash-logback-encoder/maven-metadata.xml ; POM: https://repo1.maven.org/maven2/net/logstash/logback/logstash-logback-encoder/9.0/logstash-logback-encoder-9.0.pom | Depende de Jackson 3 (`tools.jackson` 3.0.1), compatible con el Jackson 3.1.7 del BOM de Boot. Las versiones 7.x y 8.x usan Jackson 2 y no deben usarse. Compila contra Logback 1.5.20 (el BOM gestiona 1.5.38). |
| Flyway (`flyway-core` y `flyway-database-postgresql`) | **12.4.0 gestionada por el BOM** (no fijar) | BOM: https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.2/spring-boot-dependencies-4.1.2.pom | Maven Central ya publica 13.12.2, pero Boot 4.1.2 gestiona 12.4.0 y es la que valida su autoconfiguración. No sobrescribir `flyway.version`. Flyway 10+ exige el módulo `flyway-database-postgresql` además de `flyway-core`; ambos sin versión en el pom. |
| PostgreSQL JDBC | **42.7.14** gestionada por el BOM | BOM 4.1.2 | No fijar. |

### 4.2 Frontend (T4)

| Componente | Versión exacta | Fuente (URL) | Notas de compatibilidad |
|------------|----------------|--------------|-------------------------|
| `@angular/core` (y resto de paquetes `@angular/*`) | **22.2.6** | https://www.npmjs.com/package/@angular/core (`npm view @angular/core version`) | Engines: Node `^22.22.3 \|\| ^24.15.0 \|\| >=26.0.0`. **El Node local 22.20.0 NO cumple** (ver sección 7). Peer: rxjs `^6.5.3 \|\| ^7.4.0`, zone.js `~0.15 \|\| ~0.16` (no se necesita en zoneless). |
| `@angular/cli` / `@angular/build` | **22.2.7** | https://www.npmjs.com/package/@angular/cli | Mismos engines de Node. Se usa `npx @angular/cli@22.2.7 new` (sin instalación global). |
| `@angular/material` y `@angular/cdk` | **22.2.7** | https://www.npmjs.com/package/@angular/material | Peer: `@angular/core ^22 \|\| ^23`. Debe coincidir con CDK 22.2.7. |
| `ng-openapi-gen` | **1.1.0** | https://www.npmjs.com/package/ng-openapi-gen | Peer: `@angular/core >=16.0.0`; sin restricción de Node declarada. Compatibilidad real con Angular 22 a validar en T4 al generar el cliente. |
| `angular-eslint` | **22.6.0** | https://www.npmjs.com/package/angular-eslint | Peer: eslint `^9 \|\| ^10`, `typescript-eslint ^8`. |
| `prettier` | **3.10.1** | https://www.npmjs.com/package/prettier | Sin restricciones. |
| TypeScript | **6.0.x** (el que instale la CLI; NO usar 7.0.2) | `npm view @angular/build@22.2.6 peerDependencies.typescript` devuelve `>=6.0 <6.1` | `npm view typescript version` devuelve 7.0.2, pero Angular acepta solo `>=6.0 <6.1`. No instalar `typescript@latest`. |

### 4.3 Docker y herramientas (T5)

| Componente | Versión exacta | Fuente (URL) | Notas de compatibilidad |
|------------|----------------|--------------|-------------------------|
| PostgreSQL | **`postgres:17.12`** (alias `17`, `17-alpine`) | https://hub.docker.com/v2/repositories/library/postgres/tags?name=17 | Se fija el minor 17.12 en compose; Hibernate 7.4 y JDBC 42.7.14 son compatibles con PG 17. |
| Mailpit | **`axllent/mailpit:v1.33.1`** | https://hub.docker.com/v2/repositories/axllent/mailpit/tags | Último tag de versión; evitar `latest`. |
| Build backend | **`eclipse-temurin:21-jdk`** | https://hub.docker.com/v2/repositories/library/eclipse-temurin/tags?name=21-j | Existen `21-jdk` / `21-jre` y variantes `noble`, `jammy`, `ubi`. No hay tag alpine en la consulta; usar la variante Debian/Ubuntu (`21-jdk-noble`). |
| Runtime backend | **`eclipse-temurin:21-jre`** (preferible `21-jre-noble`) | idem | Solo JRE. |
| Build frontend | **`node:22.22-alpine`** | https://hub.docker.com/v2/repositories/library/node/tags?name=22-alpine | Los tags `22-alpine` y `22.22-alpine` existen. **Verificar en T5 que la imagen traiga Node >= 22.22.3** (`docker run --rm node:22-alpine node -v`); el daemon de Docker no estaba en ejecución durante esta consulta. Alternativa: `node:24-alpine` (24.22.x, cumple `^24.15`). |
| Servidor web | **`nginx:stable-alpine`** | https://hub.docker.com/v2/repositories/library/nginx/tags?name=stable-alpine | Se usa el tag móvil `stable-alpine` (hay también `stable-alpine3.24`); para reproducibilidad preferir `stable-alpine3.24`. |

## 5. Versiones que gestiona el BOM de Spring Boot 4.1.2 (no fijar en el pom)

Fuente: `https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.2/spring-boot-dependencies-4.1.2.pom`
(el BOM de 4.0.9 se anota entre paréntesis para el caso del plan B).

| Propiedad | 4.1.2 | (4.0.9) |
|-----------|-------|---------|
| `spring-framework.version` | 7.0.10 | (7.0.10) |
| `spring-security.version` | 7.1.2 | (7.0.8) |
| `spring-data-bom.version` | 2026.0.2 | (2025.1.8) |
| `hibernate.version` | 7.4.13.Final | (7.2.25.Final) |
| `hibernate-validator.version` | 9.1.5.Final | (9.0.2.Final) |
| `jackson-bom.version` (Jackson 3, `tools.jackson`) | 3.1.7 | (3.1.7) |
| `jackson-2-bom.version` (anotaciones y módulos aún en Jackson 2) | 2.21.7 | (2.21.7) |
| `flyway.version` | 12.4.0 | (11.14.1) |
| `testcontainers.version` | 2.0.5 | (2.0.5) |
| `postgresql.version` (JDBC) | 42.7.14 | (42.7.14) |
| `tomcat.version` | 11.0.27 | (11.0.27) |
| `logback.version` | 1.5.38 | (1.5.38) |
| `slf4j.version` | 2.0.20 | (2.0.20) |
| `micrometer.version` | 1.17.2 | (1.16.8) |
| `junit-jupiter.version` | 6.0.3 | (6.0.3) |
| `mockito.version` | 5.23.0 | (5.20.0) |
| `assertj.version` | 3.27.7 | (3.27.7) |
| `thymeleaf.version` | 3.1.5.RELEASE | (3.1.5.RELEASE) |
| `jakarta-validation.version` | 3.1.1 | (3.1.1) |

El BOM **no** gestiona: springdoc, Spring Modulith, MapStruct, Bucket4j, OpenHTMLtoPDF,
POI, Commons CSV, ArchUnit ni logstash-logback-encoder. Esas sí se declaran con
versión explícita (propiedades en el pom raíz); Modulith además se importa con su propio BOM.
Testcontainers no necesita importar su BOM aparte porque Boot ya lo gestiona.

## 6. Testcontainers y Docker 29

- Boot 4.1.2 gestiona Testcontainers **2.0.5**, que depende de docker-java **3.7.1**.
  Testcontainers 2.0.1 usaba docker-java 3.6.0; 2.0.2 subió a 3.7.0.
- Docker 29 eleva la versión mínima aceptada de la API de Docker (1.44), lo que rompía
  los clientes que negociaban una API más antigua. La hipótesis es que la línea
  Testcontainers >= 2.0.2 (docker-java >= 3.7.0) lo resuelve.
- **No se pudo confirmar**: la API de GitHub devolvió `rate limit exceeded` y el daemon
  de Docker local no estaba en ejecución (`docker version` falló al conectar con la
  pipe de Docker Desktop). Se deja como **riesgo a validar en T16** con
  Docker Desktop encendido, ejecutando una prueba mínima con `PostgreSQLContainer`.
  Mitigación documentada: si falla con "client version too old", fijar
  `api.version` en `~/.docker-java.properties`/`DOCKER_API_VERSION` a 1.44 o superior.

## 7. Riesgos residuales

| # | Riesgo | Impacto | Acción |
|---|--------|---------|--------|
| 1 | **Node 22.20.0 local incompatible con Angular 22.2.x** (exige >= 22.22.3; hoy existe 22.23.5 y LTS 24.22.x). | Alto: `ng` se negará a ejecutar o dará avisos de engine. | El usuario debe actualizar Node a 22.23.x (o 24 LTS) antes de T4. Alternativa: fijar Angular 21.2.26 (engines `^20.19 \|\| ^22.12 \|\| >=24`), pero el plan pide "última estable" (ADR-24). |
| 2 | Spring Boot 4.0 EOL OSS en 2026-12-31 y 4.1 EOL 2027-07-31; no hay LTS OSS. | Medio: habrá que subir de minor en 2027. | Mantener el pom con el BOM sin sobrescribir; planear migración 4.1 a 4.2. |
| 3 | Testcontainers y Docker 29 sin confirmar (sección 6). | Medio. | Validar en T16. |
| 4 | Doble versión de ArchUnit (Modulith trae 1.4.2). | Bajo. | Alinear con `dependencyManagement` en T3. |
| 5 | Flyway: Central tiene 13.x, el BOM gestiona 12.4.0. | Bajo. | Respetar el BOM. |
| 6 | `ng-openapi-gen` 1.1.0 no declara compatibilidad explícita con Angular 22; springdoc 3.x emite OpenAPI 3.1 por defecto. | Bajo a medio. | Validar en T4 (generación de cliente); si falla, configurar springdoc para 3.0. |
| 7 | OpenHTMLtoPDF exige XHTML estricto y su proyecto avanza despacio. | Bajo. | Plantillas Thymeleaf en modo XHTML; prueba de PDF en T3. |
| 8 | JDK local 22 vs destino 21 (plugins ejecutan en 22). | Bajo. | El usuario instala Temurin 21; la CI usa 21. |
| 9 | TypeScript 7.0.2 es el `latest` de npm, pero Angular 22 admite solo 6.0.x. | Medio si alguien ejecuta `npm i typescript@latest`. | Fijar `~6.0.x` en `package.json`. |

## 8. Pendientes de confirmación del usuario

1. Aceptar Spring Boot **4.1.2** en lugar de 4.0.x (D-03).
2. Actualizar Node local a >= 22.22.3 (ver riesgo 1).

## 9. Método de verificación

- Maven Central: `maven-metadata.xml` de cada artefacto (campo `<release>`; se ignoraron
  RC, milestones y betas).
- POM/BOM: `spring-boot-dependencies` 4.1.2 y 4.0.9, POM de springdoc 3.2.0, Modulith 2.1.2,
  Testcontainers 2.0.x, logstash-logback-encoder 9.0 y openhtmltopdf-pdfbox 1.1.113.
- Soporte: `https://endoflife.date/api/spring-boot.json` (respondió).
- npm: `npm view <paquete> version|engines|peerDependencies`.
- Docker Hub: `https://hub.docker.com/v2/repositories/...` (respondió). Docker local no se pudo
  consultar porque el daemon no estaba activo.
- Node: `https://nodejs.org/dist/index.json`.
- No verificado: soporte de Docker 29 en Testcontainers (límite de la API de GitHub); versión
  exacta de Node dentro de `node:22-alpine` (daemon Docker apagado).
