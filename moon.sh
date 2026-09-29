#!/data/data/com.termux/files/usr/bin/bash
# Arranca el servidor de voz de Moon. Es seguro llamarlo varias veces.
# Lo lanza la app Moon (RUN_COMMAND) o tú a mano: ./moon.sh
cd "$(dirname "$0")" || exit 1
mkdir -p logs
termux-wake-lock 2>/dev/null
pgrep -f "python3.9 voz_servidor.py" >/dev/null && exit 0
setsid nohup python3.9 voz_servidor.py >> logs/servidor.log 2>&1 &
