import subprocess, json, sys, time, os
from vosk import Model, KaldiRecognizer, SetLogLevel
SetLogLevel(-1)
MODEL = "/storage/emulated/0/Download/ProyectosTermux/Models/vosk-model-small-es-0.42"
DIR = os.path.expanduser("~/ProyectosTermux/netguard")
FRASES = ["reiniciar", "reiniciar la conexión", "[unk]"]
TEST = "--test" in sys.argv
rec = KaldiRecognizer(Model(MODEL), 16000, json.dumps(FRASES, ensure_ascii=False))
p = subprocess.Popen(["parec", "--device=OpenSL_ES_source", "--rate=16000",
    "--channels=1", "--format=s16le", "--latency-msec=100"], stdout=subprocess.PIPE)
ultimo = 0
print("Escuchando... Ctrl+C para salir", flush=True)
while True:
    data = p.stdout.read(4000)
    if not data:
        break
    if rec.AcceptWaveform(data):
        t = json.loads(rec.Result()).get("text", "")
        if t:
            print("oido:", t, flush=True)
        if "reinici" in t and time.time() - ultimo > 60:
            ultimo = time.time()
            print(">> ORDEN", flush=True)
            if not TEST:
                subprocess.Popen([DIR + "/reiniciar.sh"])
