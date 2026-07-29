# Cuentivo Privacy Site Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publicar una política de privacidad de Cuentivo, operada por Capilabs en México, lista para usar en Google Play.

**Architecture:** Sitio estático de una sola página con HTML semántico, CSS adaptable y el icono oficial. No usa JavaScript, cookies, analítica, autenticación ni servicios externos en tiempo de ejecución.

**Tech Stack:** HTML5, CSS3, Vercel Static Hosting.

## Global Constraints

- Repositorio remoto exacto: `https://github.com/diegodevxd/Cuentivo-politicasdeprivacidad.git`.
- Responsable de datos: Capilabs, México.
- Contacto: `diegomancera.dev@gmail.com`.
- Idioma: español.
- No incluir términos y condiciones ni afirmar certificaciones inexistentes.
- Describir Firebase Authentication, Cloud Firestore, Firebase Analytics, Google Play Billing, Vercel y Resend.
- No incluir secretos.

---

### Task 1: Página pública de privacidad

**Files:**
- Create: `web/Cuentivo-politicasdeprivacidad/index.html`
- Create: `web/Cuentivo-politicasdeprivacidad/styles.css`
- Create: `web/Cuentivo-politicasdeprivacidad/favicon.png`
- Create: `web/Cuentivo-politicasdeprivacidad/README.md`
- Create: `web/Cuentivo-politicasdeprivacidad/vercel.json`

**Interfaces:**
- Consumes: `artifacts/play-store/cuentivo-icon-512.png`.
- Produces: página pública estática y enlace a `https://eliminacion-cuentivo.vercel.app`.

- [ ] **Step 1: Crear una comprobación que falle antes de construir**

Run:

```powershell
Test-Path web/Cuentivo-politicasdeprivacidad/index.html
```

Expected: `False`.

- [ ] **Step 2: Crear el HTML semántico completo**

El documento debe declarar `lang="es-MX"`, título `Política de privacidad | Cuentivo`, descripción para Google Play, cabecera con Capilabs, tabla de contenido y secciones numeradas para responsable, datos tratados, finalidades, proveedores, transferencia, seguridad, conservación, eliminación, derechos ARCO, menores, cambios y contacto. Debe enlazar:

```html
<a href="https://eliminacion-cuentivo.vercel.app">Solicitar eliminación de cuenta</a>
```

- [ ] **Step 3: Implementar la identidad visual**

Definir en `styles.css` estas variables exactas y aplicarlas a un diseño móvil:

```css
:root {
  --cloud-white: #f7fafc;
  --mist-blue: #e6edfb;
  --mint-haze: #e2f7f0;
  --blush-haze: #fbe9f3;
  --deep-ink: #0b1b23;
  --slate-ink: #4a5d68;
  --neon-mint: #00e5a0;
  --mint-ink: #00795a;
}
```

Incluir foco visible y `@media (prefers-reduced-motion: reduce)`.

- [ ] **Step 4: Añadir recursos y configuración**

Copiar el icono oficial como `favicon.png`, configurar Vercel para servir archivos estáticos y documentar en `README.md` la importación del repositorio.

- [ ] **Step 5: Validar**

Run:

```powershell
node -e "const fs=require('fs');const h=fs.readFileSync('web/Cuentivo-politicasdeprivacidad/index.html','utf8');for(const x of ['Capilabs','Firebase Authentication','Cloud Firestore','Google Play Billing','derechos ARCO','eliminacion-cuentivo.vercel.app'])if(!h.includes(x))throw Error(x)"
```

Expected: exit `0`.

- [ ] **Step 6: Commit y push**

```powershell
git -C web/Cuentivo-politicasdeprivacidad add index.html styles.css favicon.png README.md vercel.json
git -C web/Cuentivo-politicasdeprivacidad commit -m "feat: publica politica de privacidad de Cuentivo"
git -C web/Cuentivo-politicasdeprivacidad push origin main
```

Expected: push exitoso a `diegodevxd/Cuentivo-politicasdeprivacidad`.
