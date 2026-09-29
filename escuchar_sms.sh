#!/data/data/com.termux/files/usr/bin/bash
source "$(dirname "$0")/lib.sh"
ULT=$(termux-sms-list -t inbox -l 1 | jq -r '.[0]._id // 0')
log "escuchando SMS (ultimo id $ULT)"
while true; do
  sleep $INTERVALO_SMS
  MSGS=$(termux-sms-list -t inbox -l 5) || continue
  MAX=$(echo "$MSGS" | jq -r 'map(._id) | max // 0')
  echo "$MSGS" | jq -c --argjson u "$ULT" '.[] | select(._id > $u)' | while read -r m; do
    num=$(echo "$m" | jq -r .number)
    body=$(echo "$m" | jq -r .body | tr 'A-Z' 'a-z')
    if [ "${num: -9}" = "${NUMERO: -9}" ] && [[ "$body" == *"$(echo "$PALABRA" | tr A-Z a-z)"* ]]; then
      log "orden recibida de $num"
      "$DIR/reiniciar.sh" &
    fi
  done
  [ "$MAX" -gt "$ULT" ] && ULT=$MAX
done
