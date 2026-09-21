# El mensaje que le llega al cliente (OpenCode)

**Rama:** `diegodevxd/mensaje-al-cliente`, ya creada desde `5b48db9`. **Trabaja ahí.**
**Implementa:** OpenCode, **sin delegar**. **Revisa:** AGY. Lee `CLAUDE.md`.
**No commitees, no generes ningún build, no despliegues.** Reporte en `C:/tmp/oc-mensaje-reporte.md`.

Esto es el texto que el negocio le manda **a su cliente** por WhatsApp desde
Detalle de Cliente → Compartir → **Compartir resumen**. Hoy tiene un error de signos que puede
hacer que el cliente entienda que tiene saldo **a favor**.

## Lo que ya localizó Claude, para que no lo busques

| Qué | Dónde |
|---|---|
| El generador del mensaje | `domain/DebtorShareText.kt` → `forCustomer(...)` y `forBusiness(...)`. Kotlin puro, sin Android: **se prueba en `testDebugUnitTest`** |
| El defecto de signos | `DebtorShareText.kt:44` → `val sign = if (entry.direction == "DEBIT") "+" else "−"` |
| La etiqueta "Venta" | `movementLabel(...)`, al final del mismo archivo |
| Quién lo llama | `MainActivity.kt:2402`, `:2466` (al cliente) y `:2471` (lista de deudores) |
| Cómo se manda | `MainActivity.kt:2588` `share(...)`: `ACTION_SEND` + `createChooser`. **No hay `wa.me` en ninguna parte** |
| El nombre por defecto | `"Mi negocio"`, repetido en `MainActivity.kt:1093`, `:1414`, `:1471`, `:1675`, `SyncContracts.kt:94` y `FirestoreSyncGateway.kt:131` |
| El plan | `domain/SubscriptionTier.kt`: `FREE`, `PRO`, `AI`, con `includesProBenefits`. En `MainActivity` está en `tierState` (`:247`) y ya hay `val tier by tierState` (`:804`) |
| El CSV, que **no se toca** | `domain/CustomerStatementCsv.kt` |

---

## 1 · El nombre del negocio

Hoy el encabezado es `"Hola $customerName, te comparto el estado de tu cuenta con $businessName."`, y
con el valor por defecto sale **"…con Mi negocio"**, que es una fuga de un detalle interno al cliente.

- **Vacío o igual al valor por defecto** → `Hola Dana TS, te comparto tu estado de cuenta.`
- **Configurado** → `Hola Dana TS, te comparto tu estado de cuenta de Novedades Lupita.`

El valor por defecto va en **una constante** (hoy está escrito a mano en seis sitios). Compara
ignorando espacios alrededor y mayúsculas. No cambies el valor por defecto de la entidad: sólo
**detéctalo** para no enseñarlo.

## 2 · Los signos, desde el punto de vista del cliente

El mensaje lo lee el cliente, no el negocio. Hoy una compra de $799 le llega como **"+$799.00"**.

- **`CHARGE` → "Compra", sin signo.**
- **`PAYMENT` → "Abono", con el signo menos** (`−`, el mismo carácter de hoy).
- **Saldo pendiente sin signo.**

**Los otros tipos no los especificó Diego y hay que resolverlos, no ignorarlos.** Usa la regla que
generaliza las dos de arriba: **el signo lo da `direction`** — lo que **aumenta** la deuda
(`DEBIT`) va sin signo, lo que la **baja** (`CREDIT`) lleva menos. Así `Devolución`, `Cancelación` y
un `Ajuste` en cualquier dirección quedan bien solos. Las demás etiquetas se quedan como están; sólo
"Venta" pasa a **"Compra"**.

**No cambies cómo se ven los montos dentro de la app** (ahí una venta seguirá siendo "−$799.00
Deuda", que es correcto desde el lado del negocio) **ni el CSV.**

## 3 · El pie de "Enviado con Cuentivo"

Al final del mensaje, **sólo en plan Gratis**:

```
Enviado con Cuentivo · Gratis en Google Play
https://play.google.com/store/apps/details?id=com.cuentivo.app
```

- La URL va en **una constante** y no se repite.
- **Con `PRO` o `AI` no se agrega.** Usa `includesProBenefits`, no `== FREE`: así un plan nuevo de
  pago que se añada mañana tampoco lo lleva por olvido.
- El generador **recibe el plan**, no lo adivina: cambia la firma y actualiza los tres llamadores.
  El `tier` ya está disponible en `MainActivity`, no hay que cablear nada nuevo.

## 4 · El otro sitio que manda texto: la lista de deudores

`forBusiness(...)` es la lista del dueño ("Clientes por cobrar"), pero **también se comparte**, así
que le aplican las mismas reglas: **el pie en plan Gratis**. Los signos ahí ya están bien (no lleva
`+`/`−`), pero **compruébalo y dilo**.

**Ojo con la privacidad, y esto no se negocia:** `forCustomer` **nunca** puede llevar el nombre ni el
saldo de otro cliente — está escrito en el KDoc del archivo y es la razón de que sean dos funciones
separadas. Si tu cambio las acerca, sepáralas más, no menos.

## 5 · El aviso en la pantalla de compartir

Si el nombre del negocio no está configurado, la pantalla de compartir debe **avisarlo** y dar
**acceso directo a configurarlo**.

**Primero busca dónde se edita hoy el nombre del negocio.** Si existe esa pantalla, enlaza a ella.
**Si no existe, dilo y no la inventes**: lo reportas y ahí se decide.

**DECIDIDO por Diego el 2026-09-20: opción C — NO se toca el onboarding.** Sólo el aviso en la
pantalla de compartir con acceso directo. El motivo: el onboarding es donde más se abandona una app,
y el aviso resuelve el problema justo en el momento en que importa. Si más adelante se ve que mucha
gente comparte sin nombre configurado, se añade una pantalla previa que sólo pida el nombre.

Para que quede el porqué: el onboarding de hoy es una sola pantalla
(`ui/onboarding/FirstDebtScreen.kt`, 159 líneas: nombre y monto de la primera deuda, con saltar), y
meter ahí un tercer campo es un cambio de producto que no hace falta para arreglar el mensaje.

## 6 · WhatsApp directo (`wa.me`): **sólo evaluar, no implementar**

Mira qué implicaría abrir directo el chat del cliente (`wa.me/<tel>?text=…`) cuando hay teléfono, con
vuelta al menú de siempre si no hay número o no está WhatsApp. **No escribas el código.** En el
reporte: qué archivos tocaría, si hace falta declarar `<queries>` en el manifiesto para Android 11+,
qué pasa con el formato del teléfono (lada, espacios, el "55 0000 0000" de las pruebas) y qué se
rompe si WhatsApp no está. Diego además comentó que **le parece una vía algo anticuada**, así que la
decisión probablemente sea no hacerlo: tu trabajo es darle los datos para decidir.

---

## Pruebas

En `testDebugUnitTest`, sobre `DebtorShareText` (es Kotlin puro, invócalo de verdad):

- **Sin nombre**: vacío, sólo espacios, y **exactamente el valor por defecto "Mi negocio"** →
  encabezado corto.
- **Con nombre** → `…tu estado de cuenta de Novedades Lupita.`
- **Sólo compras** · **compras y abonos** · **saldo en cero** (que sigue diciendo que está al
  corriente).
- **Gratis** → con pie; **PRO** y **AI** → **sin** pie.
- **Una compra nunca lleva `+`**, y un abono siempre lleva `−`. Aféralo sobre el texto generado.
- La lista de deudores: pie en Gratis, sin pie en pago.

Y una que vale más que las otras: **el mensaje de un cliente no contiene el nombre de otro.**

**Cada prueba tiene que fallar si se rompe su regla** (mutación).

## Gate

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStore=C:\tmp\cuentivo-jbr-cacerts -Djavax.net.ssl.trustStorePassword=changeit'
.\gradlew.bat testDebugUnitTest --rerun assembleDebug --no-daemon --no-configuration-cache --no-watch-fs --console=plain
```

Conteo desde los XML (referencia **1246**). Si tocas `strings.xml`: paridad ES/EN, inglés **sin
apóstrofos**, y la puerta del idioma. **No lances Gradle mientras otro corre en esta carpeta.**

## Entrega

Además de lo de siempre (archivos con línea, comando real con su salida, conteo, mutaciones), pega
**el texto completo generado** para tres casos, tal como lo recibiría el cliente:

1. **Gratis con nombre de negocio**
2. **Gratis sin nombre**
3. **Pro con abonos**
