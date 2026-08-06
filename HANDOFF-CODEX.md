# Contexto para continuar Cuentivo (handoff)

Pega esto completo al abrir la sesión nueva.

---

## Qué es el proyecto

**Cuentivo** — app Android offline-first para pequeños negocios que venden fiado.
Modelo: negocio → clientes → productos → ventas → cargos → abonos → saldo.

- Repo: `C:\Users\pibed\AndroidStudioProjects\OrganizationLabs-neo-fintech`
- Rama: `codex/neo-fintech`
- Está en **prueba cerrada en Play Store con 12 testers**. Lo que se rompa, lo ven ellos.
- Lee `CLAUDE.md` y `PROJECT_MEMORY.md` al empezar. `CLAUDE.md` tiene la arquitectura completa.

## Reglas innegociables del proyecto

1. **Dinero siempre `Long` en centavos.** Nunca `Double` ni `Float`.
2. **Nada financiero se borra ni se edita.** Ventas, pagos y movimientos se corrigen con un
   movimiento compensatorio, jamás con `UPDATE`/`DELETE`.
3. **Toda consulta filtra por `businessId`.**
4. **Nada de PII, saldos ni tokens** en logs, Analytics o Crashlytics.
5. **No declarar nada completo sin compilar y ejecutar de verdad.** Compilar no es verificar.

## Cómo compilar (obligatorio, Norton intercepta TLS)

```bash
export JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
export JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStore=C:\tmp\cuentivo-jbr-cacerts -Djavax.net.ssl.trustStorePassword=changeit'
./gradlew.bat testDebugUnitTest --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

`--no-watch-fs` no es opcional. Sin el trust store falla con `PKIX path building failed`.

Emulador: SDK en `C:\Users\pibed\AppData\Local\Android\Sdk`. Hay un AVD (`Medium_Phone`, **API 37**).
`adb` está en `$SDK/platform-tools/adb`. Para capturar pantalla usa `MSYS_NO_PATHCONV=1` y rutas
Windows en el `adb pull`, o falla.

---

## Qué se hizo en la sesión anterior (ya está en el repo, compilando y con pruebas verdes)

### Tarea A — claridad del estado de cuenta
Un tester reportó que para una deuda de $5,000 las cuentas quedaban confusas.

- `domain/CustomerStatementSummary.kt` (nuevo): totales **netos** (una cancelación resta del concepto
  que revierte, no suma como cargo nuevo) + saldo corrido + detección de abonos cancelados.
  **Invariante crítica:** `balanceCents` debe ser siempre idéntico a `FinancialDao.customerBalanceCents`.
  Hay una prueba que lo fija (`totals_balance_always_matches_the_ledger_debit_minus_credit_formula`).
- `StatementEntry` ganó `paymentId` para emparejar un abono con su cancelación.
- `CustomerLedgerPresentation`: `"Reembolso"` → **`"Abono cancelado"`** (esa palabra hacía que la
  línea pareciera una compra nueva).
- `CustomerDetailSheet`: fila de totales (Le vendiste / Te ha abonado / Debe hoy), saldo corrido bajo
  cada movimiento, abono cancelado tachado, fechas legibles con el locale de la app.

### Tarea B — cupo de crédito
- Campo "Cupo de crédito (opcional)" en alta y edición de cliente. Rechaza $0.
- Se arregló `MainActivity` que encolaba `creditLimitCents: null` fijo al sincronizar.
- `NewSaleScreen`: aviso (**no bloqueo**) si saldo + carrito supera el cupo. Decisión deliberada: el
  dueño está frente al cliente y necesita poder decidir.
- `domain/CreditLimitAdvisor.kt` (nuevo, puro, con pruebas): sugiere subir el cupo tras ≥3 ventas
  liquidadas sin cancelaciones ni pagos tardíos en 6 meses. **Devuelve `null` si el cliente no tiene
  cupo actual** — proponer un número a quien tiene crédito ilimitado sería imponerle un techo, no
  premiarlo. Nunca sube solo: tarjeta con "Subir cupo" / "Ahora no", descarte recordado en
  `CreditLimitSuggestionStore`.

### Tarea C — idioma español/inglés (completa en `ui/`)
- **186 claves en `values/strings.xml` y 186 en `values-en/strings.xml`**, paridad exacta.
- Selector de idioma en `AccountSheet` (Español / English / Predeterminado).
- `MainActivity.attachBaseContext` aplica el locale guardado. **No** se usa `AppCompatActivity`
  porque `Theme.Cuentivo` extiende `android:Theme.Material.Light.NoActionBar` (tema del framework) y
  `AppCompatActivity` reventaría al arrancar. Verificado leyendo `themes.xml`.
- `settings/LanguagePreference.kt` (nuevo): SharedPreferences propias. Es lo que hace que el idioma
  sobreviva a cerrar la app en Android 7–12, donde el sistema no tiene LocaleManager.
- `Money.format` es **es-MX fijo a propósito**. El dinero se lee igual en ambos idiomas: un negocio
  mexicano cobra en pesos. Enhebrar el locale por sus 33 call sites (varios en `domain/`, sin acceso
  a Compose) produciría dos formatos distintos en la misma pantalla.

---

## Lo que falta — en orden de prioridad

### 1. Recorrido funcional en emulador (bloqueante antes de subir build a testers)

**Nada del flujo real se ha visto correr.** La sesión anterior no pudo pasar del login. Con sesión
iniciada, verificar:

1. Cliente nuevo con cupo $3,000 → venta de $5,000 → aparece la advertencia y **la venta sí se registra**.
2. Abono de $2,000 → cancelarlo desde "Corregir" → el estado de cuenta debe mostrar
   **Le vendiste $5,000 / Te ha abonado $0 / Debe hoy $5,000**, el abono tachado, la línea
   "Abono cancelado", y el saldo corrido 5.000 → 3.000 → 5.000.
   **Este es el caso exacto que reportó el tester.**
3. Cuenta → Idioma → English → toda la app en inglés, los montos siguen en pesos. Cerrar la app por
   completo y reabrir → **debe seguir en inglés**.
4. Cliente sin cupo con buen historial → **no** debe aparecer la tarjeta de sugerencia.

Reportar el resultado real de cada paso, con la salida que dio si falla.

### 2. Verificar en API 26–30

Solo hay imagen de sistema API 37 instalada. El riesgo del idioma vive por debajo de API 33. Instalar
una imagen API 28 desde el SDK Manager y repetir el paso 3 de arriba.

### 3. Textos en español fuera de `ui/`

Faltan: los snackbars de `MainActivity` y los literales de `domain/AppAlerts.kt`,
`domain/BusinessInsights.kt`, `domain/CustomerStatementCsv.kt`, `domain/DebtorShareText.kt`,
`domain/SaleDraft.kt`, `domain/SubscriptionTier.kt`, `auth/AuthenticationGateway.kt`,
`billing/BillingGateway.kt`.

**Trampa importante:** los `object` de `domain/` son Kotlin puro sin `Context` — por eso corren en
`testDebugUnitTest`. **No les metas `Context` ni `R.string` dentro**: rompe las pruebas unitarias y la
separación que hace valiosa esa capa. El patrón correcto es que el `object` devuelva una **clave o un
enum**, y la capa de UI resuelva el texto.

`CustomerStatementCsv` y `DebtorShareText` producen contenido que sale de la app (CSV, WhatsApp):
deben usar el idioma activo.

`AuditAction.summary` se persiste en `audit_logs` — **no traducir en el momento de escritura**.
Guardar la clave y traducir al mostrar, o dejarlo en español fijo. Decidirlo explícitamente.

### 4. Tarea D — promociones (nunca se empezó)

Del plan original aprobado por el usuario. Requiere **migración de Room y cambio de
`firestore.rules`**, es lo más riesgoso:

- Migración **4 → 5**: dos columnas nullable en `products` — `promoPriceCents: Long?` y
  `promoEndsAtEpochMillis: Long?`.
  - Subir `version = 5` en `data/local/CuentivoDatabase.kt`, escribir `MIGRATION_4_5`, agregarla a
    `CUENTIVO_MIGRATIONS`.
  - **Registrar el arreglo en los tres builders de `"cuentivo.db"`**: `MainActivity`,
    `sync/SyncWorker.kt`, `androidTest/.../SyncEmulatorE2ETest.kt`. Olvidar uno hace que solo truene
    esa ruta.
  - Cotejar el SQL contra el `createSql` del esquema exportado en `app/schemas/.../5.json`. Room
    valida un hash: una diferencia de un backtick hace que la migración corra y aun así falle.
- `firestore.rules` usa `hasExactKeys()`, que **rechaza llaves de más**. Un cliente en versión 4
  subiendo un producto sin las llaves nuevas sería rechazado si las reglas ya las exigen. Hacerlas
  opcionales, o desplegar las reglas *después* de que los testers actualicen.
- UI: campos de promo en el formulario de producto, etiqueta "Promo" con precio normal tachado,
  banner en Inicio. El predicado de vigencia va en **un solo** `object` puro de `domain/` con pruebas.
- Compartir la promo por `ACTION_SEND` (patrón ya existente en `CustomerDetailSheet` /
  `DebtorShareText`). **Sin PII** en ese texto.
- Notificación local: hoy no existe nada — ni permiso `POST_NOTIFICATIONS`, ni canal, ni trabajo
  periódico (solo hay `SyncWorker` con `OneTimeWorkRequest`). El worker nuevo construiría su propia
  instancia de Room, así que **hay que registrarle `CUENTIVO_MIGRATIONS` también**.

---

## Puerta de verificación del idioma

El trabajo de i18n no está hecho hasta que esto salga vacío (salvo etiquetas de animación de Compose):

```bash
grep -rnE '(Text\(|text = |label = |title = |detail = |placeholder = |contentDescription = |emptyText = |actionLabel = |eyebrow = |note = |backDescription = )"[^"]*[a-zA-ZáéíóúñÁÉÍÓÚÑ¿¡][^"]*"' app/src/main/java/com/cuentivo/app/ui --include=*.kt | grep -vE '"\$ "|" · "|testTag|neo-action-press|"Cuentivo"'
```

Y la paridad de claves debe seguir cuadrando:

```bash
grep -c "<string name=" app/src/main/res/values/strings.xml app/src/main/res/values-en/strings.xml
```

**Cuidado con los apóstrofos en `values-en/`**: Android exige `\'`. Escribirlos desde un script con
varias capas de shell mangla el escapado y produce
`Invalid unicode escape sequence in string` al mergear recursos. Lo más seguro es redactar el inglés
sin apóstrofos ("do not" en vez de "don't").

---

## Cómo trabajar

- Cambios pequeños; compila y ejecuta pruebas después de cada bloque.
- **No declares algo terminado porque compiló.** El error más caro de la sesión anterior fue
  exactamente ese: se dio por bueno un total que mostraba $7,000 donde debía decir $5,000, y un
  selector de idioma que no aplicaba nada por debajo de API 33. Ambos compilaban perfecto.
- No inventes resultados de build. Reporta el comando que realmente ejecutaste y su salida.
- No reescribas por comodidad ni agregues dependencias innecesarias.
- No toques `app/google-services.json` (no está en `.gitignore` y aparece como untracked).
