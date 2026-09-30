#!/usr/bin/env python3
"""Mide cuántos nombres de contactos puede llegar a oír Vosk (100% offline).

Uso:  python3 chequear_contactos.py [contactos.json | nombres.txt] [carpeta_del_modelo]
Sin argumentos lee contactos.json, el archivo que guarda el servidor cuando la app Moon sincroniza tus contactos.
Con un .txt lee un nombre por línea, separa las palabras que importan (sin prefijos tipo «H -», sin «de/del/la»,
sin emojis ni números) y comprueba cuáles existen en el vocabulario del modelo de voz (words.txt si lo trae; si no, se lo pregunta a Vosk).
Una palabra que no está en el vocabulario Vosk nunca la va a escribir bien: hay que llegar a ella
por parecido de sonido o por un alias.
"""
import json, os, re, sys, unicodedata

MODELO = "/storage/emulated/0/Download/ProyectosTermux/Models/vosk-model-small-es-0.42"
RELLENO = {"de", "del", "la", "el", "los", "las", "mi", "mis", "su", "sus", "por", "al", "en", "con", "y", "un", "una",
           "que", "frente", "h", "ja", "di", "c", "cav", "gpn", "dota", "jc", "pre"}


def limpio(t):
    t = unicodedata.normalize("NFD", t.lower())
    return "".join(c for c in t if unicodedata.category(c) != "Mn")


def palabras(nombre):
    nombre = re.sub(r"(?<=[a-záéíóúñ])(?=[A-ZÁÉÍÓÚÑ])", " ", nombre)      # AlejandroMa -> Alejandro Ma
    return [p for p in re.findall(r"[a-zñ]+", limpio(nombre)) if p not in RELLENO and len(p) > 1]


def variantes(p):
    """La palabra tal cual y con una vocal acentuada (el vocabulario de Vosk lleva tildes y ñ: dario -> darío)."""
    out = [p]
    for k, c in enumerate(p):
        if c in "aeiou":
            out.append(p[:k] + "áéíóú"["aeiou".index(c)] + p[k + 1:])
        elif c == "n":
            out.append(p[:k] + "ñ" + p[k + 1:])          # cunado -> cuñado
    return out


def buscador(carpeta):
    """Devuelve f(palabra) -> bool. Usa words.txt si el modelo lo trae; si no (modelos small), pregunta al propio Vosk."""
    for raiz, _, archivos in os.walk(carpeta):
        if "words.txt" in archivos:
            with open(os.path.join(raiz, "words.txt"), encoding="utf-8", errors="ignore") as f:
                voc = {limpio(l.split()[0]) for l in f if l.strip()}
            return lambda p: p in voc
    from vosk import Model, SetLogLevel
    SetLogLevel(-1)
    m = Model(carpeta)
    if not hasattr(m, "vosk_model_find_word"):
        sys.exit("Esta versión de vosk no puede consultar el vocabulario (pkg/pip: actualiza vosk).")
    return lambda p: any(m.vosk_model_find_word(v) >= 0 for v in variantes(p))


def leer_nombres(ruta):
    """Nombres de un contactos.json ({"contactos": [{"i", "n"}]}) o de un .txt con un nombre por línea."""
    if ruta.endswith(".json"):
        with open(ruta, encoding="utf-8") as f:
            return [str(x["n"]).strip() for x in json.load(f)["contactos"] if str(x.get("n", "")).strip()]
    return [l.strip() for l in open(ruta, encoding="utf-8", errors="ignore") if l.strip()]


def main():
    ruta = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(os.path.abspath(__file__)), "contactos.json")
    if not os.path.exists(ruta):
        sys.exit("No encuentro %s. Abre la app Moon (Sistema > Contactos y llamadas > Sincronizar) o pasa un .txt.\n\n%s" % (ruta, __doc__))
    nombres = leer_nombres(ruta)
    esta = buscador(sys.argv[2] if len(sys.argv) > 2 else MODELO)
    total = dentro = 0
    sin_alcance = []
    for n in nombres:
        ps = palabras(n)
        ok = [p for p in ps if esta(p)]
        total += len(ps); dentro += len(ok)
        if ps and not ok:
            sin_alcance.append(n)
    print("Contactos: %d | palabras útiles: %d | dentro del vocabulario: %d (%.0f%%)"
          % (len(nombres), total, dentro, 100.0 * dentro / max(total, 1)))
    print("\nContactos SIN ninguna palabra que Vosk conozca (necesitan alias o parecido de sonido): %d" % len(sin_alcance))
    for n in sin_alcance:
        print("  -", n)


if __name__ == "__main__":
    main()
