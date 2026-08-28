#!/usr/bin/env bash
# PreToolUse hook: impide que el CONTENIDO de un secreto llegue a la conversación.
#
# Por qué existe: keystore.properties, la llave de firma, google-services.json y las claves
# de cuenta de servicio están en .gitignore, pero .gitignore sólo evita comitearlos. No evita
# que un agente los lea y los pegue en un reporte, en un log o en un mensaje a otra terminal.
# Una instrucción en prosa ("no imprimas el keystore") es una petición; esto es un control.
#
# Deliberadamente NO bloquea comprobar que un archivo existe. `ls keystore.properties` o
# `test -f` son legítimos y se usan a diario; lo que se bloquea es volcar el contenido.
#
# Regla 4 del proyecto: nada de PII, saldos ni tokens en logs, Analytics o Crashlytics.

set -uo pipefail

payload=$(cat 2>/dev/null || true)

# Nombres cuyo contenido nunca debe entrar a la conversación.
SECRETS='keystore\.properties|\.jks|\.keystore|google-services\.json|serviceAccount|service-account|\.p12|\.pem|CUENTIVOAI_[A-Z_]*KEY'

# Comandos que vuelcan contenido. `ls`, `stat` y `test` quedan fuera a propósito.
DUMPERS='cat |head |tail |less |more |strings |xxd |od |base64 |Get-Content|gc |type |awk |sed |grep |rg |Select-String'

tool=$(printf '%s' "$payload" | grep -oE '"tool_name"[[:space:]]*:[[:space:]]*"[^"]*"' | head -1 | sed -E 's/.*"([^"]*)"$/\1/')

deny() {
  echo "BLOQUEADO por guard-secrets: $1" >&2
  echo "Ese archivo contiene credenciales. Su contenido no debe entrar a la conversacion, a un log, a un reporte ni a un commit." >&2
  echo "Si solo necesitas saber si existe, usa 'ls' o 'test -f': no estan bloqueados." >&2
  echo "Si de verdad hace falta un valor de ahi, pideselo a Diego; no lo leas tu." >&2
  exit 2
}

case "$tool" in
  Read|Edit|Write|NotebookEdit)
    path=$(printf '%s' "$payload" | grep -oE '"file_path"[[:space:]]*:[[:space:]]*"[^"]*"' | head -1 | sed -E 's/.*"([^"]*)"$/\1/')
    [ -z "$path" ] && exit 0
    printf '%s' "$path" | grep -qE "$SECRETS" && deny "$path"
    ;;
  Bash|PowerShell)
    cmd=$(printf '%s' "$payload" | grep -oE '"command"[[:space:]]*:[[:space:]]*"([^"\\]|\\.)*"' | head -1)
    [ -z "$cmd" ] && exit 0
    if printf '%s' "$cmd" | grep -qE "$SECRETS"; then
      printf '%s' "$cmd" | grep -qE "$DUMPERS" && deny "el comando volcaria el contenido de un secreto"
    fi
    ;;
esac

exit 0
