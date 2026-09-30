# -*- coding: utf-8 -*-
"""Solo lectura. Comprueba que palabras de Moon (comandos, verbos, apps) existen en el diccionario de Vosk.

Uso (desde la carpeta del proyecto), con el mismo python que usa el servidor de voz (el que tiene vosk):
    python3.9 chequear_vocabulario.py                 # usa el modelo de voz_servidor.py
    python3.9 chequear_vocabulario.py RUTA_DEL_MODELO # otro modelo
    python3.9 chequear_vocabulario.py --words RUTA/words.txt   # si tienes la lista de palabras aparte
Si el modelo trae graph/words.txt lo lee; los modelos pequenos no lo traen, asi que pregunta a Vosk
palabra por palabra (vosk_model_find_word). No modifica nada: solo lee comandos.json y apps_app.json.
"""
import json, os, re, sys, unicodedata

DIR = os.path.dirname(os.path.abspath(__file__))
if not os.path.exists(os.path.join(DIR, "comandos.json")):
    DIR = os.getcwd()   # por si el script esta en otra carpeta: usa la carpeta actual
MODELO = "/storage/emulated/0/Download/ProyectosTermux/Models/vosk-model-small-es-0.42"
VERBOS = ["abre", "abrir", "lanza", "inicia", "ejecuta", "cierra", "cerrar", "termina", "ahora", "llama",
          "llamar", "manda", "envia", "mensaje", "si", "no", "cancela", "hola", "luna"]


def sin_acentos(t):
    t = unicodedata.normalize("NFKD", t)
    return "".join(c for c in t if not unicodedata.combining(c))


def limpia(t):
    """minusculas conservando acentos (Vosk escribe 'cámara'), sin signos."""
    return [w for w in re.sub(r"[^\w ]+", " ", (t or "").lower().replace("\xa0", " ")).replace("_", " ").split() if w]


def cargar_palabras(args):
    """Devuelve (existe(palabra)->bool, descripcion, total o None)."""
    if "--words" in args:
        ruta = args[args.index("--words") + 1]
        modelo = None
    else:
        modelo = next((a for a in args if not a.startswith("--")), MODELO)
        ruta = os.path.join(modelo, "graph", "words.txt")
    if os.path.exists(ruta):
        pal = set()
        with open(ruta, encoding="utf-8", errors="replace") as f:
            for ln in f:
                p = ln.split()
                if p and not p[0].startswith("<"):
                    pal.add(p[0].lower())
        return (lambda w: w in pal), ruta, len(pal)
    if modelo is None or not os.path.isdir(modelo):
        sys.exit("No encuentro el modelo ni la lista de palabras: %s" % (modelo or ruta))
    try:
        from vosk import Model, SetLogLevel
    except ImportError:
        sys.exit("Este modelo no trae words.txt y no puedo importar vosk con este python.\n"
                 "Ejecuta el script con el mismo python del servidor de voz (p. ej. python3.9).")
    SetLogLevel(-1)
    print("Cargando el modelo (unos segundos)...", flush=True)
    m = Model(modelo)
    return (lambda w: m.vosk_model_find_word(w) > 0), "modelo %s (consulta a Vosk)" % os.path.basename(modelo), None


def main():
    existe, ruta, total = cargar_palabras(sys.argv[1:])
    print("Diccionario: %s%s\n" % (ruta, " (%d palabras)" % total if total else ""))

    def estado(w):
        if existe(w):
            return "ok", w
        s = sin_acentos(w)
        if s != w and existe(s):
            return "acento", s
        return "falta", None

    def informe(titulo, palabras):
        palabras = sorted(set(palabras))
        ok, ac, fa = [], [], []
        for w in palabras:
            e, real = estado(w)
            (ok if e == "ok" else ac if e == "acento" else fa).append((w, real))
        print("== %s: %d palabras -> %d en el diccionario, %d con otro acento, %d faltan" % (
            titulo, len(palabras), len(ok), len(ac), len(fa)))
        if ac:
            print("   otro acento:", ", ".join("%s -> %s" % (w, r) for w, r in ac))
        if fa:
            print("   FALTAN:", ", ".join(w for w, _ in fa))
        print()
        return {w for w, _ in fa}

    cm = json.load(open(os.path.join(DIR, "comandos.json"), encoding="utf-8"))["comandos"]
    informe("Comandos (comandos.json)", [w.rstrip("*") for c in cm for f in c["frases"] for w in limpia(f.replace("*", " "))])
    informe("Verbos y respuestas", VERBOS)

    apps = json.load(open(os.path.join(DIR, "apps_app.json"), encoding="utf-8"))["apps"]
    faltan = {}
    todas = set()
    for a in apps:
        ws = limpia(a["n"])
        todas.update(ws)
        f = [w for w in ws if estado(w)[0] == "falta"]
        faltan[a["n"]] = (len(ws), f)
    falta_pal = informe("Palabras de nombres de apps (%d apps)" % len(apps), todas)

    comp = [n for n, (t, f) in faltan.items() if not f]
    parc = [n for n, (t, f) in faltan.items() if f and len(f) < t]
    nada = [n for n, (t, f) in faltan.items() if f and len(f) == t]
    print("== Apps que se podrian decir con vocabulario limitado")
    print("   completas (%d):" % len(comp), ", ".join(sorted(comp)))
    print("   a medias, alguna palabra falta (%d):" % len(parc), ", ".join(sorted(parc)))
    print("   ninguna palabra existe (%d):" % len(nada), ", ".join(sorted(nada)))
    print("\nLas que faltan se resolverian con alias: la forma que Vosk si conoce (p. ej. 'de negra') -> la app.")


if __name__ == "__main__":
    main()
