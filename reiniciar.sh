#!/data/data/com.termux/files/usr/bin/bash
source "$(dirname "$0")/lib.sh"
[ -d "$DIR/.lock" ] && [ $(( $(date +%s) - $(stat -c %Y "$DIR/.lock") )) -gt 120 ] && rmdir "$DIR/.lock"
mkdir "$DIR/.lock" 2>/dev/null || { log "ya hay un reinicio en curso"; exit 1; }
trap 'rmdir "$DIR/.lock"' EXIT
log "== inicio reinicio =="
sh_rish "cmd connectivity airplane-mode enable; sleep $PAUSA_AVION; cmd connectivity airplane-mode disable; sleep $PAUSA_RED; service call tethering 4 null s16 random"
log "== fin =="
