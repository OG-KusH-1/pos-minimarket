# Reglas para agentes de Antigravity — POS Minimarket

Este archivo lo leen los agentes de Google Antigravity que trabajan en este repositorio.
El proyecto lo coordina un orquestador (Claude Code). Tú ejecutas **tareas concretas** que
él te prepara en `docs/tareas/`. Sigue estas reglas sin excepción.

## 1. Antes de empezar

1. Abre el archivo de tu tarea en `docs/tareas/` (p. ej. `docs/tareas/T30.md`). Es tu fuente
   de verdad: ID, archivos permitidos, dependencias, criterio de terminado y contexto.
2. Comprueba que su `estado` sea `asignada`. Si dice otra cosa, no empieces.
3. Lee las secciones de diseño que la tarea cite: `docs/PLAN.md`, `docs/MODELO_DATOS.md`,
   `docs/API.md`, `docs/ROLES_PERMISOS.md`, `docs/HISTORIAS_USUARIO.md` y
   `docs/adr/0001-versiones.md`. Lee también la sección **L** de `docs/PLAN.md` (notas de
   implementación obligatorias).
4. Si algo de la tarea es ambiguo o contradice el diseño, **no inventes**: anótalo en la
   sección "Entrega" del archivo de la tarea como pregunta y detente.

## 2. Mientras trabajas

- **Toca solo los archivos y carpetas que la tarea lista en "Archivos permitidos".** Otras
  tareas pueden estar corriendo en paralelo sobre el resto del repositorio.
- No modifiques nunca: `docs/**` (salvo la sección "Entrega" de tu tarea), `CLAUDE.md`,
  `AGENTS.md`, `.claude/**`, `.github/**`, `backend/pom.xml`, `frontend/package.json`,
  `frontend/package-lock.json`, migraciones Flyway, ni los archivos generados
  `frontend/src/app/api/**` y `frontend/openapi/openapi.json`, **salvo que tu tarea los liste
  expresamente en "Archivos permitidos"**. Si necesitas una dependencia nueva o un cambio en
  esos archivos y tu tarea no los incluye, pídelo en la "Entrega".
- Tampoco modifiques `backend/src/main/java/cl/minimarket/pos/shared/**` ni
  `frontend/src/app/{core,shared}/**` si tu tarea no los lista: son base común. Si te falta
  algo ahí (p. ej. un `ErrorCode`), pídelo en la "Entrega".
- **Git:** no hagas `commit`, `push`, `merge`, `rebase`, `reset`, `checkout` de ramas ni
  `stash`. Solo comandos de lectura (`git status`, `git diff`). El orquestador integra.
- Nada de secretos ni credenciales reales en el código.
- Código e identificadores en inglés; textos de UI, comentarios y documentación en español.
- Comentarios detallados (Javadoc / TSDoc en español) en las funciones importantes,
  orientados a futuras mantenciones.

## 3. Convenciones del stack (resumen; el detalle está en `docs/PLAN.md` A.3–A.5)

- **Frontend:** Angular 22, componentes standalone, zoneless, signals, `OnPush`, Material 3,
  formularios reactivos tipados. Cada feature vive en su carpeta `frontend/src/app/features/<feature>/<sub>/`.
  Reutiliza lo que ya existe en `core/` (auth, interceptores, `StoreContextService`, `ApiError`,
  `withIdempotency()`, `withoutErrorNotification()`) y `shared/` (`PaginatedTable`,
  `ConfirmDialogService`, `SupervisorOverrideService.withSupervisorOverride`, pipes `clp`,
  `qty`, `rut`, `rutValidator`). Usa el cliente generado de `src/app/api` cuando exista.
- **Backend:** Spring Boot 4.1.2, Java 21, monolito modular (Spring Modulith). Respeta las
  dependencias entre módulos de `docs/PLAN.md` A.4. Montos con `BigDecimal`, nunca
  `double`/`float`. Errores con `ErrorCode` existente (catálogo en `docs/API.md` F.1).
  Pruebas de integración extendiendo `cl.minimarket.pos.support.AbstractIntegrationIT`.

## 4. Verificación obligatoria antes de entregar

- **Frontend:** `cd frontend && npm run format:check && npm run lint && npm test -- --watch=false && npm run build`
- **Backend:** `cd backend && ./mvnw -q -B verify`
  (en esta máquina Windows con JDK 22: `MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"`).

Todo debe quedar en verde. Si algo falla por código ajeno a tu tarea, repórtalo; no lo arregles.

## 5. Cómo entregar

Completa la sección **"Entrega"** al final del archivo de tu tarea con:

1. Estado: `completada`, `parcial` o `bloqueada`.
2. Archivos creados y modificados (rutas completas).
3. Resultado de cada comando de verificación (conteos de pruebas).
4. Decisiones tomadas y supuestos.
5. Preguntas o cosas que no pudiste resolver.

Luego cambia el `estado` del encabezado de la tarea a `entregada`. Un revisor auditará tu
trabajo; si pide cambios, el archivo volverá a `asignada` con los hallazgos agregados.

## 6. Modo autónomo (cuando el orquestador Claude no está disponible)

La persona usuaria puede seguir avanzando con Antigravity aunque Claude no esté disponible.
En ese modo:

1. **Orden.** Sigue el índice de `docs/tareas/README.md` por fase y oleada. Una tarea solo
   empieza cuando todas sus `depende_de` están `entregada` o `aprobada`. Las tareas de una
   misma oleada pueden ir en paralelo (un agente por tarea). Antes de empezar una tarea,
   cambia su `estado` de `pendiente` a `asignada`.
2. **Revisión por bloque.** Cuando una tarea dice que su oleada "cierra un bloque", al
   terminar todas las tareas de esa oleada lanza un agente revisor con
   `docs/tareas/_revision.md`. Escribe sus hallazgos en la sección "Revisión" de cada tarea.
   Corrige los críticos e importantes (máximo dos ciclos) antes de pasar a la oleada
   siguiente. El estado final en este modo es `entregada`: solo el orquestador marca `aprobada`.
3. **Verificación global.** Al cerrar cada oleada, el `verify` completo del backend y la
   verificación del frontend (§4) deben quedar en verde. Si no, no avances: arregla primero o
   detente y anótalo en la tarea.
4. **Cambios de base común.** Solo si una tarea no se puede completar sin ellos, puedes tocar
   `backend/.../shared/**`, `frontend/src/app/{core,shared}/**`, `application*.yml`,
   `backend/pom.xml` o `frontend/package.json`, con el **cambio mínimo**. Regístralo en
   `docs/tareas/CAMBIOS_BASE.md` (fecha, tarea, archivo, motivo, qué cambió). Nunca cambies
   versiones del stack ni desactives pruebas o reglas de arquitectura.
5. **Migraciones.** Nunca edites una migración existente. Si el esquema necesita un cambio,
   crea una nueva con versión `V2026120100<tarea con 4 dígitos><n>__<desc>.sql` (p. ej.
   `V2026120100481__lots_fefo.sql` para T48) y regístrala en `CAMBIOS_BASE.md`.
6. **Bitácora.** Actualiza la columna de estado de la tarea en el índice de
   `docs/tareas/README.md`. No edites `docs/ESTADO.md` ni el resto de `docs/`: lo
   consolida el orquestador al volver.
7. **Git.** Sigue sin commits ni pushes. La persona usuaria hace los commits si quiere
   puntos de restauración.
