source "$(dirname "$0")/config.sh"
mkdir -p "$DIR/logs"
log() { echo "$(date '+%F %T') $*" | tee -a "$DIR/logs/netguard.log"; }
sh_rish() { (cd "$HOME" && ./rish -c "$1"); }
avion_on()   { log "avion ON";  sh_rish "cmd connectivity airplane-mode enable"; }
avion_off()  { log "avion OFF"; sh_rish "cmd connectivity airplane-mode disable"; }
hotspot_on() { log "hotspot ON"; sh_rish "service call tethering 4 null s16 random"; }
