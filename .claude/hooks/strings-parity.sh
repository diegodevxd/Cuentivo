#!/usr/bin/env bash
# PostToolUse hook: vigila la paridad de textos ES/EN de Cuentivo.
#
# Por qué existe: un desajuste de claves entre values/ y values-en/ NO rompe el build.
# Se publica, y aparece como texto en blanco en el idioma que nadie probó. Contar totales
# tampoco basta: dos archivos pueden empatar en número y tener claves distintas.
#
# Diseño deliberado: informa, no bloquea. Sale con 0 siempre. Mientras se agrega una clave
# en español y todavía no en inglés, el desajuste es legítimo y transitorio; un hook que
# bloqueara ahí se volvería ruido que se aprende a ignorar. Sólo habla cuando hay deriva.

set -uo pipefail

ES="shared/src/commonMain/composeResources/values/strings.xml"
EN="shared/src/commonMain/composeResources/values-en/strings.xml"

payload=$(cat 2>/dev/null || true)

# Sólo actúa cuando el archivo editado es uno de los dos strings.xml. El matcher de hooks
# filtra por herramienta, no por ruta, así que el filtro de ruta vive aquí.
case "$payload" in
  *composeResources/values/strings.xml*|*composeResources/values-en/strings.xml*|*'composeResources\values\strings.xml'*|*'composeResources\values-en\strings.xml'*) ;;
  *) exit 0 ;;
esac

[ -f "$ES" ] && [ -f "$EN" ] || exit 0

keys_of() { grep -oE '<string name="[^"]+"' "$1" | sed -E 's/.*name="([^"]+)".*/\1/' | sort; }

solo_es=$(comm -23 <(keys_of "$ES") <(keys_of "$EN"))
solo_en=$(comm -13 <(keys_of "$ES") <(keys_of "$EN"))

# Silencio cuando todo cuadra: el hook sólo debe hablar si hay algo que decir.
[ -z "$solo_es" ] && [ -z "$solo_en" ] && exit 0

n_es=$(keys_of "$ES" | wc -l | tr -d ' ')
n_en=$(keys_of "$EN" | wc -l | tr -d ' ')

echo "PARIDAD DE TEXTOS: hay claves sin contraparte (ES=$n_es, EN=$n_en)."
[ -n "$solo_es" ] && { echo "Sólo en español, falta traducir:"; echo "$solo_es" | sed 's/^/  - /'; }
[ -n "$solo_en" ] && { echo "Sólo en inglés, falta el original:"; echo "$solo_en" | sed 's/^/  - /'; }
echo "Si estás a media edición, termina el otro idioma antes de cerrar la tarea."
echo "Recuerda: escribe sin diagonales de escape: \"No se pudo\", \"y no pudo\". Android las recibe puestas por el generador."

exit 0
