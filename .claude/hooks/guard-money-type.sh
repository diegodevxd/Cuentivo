#!/usr/bin/env bash
# PostToolUse hook: vigila la regla 1 del proyecto — el dinero siempre es Long en centavos.
#
# Por qué existe: un Double en una ruta de dinero COMPILA LIMPIO y pasa las pruebas que no
# lo buscan. No se manifiesta como un error, sino como centavos que se pierden al redondear,
# meses después, en la cuenta de alguien. Es exactamente el defecto que ninguna suite atrapa
# por accidente. Hoy el repo tiene cero ocurrencias; esto es para que siga así.
#
# La única conversión legítima a decimal está en la frontera de UI (CuentivoApp.kt), con
# BigDecimal.valueOf(cents, 2). BigDecimal no es Double y no dispara esto.
#
# Informa, no bloquea: puede haber un falso positivo legítimo (una animación cuyo nombre de
# variable contenga "total", por ejemplo), y bloquear ahí entrenaría a ignorarlo.

set -uo pipefail

payload=$(cat 2>/dev/null || true)

path=$(printf '%s' "$payload" | grep -oE '"file_path"[[:space:]]*:[[:space:]]*"[^"]*"' | head -1 | sed -E 's/.*"([^"]*)"$/\1/')
[ -z "$path" ] && exit 0

# Sólo Kotlin de producción. Las pruebas quedan fuera.
case "$path" in
  *.kt) ;;
  *) exit 0 ;;
esac
case "$path" in
  *[/\\]test[/\\]*|*[/\\]androidTest[/\\]*) exit 0 ;;
esac

# Ruta relativa al repo, por si llega absoluta.
rel="${path#*feature-cuentivo-ai/}"
file="$rel"
[ -f "$file" ] || file="$path"
[ -f "$file" ] || exit 0

MONEY='cents|amount|price|total|balance|saldo|monto|precio|payment|abono|cargo|charge|discount|descuento'

hits=$(grep -nE '\b(Double|Float|toDouble|toFloat)\b' "$file" 2>/dev/null | grep -iE "$MONEY" || true)

[ -z "$hits" ] && exit 0

echo "REGLA 1 - DINERO EN ENTEROS: se detecto un tipo decimal en una linea con vocabulario de dinero en $file"
echo "$hits" | sed 's/^/  /'
echo "El dinero es SIEMPRE Long en centavos. Nunca Double ni Float: un redondeo aqui no rompe el build,"
echo "se publica y aparece como centavos perdidos en la cuenta de alguien meses despues."
echo "La unica conversion a decimal permitida vive en la frontera de UI, con BigDecimal.valueOf(cents, 2)."
echo "Si es un falso positivo (una variable de animacion que se llama 'total', por ejemplo), ignoralo y sigue."

exit 0
