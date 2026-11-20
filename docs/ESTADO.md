# Estado del proyecto — POS Minimarket

> **Para retomar el trabajo** (nueva sesión, contexto compactado u otro agente): lee este
> archivo primero, luego `CLAUDE.md` y la sección del plan de la fase en curso
> (`docs/PLAN.md` H.3). Este archivo lo actualiza el orquestador al cerrar cada oleada.

**Última actualización:** 2026-11-19 · **Fase en curso:** 5 (ventas avanzadas), O3 (T72) en
ejecución · **Commits:** respaldo hasta Fase 4 (T63–T67); T68–T71 entregadas y pendientes de
respaldo.

## Orquestación automatizada (desde 2026-11-19, mandato de la persona usuaria)

El orquestador sustituto (OpenCode, GLM-5.3) mantiene la cadena sin intervención humana salvo
en tres casos: commits de respaldo (los hace la persona usuaria; los agentes no commitean),
decisiones de diseño no contempladas (se anotan como pregunta en la tarea afectada y la
oleada se detiene ahí) y fallos de verificación por código ajeno a la tarea en curso.

Reglas fijas de la automatización:

1. **Orden:** índice de `docs/tareas/README.md` por fase → oleada; una tarea solo se lanza
   cuando sus `depende_de` están `entregada` o `aprobada`. Las tareas de una misma oleada van
   en paralelo (sus archivos permitidos no se solapan).
2. **Por tarea:** un subagente implementador recibe un prompt con la tarea, las reglas de
   `AGENTS.md` (archivos permitidos, verificación §4, protocolo de entrega §5) y un briefing
   de contexto. Al terminar deja la tarea `entregada` con su sección "Entrega" completa y la
   columna de estado del índice actualizada.
3. **Cierre de bloque (oleadas 🔍):** subagente revisor con `docs/tareas/_revision.md`
   (verifica §4 completo, caza falsos verdes, contrasta con `docs/API.md`, sección L,
   `BigDecimal`, permisos, idempotencia). Si el veredicto es "Requiere cambios": un corrector
   fuerte arregla **solo críticos e importantes** (máx. 2 ciclos). Si aprueba: el orquestador
   marca `aprobada` en el índice, actualiza este archivo y sugiere el commit de respaldo.
4. **Modelos — economía con calidad (ajuste 2026-11-19 por uso semanal limitado):**
   implementación normal → **`glm-5.3-flash`** (US$0.15/0.50, pago; probado en este repo:
   T72 y T73 pasaron revisión/verificación); lógica especialmente difícil y rescates →
   `kimi-k2.7-code` (US$0.95/4); **revisor de bloque → `kimi-k2.7-code`** (familia
   distinta al implementador, más barato que `glm-5.3`); exploración/básicas → gratis. El
   orquestador economiza: respuestas breves y bitácora solo en hitos. Relevo por peldaños si
   un modelo falla (evitar los bloqueados por región: `deepseek*`).

   | Rol | 1º | 2º | 3º | 4º | Último recurso |
   |---|---|---|---|---|---|
   | Revisor de bloque | `glm-5.3` | `kimi-k2.7-code` | `kimi-k3.1-pro` | `qwen4-max` | **esperar reset del cubo** (nunca gratis) |
   | Implementador fuerte (flujos POS, lógica de negocio, rescates) | `glm-5.3` | `kimi-k2.7-code` | `kimi-k3.1-pro` | `qwen4-max` | **esperar reset del cubo** (nunca gratis) |
   | Implementador medio (CRUD, codegen de contrato) | `glm-5.3-flash` (crédito GLM, barato) | `glm-5.3` | `kimi-k2.7-code` | `minimax-m3` (probar región) | gratis solo si la tarea es básica |

   **Relevo por cubo de proveedor (desde 2026-11-19):** el plan pago de OpenCode Go tiene
   límite de 5 horas y el cubo **Personal/OpenCode está sin saldo** (requiere recarga de
   créditos en OpenCode Console). **Regla firme de la persona usuaria:** nada importante para
   el negocio en modelos gratis; los gratuitos de OpenCode Go (`hy4-flash-free`,
   `longcat-2.5-free`, `beam-free`) se usan SOLO para cosas básicas (exploración, codegen
   mecánico, papeleo de tareas). Si el cubo Go se seca, las tareas de negocio (venta, fiado,
   promociones, reabastecimiento, reportes, seguridad, sus revisiones) **esperan el reset
   (~5 h)** en vez de bajar a gratis; lo básico sigue avanzando en gratis mientras tanto. Al
   volver el cubo se hace verificación fuerte de lo hecho en la ventana seca.

   **Región de hospedaje (desde 2026-11-19):** algunos modelos del plan Go están hospedados
   fuera de Europa/EE. UU. y la consola los bloquea con el error "hosted outside Europe and
   the United States" (le pasó a `deepseek-v4-pro`). La persona usuaria puede habilitar
   **Global** en OpenCode Console → Settings → Privacy → Regions para desbloquearlos. Hasta
   saber si está habilitado, preferir los modelos ya probados en esta sesión
   (`kimi-k2.7-code`, `glm-5.3`/`glm-5.3-flash`, `kimi-k3.1-pro`, `hy4-flash-free` solo para
   básicas) y, ante el error de región, relanzar con el siguiente peldaño probado.

5. **Interrupciones:** si una sesión muere (cuota, cierre, compactación), el sucesor retoma
   desde este archivo + el índice; el trabajo en marcha queda en el árbol de trabajo y en la
   sección "Entrega" de la tarea. Nunca se relanza una tarea sin leer antes qué dejó la
   anterior. Los modelos gratuitos son el suelo de emergencia **solo de las tareas
   básicas**: el negocio nunca baja a gratis (espera el reset del cubo).
6. **Verificación global:** al cerrar cada oleada, `./mvnw verify` (backend) y la verificación
   §4 del frontend deben quedar en verde antes de avanzar de fase.

## Decisiones vigentes

| Tema | Decisión | Dónde |
|---|---|---|
| Plan | Aprobado con las recomendaciones por defecto (D-01 a D-10) | `docs/PLAN.md` I.2 |
| Spring Boot | 4.1.2 (no 4.0.x) | `docs/adr/0001-versiones.md` |
| Frontend | Angular 22.2, Node ≥ 22.22.3 (local: v26.13.1) | ídem |
| Repositorio | Git propio en `pos-minimarket`; el repo padre no se toca | — |
| Antigravity | Traspaso manual por archivos (sus términos prohíben invocarlo desde otro agente) | `docs/tareas/README.md`, `AGENTS.md` |
| Notas para Fase 1+ | Obligatorias (globales `@GlobalEndpoint`, `StoreAccessValidator`, idempotencia, etc.) | `docs/PLAN.md` L |
| Orquestación | 2026-11-19: OpenCode (GLM-5.3) orquesta y lanza subagentes por tarea; calidad de modelos por delante del coste, con relevo automático | §Orquestación automatizada arriba |

## Avance por fase

| Fase | Estado | Notas |
|---|---|---|
| 0 Cimientos | **Terminada y revisada** (T1–T16) | Backend: 201 unitarias + 68 IT. Frontend: 307 pruebas. CI y Docker Compose listos. |
| 1–4 (T17–T67) | **Terminadas** (todas `entregada` en el índice; las oleadas 🔍 pasaron su revisión autónoma para avanzar) | Respaldadas con commits hasta T67. |
| 5 Ventas avanzadas | **Terminada y revisada** (T68–T77) | Revisión O5: todas aprobadas con observaciones (0 críticos/0 importantes); ciclo 1 de T70 aprobado en Revisión 2. Verificación final: frontend 96 archivos/684 tests + format/lint/build; backend 354/253 BUILD SUCCESS. Pendiente de commit de respaldo (junto al avance de Fase 6). |
| 6 Reabastecimiento | **Última tanda** | **T78, T79, T80 y T81 entregadas/aprobadas** (contrato 8: 232 modelos/17 servicios, grupo replenishment con `lineIds`). **T82 frontend + fix de `ReportFilterTest` en paralelo** (npm vs Maven). Luego: revisión O4 → fase completa. |
| 7–8 | Pendientes | Ver "Cadena prevista". |

## Cadena prevista (automática, hasta agotar el plan)

- **Fase 5 (en curso):** T72 → revisión O3 → T73 (contrato 7) → T74 + T75 + T76 + T77 en
  paralelo → revisión O5 → cierre de fase (sugerir commit `chore(backup): respaldo Fase 5
  completa (T68-T77)`).
- **Fase 6 (reabastecimiento):** O1 T78 + T79 en paralelo → O2 🔍 T80 → revisión → O3 T81
  (contrato 8) → O4 🔍 T82 → revisión → cierre.
- **Fase 7 (dashboard y reportes):** O1 T83 + T84 + T85 en paralelo → O2 🔍 T86 → revisión →
  O3 T87 (contrato 9) → O4 🔍 T88 + T89 en paralelo → revisión → cierre.
- **Fase 8 (semilla, e2e, rendimiento y endurecimiento):** O1 T91 + T92 en paralelo → O2 T90
  + T93 en paralelo → O3 T94 → O4 🔍 T95 → revisión final → **proyecto terminado**.

## Pendientes y avisos

- **Commits de respaldo:** T68–T71 entregadas y sin commit. Al cerrar Fase 5, sugerir el
  respaldo; si se prefiere un punto de restauración antes, sirve
  `chore(backup): respaldo Fase 5 O1-O2 (T68-T71)`. Los agentes no commitean (regla del repo).
- **Docker Desktop:** instalación **por usuario** en `%LOCALAPPDATA%\Programs\DockerDesktop`
  (no está en Archivos de programa). Si Testcontainers falla o `docker info` se cuelga,
  arrancarlo con `Start-Process "$env:LOCALAPPDATA\Programs\DockerDesktop\Docker Desktop.exe"`
  y esperar al daemon; desactivar "Resource Saver" si se detiene solo. Requisito para todo
  `./mvnw verify`.
- **Maven con JDK 22 de Oracle:** `MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"`
  (PowerShell: `$env:MAVEN_OPTS="..."`). Instalar Temurin 21 lo evita.
- **`frontend/src/app/features/pos/sale/customer/` no existe** y T77 lo necesita (el resto de
  placeholders de Fase 5 sí existe): el implementador de T77 debe crear la carpeta y sus
  rutas (`*.routes.ts` incluido).
- **Placeholders con mojibake:** los componentes placeholder de promociones/clientes/
  devoluciones/documentos tienen `??` en comentarios; se corrige de paso al reemplazarlos.
- **T80 desbloqueada (2026-11-19):** `PurchaseOrderApi.createDrafts(...)` ya existe (`glm-5.3`,
  verify en verde, registrado en `CAMBIOS_BASE.md` como "preparación de T80"): T80 puede
  lanzarse cuando T78 esté entregada. Nota: `PurchaseOrderOrigin` se movió al paquete raíz de
  `purchasing` (precedente `catalog.SaleType`, `ModularityTest` en verde).
- **Fase 7 lista (briefings T83–T89 hechos):** T86 **NO necesita cambio de `pom.xml`** (Apache
  POI 6.0.0 y OpenHTMLtoPDF ya presentes; reutilizar `shared/export/PdfRenderer` +
  `XlsxWriter` y el patrón de `CatalogExportController`); los permisos `report:*` ya existen
  en `Permissions.java`. **Decisión de orquestación sobre L-02:** los endpoints
  `/reports/**` van **store-scoped** (exigen `X-Store-Id`, SIN `@GlobalEndpoint` ni
  `@RequiresTerminal`), coherente con `ReportFilter.storeIds` por defecto al local activo y
  con el frontend (`api-url.ts` solo exime a los patrones globales). Dashboard usa `date`
  (no rango); solo dashboard e inventory-valuation aceptan `from/to` opcionales. T83–T85
  dependen de T78 (Fase 6).
- **Consolidación de docs al cerrar Fase 5 (ejecutada 2026-11-19):** `PLAN.md` A.4 actualizado
  (promotions con el motor real y la excepción SQL ratificada; billing con `audit`;
  reporting con `refreshDailySales`) y `TAX_DOCUMENT_RETRIED` registrado en D.5 de
  `ROLES_PERMISOS.md`. Resta documentar en `API.md` F.2 el filtro `active` de
  `GET /customers` — diferido a T93 (docs finales y revisión OpenAPI; el contrato ya expone
  el parámetro y funciona).
- **Flake conocido (mantenimiento futuro):** `SaleReturnControllerIT` falla solo en la
  ventana 21:00–24:00 Chile (límite UTC vs `America/Santiago`); ajeno a Fase 6 y
  documentado por T80. Reintentar la verificación fuera de esa ventana; una corrección del
  fixture de fechas quedaría como mejora puntual de base común si molesta en CI nocturno.
- **Cambios de base común anticipados para Fase 8** (preparar la orquestación):
  (1) **T92** necesita `@playwright/test` + script `e2e` + `playwright.config.ts` en
  `frontend/package.json`/lock (T92 solo puede tocar `e2e/**`); (2) **T91**: decidir
  SQL-only vs `DemoDataGenerator` Java con runner `@Profile("demo")` (Flyway no ejecuta
  Java; `reporting` restringe dependencias — su paquete es `reporting/demo`, nuevo);
  (3) **T90**: carga con JUnit/`ExecutorService` para evitar tocar `pom.xml`; (4) **T95**:
  registrar en `CAMBIOS_BASE.md` los cambios de pom/`application-prod.yml`/nginx/`ci.yml`
  (ya listados en sus archivos permitidos; no hace falta servicio nginx nuevo);
  (5) **T93**: crear `docs/img/` y capturas con el stack demo; (6) repaso final de
  A05/A06 en `SEGURIDAD.md` tras T95. Nota: `/swagger-ui`→404 y CORS estricto en prod
  **ya están cubiertos** por la config actual (verificar, no reimplementar).
- **Nunca dos builds Maven simultáneos sobre el mismo `backend/`:** comparten `target/` y se
  corrompen. Antes de ejecutar `./mvnw verify`, comprobar que no haya otro proceso Maven vivo
  y esperar si lo hay (regla obligatoria para todos los agentes backend en paralelo).
- **T72 aprobada (2026-11-19):** revisión O3 "Aprobado con observaciones": 0 críticos, 0
  importantes, 5 menores (cosméticos/notas de diseño; detalle en la sección "Revisión" de
  `T72.md`). Veredicto del encargo especial: la serialización del pool en el perfil test NO
  enmascara defectos de producción (folios únicos y sin huecos garantizados por
  `SequenceService` con `FOR UPDATE`). **Pendiente del bloque:** revisión complementaria de
  las oleadas O1–O2 (T68–T71), cuyas secciones "Revisión" estaban vacías (en marcha).

## Bitácora

| Fecha | Hito |
|---|---|
| 2026-11-12 | Plan y diseño aprobados (`docs/`). |
| 2026-11-13 | Fase 0 terminada: esquema completo, semilla, kernel, infraestructura, esqueleto Angular, CI. |
| 2026-11-13 | Definido el traspaso de tareas a Antigravity. |
| 2026-11-13 | Antigravity pasa a implementar casi todo; O1 de Fase 1 (T17–T20) asignada. |
| 2026-11-13 | Escritas las 75 tareas restantes (T21–T95) y el modo autónomo para Antigravity. |
| 2026-11-13 | O1 entregada por Antigravity; revisión 1 pide cambios (falsa alarma IPv6 en audit, validación de emisor JWT, pruebas sobre BD compartida). |
| (sesiones intermedias) | Fases 1–4 completadas y respaldadas (T17–T67); Fase 5 O1–O2 (T68–T71) entregadas. |
| 2026-11-19 | Orquestación automatizada con OpenCode: T72 lanzada con subagente (`glm-5.3-flash`), Docker Desktop reactivado (instalación por usuario), definida la escalera de relevo de modelos y este archivo pasa a reflejar el estado real. |
| 2026-11-19 | Briefings de Fase 6 (T78–T82) preparados por explorador; T79 lanzada en paralelo a T72 (`kimi-k3.1-pro`). Detectado hueco de `PurchaseOrderApi.createDrafts` que bloquea T80 (lo resuelve la orquestación antes de lanzarla). |
| 2026-11-19 | Límite de 5 h de OpenCode Go agotado: T72 (en 2ª pasada de verify) y T79 interrumpidas. Relevo por cubo: finalizador de T72 → `claude-haiku-5-5`; T79 → `gpt-6.2-sol` (cubo Personal/OpenCode). Regla fija: lo básico en gratis/económico, lógica de negocio en modelo bueno. |
| 2026-11-19 | El cubo Personal/OpenCode resultó **sin saldo** (Insufficient balance): el relevo anterior murió al instante. Continuación con los gratuitos de OpenCode Go (`hy4-flash-free`): finalizador de T72 y relevo de T79. Pendiente: verificación fuerte de T79 cuando vuelva un modelo bueno (reset del cubo Go o recarga de créditos). |
| 2026-11-19 | 15:40 — Cubo Go liberado: finalizador T72 y relevo T79 lanzados en modelos del plan Go. `deepseek-v4-pro` resultó bloqueado por región de hospedaje (la consola exige "Global"): T72 relanzada en `kimi-k2.7-code` y escalera ajustada a modelos probados. Regla firme nueva: el negocio nunca en gratis; gratis solo para tareas básicas. |
| 2026-11-19 | Regla nueva de la persona usuaria: **GLM primero** — el plan Go trae US$500 de crédito en GLM; todo lo difícil en `glm-5.3`, lo medio en `glm-5.3-flash`. Los dos agentes en vuelo (finalizador T72 y T79) terminan en `kimi-k2.7-code` por estar ya avanzados; desde el siguiente lanzamiento (revisor O3) todo lo difícil sale en GLM. |
| 2026-11-19 | **T79 entregada** (`kimi-k2.7-code`): el código del relevo anterior ya estaba completo; 19 unitarias verdes; 1 fallo ajeno en `billing` (folio boleta39) documentado — lo resuelve el finalizador de T72. Lanzado agente de base común (`glm-5.3`) para `PurchaseOrderApi.createDrafts` (hueco que bloquea T80). |
| 2026-11-19 | Finalizador T72: las 2 pruebas arregladas (Mailpit: extracción defensiva; folio: **carrera real** entre listeners asíncronos de billing, resuelta serializando el pool en `application-test.yml`, registrado en `CAMBIOS_BASE.md`). Quedó `bloqueada` solo porque el agente paralelo de `purchasing` tenía código a medio escribir que no compilaba; avisado para que corrija el import antes de su verify. |
| 2026-11-19 | Cambio de base entregado: `PurchaseOrderApi.createDrafts` + `PurchaseOrderOrigin` al paquete raíz (sin migración: valores y FK ya existían); verify completo **BUILD SUCCESS 337+246**, con lo que T72 quedó desbloqueada y la orquestación la cerró como `entregada`. Revisor O3 lanzado (`glm-5.3`). |
| 2026-11-19 | Revisión O3: **Aprobado con observaciones** (0 críticos, 0 importantes, 5 menores); confirmado que el fix de folios no enmascara defectos de producción. T72 → `aprobada`. Lanzadas: revisión complementaria de T68–T71 (`glm-5.3`) y T73 contrato (`glm-5.3-flash`). T78 queda en espera hasta que el revisor complementario termine, para no contaminar su build. |
| 2026-11-19 | Briefings Fase 7 (T83–T89) listos con explorador gratis: **sin huecos de base común** (POI/OpenHTMLtoPDF ya en pom; permisos report:* existen). Decisión de orquestación: `/reports/**` store-scoped, sin anotaciones [G]/[T]; dashboard por `date`. T83–T85 esperan a T78. |
| 2026-11-19 | Revisión complementaria T68–T71: T68/T69/T71 **aprobadas con observaciones**; **T70 requiere cambios** (scope P neutralizado: `hasFullSaleReadScope` incluía `SALES_SALE_READ` y `findBySaleReturnId` sin alcance; falso verde con autoridades irreales). Corrector T70 lanzado (`glm-5.3`) — ciclo 1 de 2. Ratificaciones de orquestación: excepción SQL de solo lectura de `PromotionLookup` aceptada; filtro `active` de GET /customers se documenta; `TAX_DOCUMENT_RETRIED` y dependencia `audit` de billing van a la lista de consolidación. |
| 2026-11-19 | T73 **entregada**: api:gen 219 models/16 services; grupos promotions/customers/sale-returns/billing + email materializados; 43 imports verificados por (método, PATH); frontend 604 tests verde. Lanzadas las 4 tareas de O5 en paralelo con regla anti-colisión de npm; el índice del README lo actualiza solo la orquestación (evita escrituras concurrentes). |
| 2026-11-19 | Corrector T70 (ciclo 1) **entregado**: scope P restaurado (sin `SALES_SALE_READ` en `hasFullSaleReadScope`), falso verde corregido con set CAJ realista, `findBySaleReturnId` con propiedad vía `referenced_document_id` (sin tocar `sales`), IT nuevo de devolución ajena → 404. Verify **337/247 verde**. Decisión de orquestación: aceptada la limitación fail-safe (NC sin boleta persistida → 404); extender `SaleLookupApi.cashierOfReturn` queda como mejora opcional futura. **T78 lanzada** (`glm-5.3`); la re-revisión de T70 se pliega a la revisión O5. |
| 2026-11-19 | **T77 entregada** (20 pruebas propias en verde ×3 corridas; 204 cubierto; selector con fiado). Reportó 4 huecos de cableado en `pos/sale/**` (archivos sin dueño): lanzado **agente de cableado T77-bis** (`glm-5.3`, autorización de orquestación) para selector en venta → `PosCartStore.setCustomerId()`, pago `CUSTOMER_CREDIT`, correo desde ticket y folio/timbre. Fallos ajenos pendientes: format de T74 y `<h1>` de T76 (dueños avisados). |
| 2026-11-19 | **T75 entregada**: pantalla de clientes completa (RUT módulo 11, cartola paginada server-side, abono con tope de deuda y `PAYMENT_EXCEEDS_DEBT` manejado, ajuste con override, config de fiado); 13 pruebas propias; suite **675/675 verde** (el fix de `<h1>` de T76 ya surtió efecto). Observaciones para la revisión O5: el backend de `/adjustments` responde `CREDIT_LIMIT_EXCEEDED` como límite duro sin `OVERRIDE_REQUIRED` (wrapper override queda passthrough) y `CustomerResponse` no expone `notes`. |
| 2026-11-19 | **T76 entregada**: anulación/devoluciones con tope real por línea (cruce con `SaleResponse.lines` por `saleLineId`), `restock` por fila + conmutador global, override `O:SALE_VOID` con idempotencia en el reintento, `<h1>` «Devoluciones» conforme al spec global; 18 pruebas propias; suite 675/675 ×2 corridas. Warning de presupuesto scss (5,94 kB) igual a precedentes ya entregados; `format:check` solo falla por archivo ajeno de T74. |
| 2026-11-19 | **T78 entregada**: job 02:00 con ventana [d−3, d−1] + barrido por devoluciones, `had_stockout` literal de B.11, `dailyDemand` con bloques de 1.000, `ReportFilter` con `requireRange`/`optionalRange` (≤366, `VALIDATION_ERROR`); +17 unit y +6 IT (354/253). Prerrequisito de T80 detectado: exponer `SalesHistoryApi.refreshDailySales(from,to)` (actualizando A.4) — lo hace la orquestación tras la revisión O5. |
| 2026-11-19 | **FASE 5 CERRADA**: revisión O5 aprobó T74–T77 y el ciclo 1 de T70 (0 críticos/0 importantes en todo el bloque). Verificación completa en verde (frontend 684/684 + format/lint/build; backend 354/253). T74–T77 y T70 → `aprobada`. Lanzada la base común `SalesHistoryApi.refreshDailySales` (flash) que desbloquea T80. Pendiente: commit de respaldo (persona usuaria) y consolidación menor de docs (A.4 / API F.2 / D.5). |
| 2026-11-19 | Consolidación de docs ejecutada (A.4: promotions/billing/reporting; D.5: `TAX_DOCUMENT_RETRIED`; filtro `active` de /customers diferido a T93). **T80 entregada**: corrida síncrona (refresh → demanda → calculador → líneas), PATCH con máquina de estados, OC DRAFT trazables; 20 IT; 4 cambios de base registrados en `CAMBIOS_BASE.md`; acciones de auditoría de reabastecimiento ratificadas en D.5. **Flake conocido documentado**: `SaleReturnControllerIT` falla solo entre 21:00–24:00 CLT (UTC vs zona local), ajeno a T80; verify final sin exclusiones programado tras las 24:00. Revisión del bloque O2 lanzada (`kimi-k2.7-code`). |
| 2026-11-20 | Detectado con ayuda de la persona usuaria: las sesiones del revisor O2 y del verify final de T80 **murieron silenciosamente** en la espera de medianoche (sin notificación de error; los logs agendados no existen; ninguna sección Revisión escrita). Relanzada la fase de cierre de la revisión O2 (`kimi-k2.7-code`) con los hallazgos preservados (validar `GenerateDraftsRequest`: `supplierIds` implementado vs `lineIds` de F.3; corrida excluida 621 en verde) y verify completo fuera de la ventana del flake. Briefing Fase 8 (T90–T95) recibido con 6 cambios de base común anticipados. |
| 2026-11-20 | Cierre de revisión O2: **T78 y T79 aprobadas**; **T80 requiere cambios** (crítico del contrato + 2 importantes; corrector ya en marcha con la prescripción literal y el `[I]` ratificado). Verify sin exclusiones **BUILD SUCCESS 360/360 + 267/267**; `SaleReturnControllerIT` pasó fuera de ventana (flake horario confirmado). |
| 2026-11-20 | Ciclo 1 de T80 **aprobado con observaciones** → **T80 `aprobada`**: contrato `lineIds` conforme F.3, `@Idempotent` + `@Valid` agregados; conteos reales 346-347/267 (la discrepancia con 360 era residuo compilado de `NoopPromotionEngineTest`, no pérdida del fix). **Hallazgo aparte:** `ReportFilterTest` (reporting, T78) tiene 15 `@Test` no descubiertos por surefire — agendado fix pequeño en paralelo con T82. **T81 lanzada** (`glm-5.3-flash`). |
| 2026-11-20 | Consolidación: `[I]` marcado en `API.md` F.2 para `POST /replenishment/runs/{id}/purchase-orders` (ratificado por la orquestación en el ciclo de T80). Bloque O2 de Fase 6 completo: T78/T79/T80 `aprobadas`. |
| 2026-11-20 | **T81 entregada**: api:gen 232/17; grupo `fn/replenishment` materializado (9 fns) con `GenerateDraftsRequest { lineIds }`; 137 imports verificados por (método, PATH) + 2 verificaciones automáticas nuevas en 0 errores; suite 684/684. Lanzadas en paralelo: **T82** (frontend reabastecimiento, `glm-5.3-flash`) y **fix de `ReportFilterTest`** (15 `@Test` no descubiertos). |
