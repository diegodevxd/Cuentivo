# Recorrido funcional de Cuentivo 1.3

**Objetivo:** validar el rediseño `codex/neo-fintech` antes de distribuirlo a testers, sin modificar Firebase ni Play Console de producción.

## Límites

- Proyecto Firebase existente: `organitazionlabs` (`600636825165`).
- App Android existente: `com.cuentivo.app`.
- El AVD debe usar Auth y Firestore Emulator Suite mediante `-Pcuentivo.useEmulators=true`.
- No modificar `OrganizationLabs`, `main`, `app/google-services.json`, reglas remotas ni Play Console.
- No limpiar ni modificar archivos fuente pendientes; solo se pueden limpiar datos de `com.cuentivo.app` en el AVD.
- Dinero: `Long` en centavos; no se valida una corrección financiera como correcta sin comprobar el libro mayor resultante.

## Investigación delegada reconciliada

- `exp-1` (`ses_039ee0273ffejyCyywTEoSNW1Z`): ubicó los controles, rutas UI y puntos observables de A-D; se usa para navegar sin suponer coordenadas.
- `exp-2` (`ses_039ee0253ffe68EQh35N6ixsAq`): confirmó AVD `Medium_Phone`, rutas de emuladores, flag `USE_FIREBASE_EMULATORS` y el flujo de cuenta ficticia; se usó para el arranque controlado.
- `exp-3` (`ses_039ee0245ffe66k07V9LY7Pm4J`): identificó cobertura estática y los comportamientos que requieren evidencia manual; se convirtieron en los criterios de aprobación de este documento.
- `exp-4` (`ses_039e74c05ffeXZ624Tpy31KS7D`): confirmó que ambos repositorios comparten `com.cuentivo.app`, Firebase, reglas, Auth y Billing; el rediseño solo cambia a versión `7/1.3`.
- `lib-1` (`ses_039e616d7ffeChrvmgK1U5t3qN`): contrastó `10.0.2.2` y `useEmulator` previo a Auth/Firestore con la documentación oficial; esos requisitos quedaron verificados en el BuildConfig instalado.

## Ejecución

1. Registrar estado del repositorio, AVD, CLI Firebase, proyecto activo y edición Firestore.
2. Arrancar Auth/Firestore Emulator Suite para `organitazionlabs`.
3. Instalar el debug actual con `USE_FIREBASE_EMULATORS=true`.
4. Crear cuenta y negocio ficticios locales.
5. Ejecutar y evidenciar los cuatro escenarios del punto 1 de `HANDOFF-CODEX.md`.
6. Reproducir los tres defectos adicionales del estado de cuenta:
   - abonos iguales indistinguibles,
   - saldo negativo presentado como deuda,
   - cancelación parcial sin explicación suficiente.
7. Clasificar resultados por `PASS`, `FAIL` o `BLOCKED`; no modificar código durante la validación.
8. Si hay fallos, abrir carriles separados para lógica financiera, diseño de detalle de cliente e i18n, con pruebas antes de integrar.

## Criterios de aprobación del recorrido

- Venta de $5,000 sobre cupo de $3,000 advierte pero se registra.
- Tras abono de $2,000 y su cancelación: vendido $5,000, abonado $0, debe $5,000; el abono queda tachado, dice `Abono cancelado` y el saldo corre $5,000 → $3,000 → $5,000.
- English persiste tras `force-stop` y los importes siguen con formato mexicano.
- Un cliente sin cupo no recibe sugerencia automática aunque tenga buen historial.
- Los defectos adicionales se documentan con evidencia antes de corregirlos.
