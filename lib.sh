source "$(dirname "$0")/config.sh"
mkdir -p "$DIR/logs"
# historial de Actividad: misma carpeta que usa voz_servidor.py (Download/MoonLogs; si no se puede, logs/)
LOGS_MOON="${MOON_LOGDIR:-/storage/emulated/0/Download/MoonLogs}"
mkdir -p "$LOGS_MOON" 2>/dev/null || LOGS_MOON="$DIR/logs"
ACT_LOG="$LOGS_MOON/actividad.jsonl"
log() { echo "$(date '+%F %T') $*" | tee -a "$DIR/logs/netguard.log"; }
sh_rish() { (cd "$HOME" && ./rish -c "$1"); }
avion_on()   { log "avion ON";  sh_rish "cmd connectivity airplane-mode enable"; }
avion_off()  { log "avion OFF"; sh_rish "cmd connectivity airplane-mode disable"; }
hotspot_on() { log "hotspot ON"; sh_rish "service call tethering 4 null s16 random"; }
