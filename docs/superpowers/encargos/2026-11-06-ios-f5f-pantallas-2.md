# Encargo iOS — F5-F: pantallas de dinero, fechas de plataforma, venta, tablero y detalle

**Implementador:** OpenCode (DeepSeek v4.1 Flash). **SIN DELEGAR.** Sin pedir confirmación.
**Carpeta:** `C:\Users\pibed\orca\workspaces\OrganizationLabs-neo-fintech\cuentivo-ios`.
No hagas push. Commits `ios(F5-F): ...`, **y cada commit debe compilar en Android**. Reporte en `C:/tmp/ios-relevo/oc-ios-f5f-reporte.md`. **No pidas acceso a nada más fuera del repo y `C:/tmp/ios-relevo`.**

**Objetivo:** mover pantallas a `commonMain` **sin cambiar comportamiento en Android**. No cambies textos, cálculos de dinero, `testTag` ni comentarios. Estas pantallas registran ventas y cobros: **una línea de lógica distinta es un defecto, aunque compile.**

## Por qué así (ya decidido; no lo re-decidas)

- **Mismo paquete en cada archivo.** `internal` se quita **solo** de lo que `:app` o `androidTest` usan de verdad.
- **`commonMain` no nombra relojes ni `kotlinx-datetime`:** Android compila contra 0.6.2 e iOS contra 0.7.1, y por ejemplo `toEpochDays()` devuelve tipos distintos. Por eso fechas y reloj van con `expect`/`actual` de funciones que hablan en `Long` y `String`.
- **Android conserva exactamente su formato de hoy:** `DateTimeFormatter` y `DateFormat`, con el `Locale` de la composición donde ya se usa, y es-MX fijo donde ya es fijo.
- **`PlatformBackHandler` en iOS no hace nada, a propósito.** iOS no tiene botón atrás y `CustomerDetailSheet` ya tiene `DetailTopBar` visible para cerrar.
- **Fuera de esta tanda, no los toques:** `ProductPhoto`, `ProductsScreen`, `CustomersScreen`, `AccountSheet`, `LanguageSheet`, `FirstDebtPreferenceStore`, `CuentivoApp.kt`, `MainActivity.kt`, `ui/ai/*`. Solo ajusta un consumidor si deja de compilar, y dímelo.

## Commit 1: tres pantallas sin plataforma

Mueve a `shared/src/commonMain/kotlin/com/cuentivo/app/ui/`, mismo paquete: `onboarding/FirstDebtScreen.kt`, `customer/OpeningBalanceImportDialog.kt`, `transaction/PaymentScreen.kt`.

- `Res.string.*` y `stringResource`/`painterResource` de `org.jetbrains.compose.resources`, con `import com.cuentivo.shared.resources.*`, como en `AuthenticationScreen.kt`.
- `FirstDebtScreen`: logo con `Res.drawable.ic_cuentivo_logo`. Conserva `scale` y comentario.
- `OpeningBalanceImportDialog.kt:77-79`: lo que hoy es un `Int` de `R.string` pasa a `StringResource`. Conserva el orden de las ramas.

## Commit 2: `platform/PlatformClockAndDates`

`shared/src/commonMain/kotlin/com/cuentivo/app/platform/PlatformDates.kt`:

```kotlin
/** Hoy, en días desde 1970, en la zona del dispositivo. */
expect fun todayEpochDay(): Long

/** Un UUID v4 en minúsculas, con guiones. */
expect fun newUuid(): String

/** `yyyy-MM-dd` a días desde 1970. Lanza si no es una fecha válida. */
expect fun isoDateToEpochDay(iso: String): Long

/** "1 ene 2025", con el idioma de la composición. Una fecha de negocio: no depende de la zona. */
@Composable expect fun formatDayMonthYear(epochDay: Long): String

/** "14:05" en la zona del dispositivo, con el idioma de la composición. */
@Composable expect fun formatHourMinute(epochMillis: Long): String

/** Hora corta en es-MX, fija: es lo que hace hoy el tablero. */
expect fun formatShortTimeEsMx(epochMillis: Long): String

@Composable expect fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit)
```

**`androidMain` (`PlatformDates.android.kt`):**
- `LocalDate.now().toEpochDay()`, `UUID.randomUUID().toString()`, `LocalDate.parse(iso).toEpochDay()`.
- `formatDayMonthYear`: `LocalConfiguration.current.locales[0]`, `remember(locale) { DateTimeFormatter.ofPattern("d MMM uuuu", locale) }` y `LocalDate.ofEpochDay(...)`. Copiado de `CustomerDetailSheet.kt:424-427`.
- `formatHourMinute`: copiado de `CustomerDetailSheet.kt:432-434`.
- `formatShortTimeEsMx`: copiado de `DashboardScreen.kt:491-493`.
- `PlatformBackHandler`: `androidx.activity.compose.BackHandler`.
- Separa la parte pura en funciones `internal` que reciban `Locale` y, si aplica, `ZoneId`, para probarlas sin composición.

**`iosMain` (`PlatformDates.ios.kt`):**
- `todayEpochDay` con `iosNowLocalDateTime().date` de `IosClock.kt`, convertido a `Long` (`.toEpochDays().toLong()`).
- `newUuid`: `NSUUID().UUIDString().lowercase()`.
- `isoDateToEpochDay`: `kotlinx.datetime.LocalDate.parse(iso).toEpochDays().toLong()`.
- **Trampa de `formatDayMonthYear`:** una fecha de negocio no tiene zona. Con la zona local, en México `1 ene` saldría `31 dic`. Crea el `NSDate` en `epochDay * 86400.0` y formatea con **UTC** (`NSTimeZone.timeZoneForSecondsFromGMT(0)`).
- `formatHourMinute`: `setDateFormat("HH:mm")` y zona local (`NSTimeZone.localTimeZone`).
- `formatShortTimeEsMx`: `setTimeStyle(NSDateFormatterShortStyle)`, `setDateStyle(NSDateFormatterNoStyle)`, `NSLocale(localeIdentifier = "es_MX")` y zona local.
- Idioma de la composición en iOS: `NSLocale.preferredLanguages.firstOrNull() as? String`, `NSLocale(localeIdentifier = tag)`. Si es `null`, usa `NSLocale.currentLocale`.
- Funciones `internal` que reciben `localeIdentifier` y zona, para `iosTest`.
- `PlatformBackHandler`: no hace nada, con el porqué en un comentario.

**Pruebas:**
- `commonTest/.../platform/PlatformDatesTest.kt`: `newUuid` casa con `^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$` y dos llamadas difieren; `isoDateToEpochDay("1970-01-01") == 0L`, `("2025-01-01") == 20089L`; una fecha inválida lanza; `todayEpochDay()` mayor que `20089L`.
- `androidHostTest`: la función pura de `d MMM uuuu` con `Locale.forLanguageTag("es-MX")` y `Locale.ENGLISH`, y `HH:mm` con una `ZoneId` fija. Afirma el texto exacto que hoy produce Java, leído de la corrida, no supuesto. `todayEpochDay()` igual a `LocalDate.now().toEpochDay()`.
- `iosTest/.../platform/PlatformDatesIosTest.kt` (no se puede correr aquí): `20089L` con `es_MX` contiene `2025` y empieza con `1 `; **con `NSTimeZone` local de México no cambia el día**, y por eso la función siempre usa UTC; `20089L` con `en_US` contiene `Jan`; `HH:mm` con zona GMT fija da el texto exacto; `formatShortTimeEsMx` no da cadena vacía.

## Commit 3: `NewSaleScreen`, `DashboardScreen`, `CustomerDetailSheet`

Mueve, mismo paquete: `transaction/NewSaleScreen.kt`, `dashboard/DashboardScreen.kt`, `customer/CustomerDetailSheet.kt`.

| Hoy | Queda |
|---|---|
| `UUID.randomUUID().toString()` | `newUuid()` |
| `LocalDate.now().toEpochDay()` | `todayEpochDay()` |
| `LocalDate.now().plusDays(it).toEpochDay()` (`NewSaleScreen.kt:409`) | `todayEpochDay() + it` |
| `DateFormat...format(Date(activity.occurredAtEpochMillis))` | `formatShortTimeEsMx(activity.occurredAtEpochMillis)` |
| `paymentBusinessDate` | `formatDayMonthYear(paymentDateEpochDay)` |
| `displayDate(dateIso)` | `formatDayMonthYear(isoDateToEpochDay(dateIso))` |
| hora en `CustomerDetailSheet.kt:432-434` | `formatHourMinute(createdAtEpochMillis)` |
| `BackHandler(onBack = onDismiss)` | `PlatformBackHandler(onBack = onDismiss)` |

- Conserva las funciones privadas (`paymentBusinessDate`, `displayDate`) y sus KDoc; solo cambia su cuerpo.
- `pluralStringResource` de `org.jetbrains.compose.resources` con `Res.plurals.*`. **`labelRes` privados de `Int`** pasan a `StringResource` o `PluralStringResource`, según su uso.
- Los que se consumen desde `:app` (`initialOf`, por ejemplo) se hacen públicos si el compilador lo pide. `CustomersScreen` seguirá en `app` en esta tanda.
- `@Composable` en todo lo que ahora llame a `formatDayMonthYear` o `formatHourMinute`. Si alguna llamada no estaba en contexto de composición, **dímelo y no la reacomodes por tu cuenta.**

## Lo que Native rechaza

Comas y `. ; : / \ < > [ ]` en nombres entre backticks, `@Test(expected=)`, `org.junit`, `String.format`, `measureTimeMillis`, `java.*` y `Charsets` en común. **Revisa también lo que moviste,** no solo lo que escribes: en la tanda anterior se me pasó una coma en un nombre de prueba.

## Fuente verificable de los símbolos

**No accedas a `C:/tmp/kn-prebuilt`.** Ya contrasté en `C:/tmp/ios-relevo/simbolos-f5f.txt`: `NSDateFormatter`, `setDateFormat`, `setLocale`, `setTimeZone`, `setTimeStyle`, `setDateStyle`, `stringFromDate`, `NSDateFormatterShortStyle`, `NSDateFormatterNoStyle`, `dateWithTimeIntervalSince1970`, `timeZoneForSecondsFromGMT`, `localTimeZone`, `NSLocale(localeIdentifier = ...)`, `preferredLanguages`, `NSUUID`, `UUIDString`. **En Kotlin/Native las propiedades de estas clases pueden verse como `val`: usa los `set...` de la lista.** Si necesitas otro símbolo, no lo adivines: anótalo en el reporte.

## Control negativo obligatorio, en Windows

Rompe, corre `:shared:testAndroidHostTest`, y **dime la prueba que falló, con su nombre, y por qué falló esa**. Una prueba que truena por otra razón no cuenta. Restaura y confirma con `git diff`.

1. `newUuid` en mayúsculas.
2. El patrón `d MMM uuuu` cambiado a `d/MM/uuuu`.
3. `isoDateToEpochDay` que devuelva un día de más.
4. `HH:mm` ignorando la `ZoneId` recibida.

## Puerta

Sin emulador ni pruebas instrumentadas. Con el entorno Norton de `CLAUDE.md` y `--no-daemon --no-configuration-cache --no-watch-fs --console=plain`:

```
.\gradlew.bat :shared:compileCommonMainKotlinMetadata :shared:testAndroidHostTest testDebugUnitTest assembleDebug :app:compileDebugAndroidTestKotlin
```

Al final, una sola vez: `assembleRelease lintDebug`. Si se queda sin memoria, dilo; no reintentes en bucle. Corre también las dos puertas de i18n y el conteo de claves de `CLAUDE.md`. **Si una puerta da coincidencias nuevas, repórtalas: no la amplíes.**

## Reporte

- **Rama y commit:** de `git branch --show-current` y `git rev-parse HEAD`, no de memoria.
- Conteos reales de los XML. La base es 802 + 757 = 1559: cuántas son nuevas.
- Salida de las tareas, archivos tocados y los **cuatro controles negativos**, con la prueba que falló.
- **Lista exacta de cada línea que no sea solo cambiar de recurso, de `internal` o de `import`.** La voy a auditar leyendo el diff.
- Qué perdió `internal`, símbolos no contrastados y cualquier cosa distinta a este encargo.

**No digas que iOS compila ni que estas pantallas funcionan en iOS: no puedes saberlo desde Windows.**
