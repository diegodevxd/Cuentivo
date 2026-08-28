# Runbook Operativo: Apagador Remoto de Emergencia (Killswitch) de CuentivoAI

Este runbook está diseñado para ser ejecutado de forma inmediata ante incidentes críticos (costos anómalos, degradación de Gemini, fallos del modelo o mantenimiento) a cualquier hora sin necesidad de leer código fuente.

---

## 1. Control Positivo Previo (OBLIGATORIO ANTES DE APAGAR)

> [!IMPORTANT]
> **No toques el interruptor sin validar la visibilidad de logs:**
> Una ausencia de eventos tras el corte es indistinguible de un filtro mal escrito, un proyecto incorrecto en la consola o una ventana de tiempo desfasada.
>
> **Antes de modificar Firestore, confirma que tu filtro devuelve filas con la IA encendida:**
> 1. Abre el **Explorador de registros (Logs Explorer)** en Google Cloud Console.
> 2. Aplica el filtro de la Sección 3 (con ventana de tiempo "Últimos 15 minutos" o "Última 1 hora").
> 3. Confirma que ves filas activas (`AI_PROVIDER_INVOCATION`, `AI_INTERPRET_RESULT` o ejecuciones de la función).
> 4. **Si NO ves filas con la IA encendida, NO continúes:** corrige el proyecto, la ventana de tiempo o usa el filtro alternativo por texto antes de apagar.

---

## 2. Procedimiento de Apagado de Emergencia

### Paso 1: Modificar el interruptor en Firestore
1. Accede a la **Consola de Firebase** o **Google Cloud Firestore**.
2. Navega a la colección **`aiControl`**.
3. Selecciona el documento **`global`** (Ruta: `aiControl/global`).
4. Edita el campo booleano **`enabled`** y asígnale el valor **`false`**.
5. Guarda los cambios.

---

## 3. Filtros de Cloud Logging y Verificación

Las callables de CuentivoAI son **Cloud Functions v2** (basadas en Cloud Run), por lo que se registran bajo el recurso `cloud_run_revision` y la etiqueta `service_name`.

### A. Filtros para `interpretAiRequest`

**Filtro Estándar (Cloud Functions v2 / Cloud Run):**
```text
resource.type="cloud_run_revision"
resource.labels.service_name="interpretAiRequest"
```

**Filtro Alternativo Resiliente (Independiente del tipo de recurso / cambios de infraestructura):**
```text
("AI_INTERPRET_RESULT" OR "AI_PROVIDER_INVOCATION" OR "AI_PROVIDER_FAILURE" OR "AI_PROVIDER_LATENCY" OR "AI_REJECTED")
```

---

### B. Filtros para `getAiAvailability`

**Filtro Estándar (Cloud Functions v2 / Cloud Run):**
```text
resource.type="cloud_run_revision"
resource.labels.service_name="getAiAvailability"
```

**Filtro Alternativo Resiliente:**
```text
("getAiAvailability" OR "AI_AVAILABILITY_REJECTED")
```

---

### C. Indicadores de Éxito del Apagado

1. **Evidencia Positiva de Corte (Obligatoria):**
   Aparecen nuevos registros con el estado de rechazo autoritativo:
   ```json
   {
     "message": "AI_INTERPRET_RESULT",
     "status": "AI_DISABLED"
   }
   ```
2. **Evidencia Negativa de Gasto:**
   Habiendo confirmado en el Paso 1 que aparecían eventos de proveedor, cesa por completo la emisión de:
   ```text
   AI_PROVIDER_INVOCATION
   ```
   *(Ninguna llamada es despachada al SDK de Gemini).*
3. **Consulta de Disponibilidad:**
   Las llamadas a `getAiAvailability` responden:
   ```json
   { "available": false }
   ```

---

## 4. Comportamiento y Tiempos de Propagación

El apagador opera bajo dos ventanas temporales distintas que quien opere debe conocer:

1. **Servidor (Backend Cloud Functions — Corte Inmediato):**
   - **Tiempo: 0 segundos (Inmediato).**
   - Toda invocación entrante a `interpretAiRequest` evalúa la transacción de autorización y corta antes de cualquier consumo o invocación al proveedor Gemini.
   - La respuesta autoritativa es `AI_DISABLED` con propuesta nula (`proposal: null`).
   - El gasto en Gemini se detiene al 100% de inmediato ($0.00).

2. **Clientes Android (Aplicación Móvil):**
   - **Tiempo: Hasta 5 minutos** (gobernado por el TTL de 5 minutos en memoria de `AiAvailabilityCoordinator` / `AI_AVAILABILITY_CACHE_TTL_MILLIS`).
   - Al volver a primer plano tras expirar el TTL, la app consulta `getAiAvailability`, detecta `available: false` y conmuta al estado de mantenimiento (`ServiceDisabled`).
   - **Instantáneo al reiniciar:** Si el usuario cierra y reabre la app, el estado de mantenimiento aparece de inmediato.
   - Si un usuario con la app ya abierta intenta enviar una solicitud dentro de la ventana de 5 minutos antes del refresco, el servidor rechaza autoritativamente con `AI_DISABLED` y la app muestra el mensaje de mantenimiento en 0 segundos.

---

## 5. Procedimiento de Reactivación (Encendido)

1. En la colección **`aiControl`**, abre el documento **`global`**.
2. Cambia el campo **`enabled`** nuevamente a **`true`**.
3. Verifica en Cloud Logging que:
   - Aparecen entradas con `AI_PROVIDER_INVOCATION`.
   - `AI_INTERPRET_RESULT` vuelve a emitir `AI_PROPOSAL`.

---

## 6. Advertencia Crítica: Trampa de `usedActions` vs `monthlyActionLimit`

> [!CAUTION]
> **No confundir `monthlyActionLimit` con `usedActions` (Incidente 2026-08-25):**
>
> - **`aiControl/global.monthlyActionLimit`**: Vive en la colección `aiControl` y define el límite mensual de acciones permitidas para el plan AI.
> - **`aiUsage/{uid}_{yyyy-MM}.usedActions`**: Vive en la colección `aiUsage` y es el contador de acciones consumidas por un usuario en el mes actual.
>
> **Peligro:** Modificar `usedActions` pensando que es un interruptor general deja a usuarios bloqueados por cuota agotada (`AI_QUOTA_EXHAUSTED`). Para apagar o encender CuentivoAI de emergencia, el **ÚNICO** documento y campo a modificar es:
> **`aiControl/global` → `enabled` (`true` / `false`)**.

---

## 7. Registro del Simulacro de Rollback (Para llenado en vivo por el Operador)

*(Esta sección debe ser completada exclusivamente por el operador al ejecutar el simulacro en vivo)*

- **Fecha y Hora del Simulacro:** ___________________________
- **Operador Responsable:** ___________________________
- **Entorno / Proyecto:** ___________________________
- **Control positivo previo (filas visibles con IA encendida antes del corte):** [ ] SÍ  [ ] NO
- **Hora de cambio `enabled = false`:** ___________________________
- **Confirmación de corte inmediato en servidor (0s / `AI_DISABLED`):** [ ] SÍ  [ ] NO
- **Confirmación de cese de `AI_PROVIDER_INVOCATION` en logs:** [ ] SÍ  [ ] NO
- **Tiempo medido de convergencia en cliente Android:** ___________________________
- **Hora de reactivación `enabled = true`:** ___________________________
- **Confirmación de retorno a `AI_PROPOSAL`:** [ ] SÍ  [ ] NO
- **Observaciones e incidencias durante el simulacro:**
  ____________________________________________________________________________________________________________________
  ____________________________________________________________________________________________________________________
- **Firma del Operador:** ___________________________
