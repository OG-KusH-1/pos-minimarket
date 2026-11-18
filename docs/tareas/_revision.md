# Lista de revisión de un bloque (modo autónomo)

Prompt para el agente revisor de Antigravity (cambia la oleada):

> Revisa las tareas de la Fase 1, oleada O3 (y las oleadas anteriores del bloque aún no
> revisadas) siguiendo `docs/tareas/_revision.md`. No modifiques código: solo escribe los
> hallazgos en la sección "Revisión" de cada tarea.

## Cómo revisar

1. **No modifiques código.** Solo lees, ejecutas comandos de verificación y escribes en la
   sección "Revisión" de cada tarea.
2. Ejecuta la verificación completa (`AGENTS.md` §4): backend `verify` y frontend. Anota los
   conteos y cualquier fallo.
3. Por cada tarea del bloque, compara su Entrega con su archivo de tarea y con el diseño.

## Qué comprobar en cada tarea

- **Alcance:** solo tocó sus "Archivos permitidos" (más lo registrado en `CAMBIOS_BASE.md`).
- **Criterio de terminado:** cada punto tiene una prueba que lo demuestra de verdad. Busca
  falsos verdes: aserciones que pasarían aunque el código no hiciera nada.
- **Diseño:** endpoints, DTO, permisos y errores coinciden con `docs/API.md` F.2/F.3 y
  `docs/ROLES_PERMISOS.md`. Las tablas y columnas coinciden con `docs/MODELO_DATOS.md`.
- **Módulos:** respeta las dependencias de `docs/PLAN.md` A.4. No hay SQL directo sobre
  tablas de otro módulo (usa su API pública). `ModularityTest` en verde.
- **Reglas de negocio:** las RN de `docs/MODELO_DATOS.md` B.15 que aplican se cumplen y
  están probadas (stock nunca negativo, segregación de funciones, límites por rol, overrides
  de un solo uso, idempotencia).
- **Seguridad:** permisos en cada endpoint, alcance por local (`X-Store-Id`), sin datos de
  costo para quien no tiene `catalog:cost:read`, sin secretos en el código, sin exponer
  detalles internos en los errores, validación de entradas.
- **Notas obligatorias:** `docs/PLAN.md` sección L (`@GlobalEndpoint`, `@RequiresTerminal`,
  idempotencia, BD compartida en pruebas).
- **Dinero:** `BigDecimal`, redondeos de A.5, nada de `double`/`float`.
- **Calidad:** Javadoc/TSDoc en español en lo importante; código e identificadores en inglés.
- **Pruebas sobre la BD compartida:** afirman de forma relativa y no dejan datos que rompan
  otras pruebas (L-08).

## Formato de los hallazgos (en la sección "Revisión" de la tarea)

```
### Revisión autónoma <n> (<fecha>) — <Aprobado | Aprobado con observaciones | Requiere cambios>
**Críticos**
1. <archivo:línea> — <problema> — <corrección exacta>
**Importantes**
1. ...
**Menores**
1. ...
```

Si el veredicto es "Requiere cambios", la tarea vuelve a `asignada` y un agente corrige solo
los críticos e importantes, agregando una "Entrega <n+1>". Máximo dos ciclos por tarea: si
persiste, deja la tarea en `entregada` con una nota "pendiente para el orquestador".
