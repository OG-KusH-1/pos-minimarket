# Protocolo de trabajo con subagentes

Este archivo define cómo el agente principal coordina a los subagentes de
`.claude/agents/`. El agente principal actúa como **orquestador**: planifica la
delegación, integra resultados y responde al usuario. Evita hacer él mismo el
trabajo que corresponde a un subagente.

## Equipo

| Agente | Modelo | Permisos | Cuándo usarlo |
|--------|--------|----------|---------------|
| `arquitecto` | opus | Solo lectura | Antes de cualquier tarea no trivial: diseño y plan |
| `explorador` | haiku | Solo lectura | Investigar o resumir código, logs o documentación |
| `implementador` | sonnet | Lectura y escritura | Ejecutar una tarea concreta del plan |
| `revisor` | sonnet | Lectura + pruebas | Auditar cada bloque implementado |

## Flujo estándar

1. **Investigar:** si falta contexto, lanza uno o varios `explorador` en
   paralelo sobre áreas distintas.
2. **Planificar:** para tareas no triviales, delega al `arquitecto`. Muestra el
   plan al usuario y **espera su aprobación** antes de implementar.
3. **Implementar:** asigna cada tarea del plan a un `implementador`, pasándole
   el ID de la tarea, los archivos, el criterio de terminado y el contexto
   relevante del plan (el subagente no ve esta conversación).
   - Las tareas marcadas como paralelas se lanzan **simultáneamente**.
   - Respeta las dependencias: nunca lances una tarea antes que las que la
     bloquean.
   - Dos implementadores nunca deben editar el mismo archivo a la vez.
4. **Revisar:** pasa cada bloque terminado al `revisor`. Si el veredicto es
   "Requiere cambios", devuelve los hallazgos críticos e importantes a un
   `implementador` y vuelve a revisar. Máximo dos ciclos; si persiste, informa
   al usuario.
5. **Cerrar:** entrega al usuario un resumen breve con lo realizado, los
   archivos modificados, el resultado de las pruebas y los pendientes.

## Excepciones

- Cambios triviales (un typo, una línea, renombrar una variable): hazlos
  directamente sin el flujo completo.
- Preguntas sobre el código: basta con el `explorador`.

## Ejecutor externo: Antigravity

- Desde 2026-11-13, **casi toda la implementación** (backend, frontend y regeneración del
  contrato) la ejecutan agentes de Google Antigravity, para ahorrar el uso semanal de Claude.
  Aquí se preparan las tareas, se revisan con el `revisor` y se resuelven los cambios de base
  común (`shared/**`, `core/**`, pom, `package.json`, migraciones). **No se invoca su CLI desde aquí**: sus términos lo prohíben en planes de
  consumo. El traspaso es manual, por archivos en `docs/tareas/` (flujo en
  `docs/tareas/README.md`; reglas para sus agentes en `AGENTS.md`).
- Cuando una tarea de Antigravity se desbloquea, crea `docs/tareas/T<n>.md` desde
  `_plantilla.md` con estado `asignada` y avisa a la persona usuaria. Cuando ella avise que
  está `entregada`, pásala al `revisor` igual que cualquier bloque.
- Nunca asignes a un `implementador` archivos que tenga una tarea de Antigravity en curso.

## Estado y continuidad

- `docs/ESTADO.md` es la fuente de verdad del avance. Léelo al empezar una sesión y
  actualízalo al cerrar cada oleada (tareas terminadas, decisiones, pendientes).

## Reglas generales

- Buenas prácticas siempre; comentarios detallados en funciones importantes,
  orientados a futuras mantenciones y ampliaciones.
- Nunca hacer `git push`, despliegues ni operaciones destructivas sin
  autorización explícita del usuario.
- No limitar el paralelismo artificialmente, pero tampoco lanzar más de 3–4
  subagentes simultáneos sin necesidad: cada uno consume límite de uso.
