# Cuentivo Account Deletion Site Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publicar una página de eliminación de cuenta de Cuentivo con un formulario automático compatible con Vercel y Resend.

**Architecture:** El navegador muestra una página estática y envía JSON a una función serverless del mismo origen. La validación compartida se prueba con Node; la función envía una notificación a Capilabs sin guardar solicitudes en base de datos.

**Tech Stack:** HTML5, CSS3, JavaScript ES modules, Vercel Functions, Resend, Node.js test runner.

## Global Constraints

- Repositorio remoto exacto: `https://github.com/diegodevxd/Eliminacion-Cuentivo.git`.
- Responsable: Capilabs, México.
- Receptor: `diegomancera.dev@gmail.com`.
- Solicitar únicamente el correo registrado, confirmación y un campo trampa invisible.
- No revelar si existe una cuenta.
- Plazo de eliminación: máximo 30 días.
- Conservación del comprobante: máximo 90 días.
- No almacenar solicitudes en base de datos ni incluir secretos.

---

### Task 1: Validación y función de solicitud

**Files:**
- Create: `web/Eliminacion-Cuentivo/lib/validate-request.js`
- Create: `web/Eliminacion-Cuentivo/api/solicitar-eliminacion.js`
- Create: `web/Eliminacion-Cuentivo/test/validate-request.test.js`
- Create: `web/Eliminacion-Cuentivo/package.json`
- Create: `web/Eliminacion-Cuentivo/.env.example`

**Interfaces:**
- Produces: `validateDeletionRequest(value): { ok: true, email: string } | { ok: false, status: number, message: string }`.
- Produces: `POST /api/solicitar-eliminacion` con respuesta JSON `{ ok, message }`.

- [ ] **Step 1: Escribir pruebas fallidas**

Cubrir con `node:test`: correo válido normalizado, correo inválido, confirmación ausente, honeypot lleno y cuerpo inexistente.

Run:

```powershell
npm --prefix web/Eliminacion-Cuentivo test
```

Expected: FAIL porque `lib/validate-request.js` no existe.

- [ ] **Step 2: Implementar validación mínima**

La función debe aceptar sólo objetos, limitar correo a 254 caracteres, normalizar con `trim().toLowerCase()`, exigir `confirmDeletion === true` y devolver éxito silencioso para honeypot lleno para no enseñar el filtro a bots.

- [ ] **Step 3: Implementar la función Vercel**

Aceptar sólo `POST`, validar `Content-Type: application/json`, usar `RESEND_API_KEY`, `DELETE_REQUEST_FROM` y `DELETE_REQUEST_TO`, y enviar un correo con el correo solicitado y la fecha ISO. Las respuestas no deben confirmar si la cuenta existe.

- [ ] **Step 4: Ejecutar pruebas**

Run:

```powershell
npm --prefix web/Eliminacion-Cuentivo install
npm --prefix web/Eliminacion-Cuentivo test
```

Expected: todas las pruebas PASS.

### Task 2: Interfaz pública

**Files:**
- Create: `web/Eliminacion-Cuentivo/index.html`
- Create: `web/Eliminacion-Cuentivo/styles.css`
- Create: `web/Eliminacion-Cuentivo/script.js`
- Create: `web/Eliminacion-Cuentivo/favicon.png`
- Create: `web/Eliminacion-Cuentivo/README.md`
- Create: `web/Eliminacion-Cuentivo/vercel.json`

**Interfaces:**
- Consumes: `POST /api/solicitar-eliminacion`.
- Produces: formulario accesible y enlace a `https://cuentivo-politicasdeprivacidad.vercel.app`.

- [ ] **Step 1: Construir contenido semántico**

Incluir pasos exactos dentro de la app, lista de datos eliminados, plazo de 30 días, conservación de comprobante por 90 días y formulario con `email`, `confirmDeletion` y honeypot `website`.

- [ ] **Step 2: Implementar envío y estados**

`script.js` debe desactivar el botón durante el envío, publicar JSON, mostrar confirmación genérica, limpiar el formulario en éxito y permitir reintentar después de errores.

- [ ] **Step 3: Aplicar identidad visual**

Reutilizar la paleta exacta de la política, con tarjetas translúcidas, foco visible, diseño móvil y respeto por `prefers-reduced-motion`.

- [ ] **Step 4: Documentar configuración**

`.env.example` debe contener sólo:

```dotenv
RESEND_API_KEY=
DELETE_REQUEST_FROM=Cuentivo <onboarding@resend.dev>
DELETE_REQUEST_TO=diegomancera.dev@gmail.com
```

`README.md` debe explicar cómo definir esas tres variables en Vercel y volver a desplegar.

- [ ] **Step 5: Validar sintaxis y contenido**

Run:

```powershell
node --check web/Eliminacion-Cuentivo/script.js
node --check web/Eliminacion-Cuentivo/api/solicitar-eliminacion.js
npm --prefix web/Eliminacion-Cuentivo test
```

Expected: exit `0` y todas las pruebas PASS.

- [ ] **Step 6: Commit y push**

```powershell
git -C web/Eliminacion-Cuentivo add .
git -C web/Eliminacion-Cuentivo commit -m "feat: publica solicitud de eliminacion de Cuentivo"
git -C web/Eliminacion-Cuentivo push origin main
```

Expected: push exitoso a `diegodevxd/Eliminacion-Cuentivo`.
