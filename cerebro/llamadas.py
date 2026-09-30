# -*- coding: utf-8 -*-
"""Llamadas por voz: «llama a Daniel» -> busca el contacto por parecido de SONIDO, pregunta y marca.

La app Moon manda solo nombres e ids (POST /contactos -> contactos.json). El número nunca sale del móvil:
el servidor le pide a la app que llame al contacto por su id y es la app quien busca el número y marca.
Por defecto siempre pregunta «¿Llamo a …?» (ajuste `confirmar_llamadas`). Si hay varios parecidos los
propone uno a uno. Los apodos se guardan solos como alias cuando dices que sí a un parecido.
"""
import difflib
import json
import os
import re

from . import config as cfg
from .util import norm, log_evento, leer_ajustes

NOMBRES = {"llamar": "Llamar a un contacto"}

# Palabras que no sirven para reconocer a alguien (prefijos de agenda, artículos...).
RELLENO = {"de", "del", "la", "el", "los", "las", "mi", "mis", "su", "sus", "por", "al", "en", "con", "y", "un",
           "una", "que", "frente", "h", "ja", "di", "c", "cav", "gpn", "dota", "jc", "pre"}
VERBOS_LLAMAR = {"llama", "llamar", "llamale", "marca", "marcar", "telefonea"}
ANTES_DEL_VERBO = {"por", "favor", "oye", "luna", "moon", "puedes", "podrias", "quiero", "ahora"}


def palabras(nombre):
    """Palabras útiles de un nombre de contacto (sin prefijos, sin emojis ni números, sin «de/del/la»)."""
    nombre = re.sub(r"(?<=[a-záéíóúñ])(?=[A-ZÁÉÍÓÚÑ])", " ", nombre or "")       # AlejandroMa -> Alejandro Ma
    return [p for p in re.findall(r"[a-z]+", norm(nombre)) if p not in RELLENO and len(p) > 1]


def sonido(p):
    """Forma fonética aproximada en español: b/v, h muda, ll/y, c/k/qu, z/s, g/j, ch."""
    s = p.replace("ch", "X")
    s = re.sub(r"c([ei])", r"s\1", s)
    s = s.replace("c", "k").replace("qu", "k").replace("q", "k")
    s = re.sub(r"gu([ei])", r"G\1", s)
    s = re.sub(r"g([ei])", r"j\1", s)
    s = s.replace("G", "g").replace("h", "")
    s = s.replace("ll", "y").replace("v", "b").replace("z", "s").replace("w", "u").replace("x", "ks")
    return re.sub(r"(.)\1+", r"\1", s)


def _leer_json(nombre, defecto):
    try:
        with open(os.path.join(cfg.DIR, nombre), encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return defecto


def _escribir_json(nombre, datos):
    ruta = os.path.join(cfg.DIR, nombre)
    try:
        with open(ruta + ".tmp", "w", encoding="utf-8") as f:
            json.dump(datos, f, ensure_ascii=False, indent=1)
        os.replace(ruta + ".tmp", ruta)
        return True
    except OSError:
        return False


class LlamadasMixin:
    def _acciones_llamadas(self):
        return {"llamar": self._a_llamar}

    # ---------- entender la frase ----------
    @staticmethod
    def _regla_llamada(tn):
        """'llama a X' / 'llamar al X' / 'marca a X' sin necesitar el modelo. Devuelve el texto del contacto o None."""
        toks = tn.split()
        for i, t in enumerate(toks[:4]):
            if t in VERBOS_LLAMAR and all(x in ANTES_DEL_VERBO for x in toks[:i]):
                resto = toks[i + 1:]
                while resto and resto[0] in ("a", "al"):
                    resto = resto[1:]
                if resto and len(resto) <= 5:
                    return " ".join(resto)
                return None
        return None

    # ---------- datos ----------
    def _contactos(self):
        """Contactos que envió la app: [{"i", "n", "p" (palabras), "s" (sonidos)}]. Se recarga si el archivo cambia."""
        ruta = os.path.join(cfg.DIR, "contactos.json")
        try:
            m = os.stat(ruta).st_mtime_ns
        except OSError:
            return []
        cache = getattr(self, "_cont_cache", None)
        if cache is None or cache[0] != m:
            datos = []
            try:
                for x in _leer_json("contactos.json", {})["contactos"]:
                    ps = palabras(str(x["n"]))
                    if ps:
                        datos.append({"i": str(x["i"]), "n": str(x["n"]), "p": ps, "s": [sonido(p) for p in ps]})
            except (KeyError, TypeError):
                datos = []
            cache = self._cont_cache = (m, datos)
        return cache[1]

    @staticmethod
    def _clave_contacto(texto):
        toks = norm(texto).split()
        while toks and toks[0] in ("a", "al"):
            toks = toks[1:]
        return " ".join(toks)

    def buscar_contactos(self, texto):
        """Hasta MAX_CANDIDATOS contactos, del más al menos parecido: [{"i", "n", "p", "score", ...}]."""
        datos = self._contactos()
        qn = self._clave_contacto(texto)
        if not datos or not qn:
            return []
        alias = _leer_json("contactos_alias.json", {})
        cid = alias.get(qn)
        if not cid:
            m = difflib.get_close_matches(qn, list(alias), n=1, cutoff=0.85)
            cid = alias[m[0]] if m else None
        if cid:
            for c in datos:
                if c["i"] == str(cid):
                    return [dict(c, score=1.0, via="alias")]
        qs = [sonido(w) for w in qn.split() if w not in RELLENO and len(w) > 1]
        if not qs:
            return []
        uso = _leer_json("contactos_uso.json", {})
        pts = []
        for c in datos:
            sc = sum(max(difflib.SequenceMatcher(None, s, cs).ratio() for cs in c["s"]) for s in qs) / len(qs)
            ajustado = sc - 0.01 * max(0, len(c["s"]) - len(qs)) + 0.004 * min(int(uso.get(c["i"], 0)), 10)
            pts.append((ajustado, sc, c))
        pts.sort(key=lambda x: -x[0])
        if pts[0][1] < cfg.UMBRAL_CONTACTO:
            return []
        out = []
        for ajustado, sc, c in pts:
            if sc >= cfg.UMBRAL_CONTACTO and ajustado >= pts[0][0] - 0.08:
                out.append(dict(c, score=round(sc, 2), via="sonido"))
            if len(out) >= cfg.MAX_CANDIDATOS:
                break
        return out

    @staticmethod
    def _nombre_corto(c):
        return " ".join(p.capitalize() for p in c["p"][:2])

    def _guardar_alias_contacto(self, texto, cid):
        clave = self._clave_contacto(texto)
        if len(clave) < 2 or len(clave) > 40:
            return
        alias = _leer_json("contactos_alias.json", {})
        if alias.get(clave) != cid:
            alias[clave] = cid
            if _escribir_json("contactos_alias.json", alias):
                log_evento({"tipo": "alias_contacto", "alias": clave, "contacto_id": cid})

    def _sumar_uso(self, cid):
        uso = _leer_json("contactos_uso.json", {})
        uso[cid] = int(uso.get(cid, 0)) + 1
        _escribir_json("contactos_uso.json", uso)

    # ---------- la acción ----------
    def _a_llamar(self, a):
        q = (a.get("contacto") or "").strip()
        if not q:
            return "¿A quién llamo?"
        if not self._contactos():
            return "Todavía no tengo tus contactos. Abre la app Moon para sincronizarlos"
        cands = self.buscar_contactos(q)
        if not cands:
            return "No encontré a %s en tus contactos" % q
        if (not leer_ajustes().get("confirmar_llamadas", True)) and len(cands) == 1 \
                and cands[0]["score"] >= cfg.UMBRAL_DIRECTO:
            return self._marcar(cands[0], q, True)
        return self._proponer(q, cands, 0)

    def _proponer(self, q, cands, k):
        """Pregunta por el candidato k; si dice que no, pasa al siguiente."""
        if k >= len(cands):
            return "Está bien, no llamo a nadie"
        c = cands[k]
        nombre = self._nombre_corto(c)
        # `extra` le dice a la app qué tarjeta dibujar. Solo id y nombre: el número lo busca la app en tu móvil.
        self.preguntar("¿Llamo a %s?" % nombre,
                       lambda: self._marcar(c, q, len(cands) == 1),     # alias solo si no había duda entre varios
                       lambda: self._proponer(q, cands, k + 1),
                       extra={"tipo": "llamar", "cid": c["i"], "nombre": nombre, "k": k + 1, "n": len(cands)})
        return None

    def _marcar(self, c, q, aprender=False):
        nombre = self._nombre_corto(c)
        r = self._pedir_app({"tipo": "llamar", "cid": c["i"], "nombre": nombre}, cfg.ESPERA_ORDEN_APP + 1)
        if r is None:
            log_evento({"tipo": "llamada", "ok": False, "motivo": "app_no_disponible", "contacto_id": c["i"]})
            return "La app Moon no está abierta, no puedo marcar"
        if not r["ok"]:
            log_evento({"tipo": "llamada", "ok": False, "motivo": "app_no_pudo", "contacto_id": c["i"]})
            return "No pude llamar a %s. Revisa el permiso de llamadas en Moon" % nombre
        self._sumar_uso(c["i"])
        if aprender and c.get("via") != "alias" and c.get("score", 1.0) < 0.95:
            self._guardar_alias_contacto(q, c["i"])
        log_evento({"tipo": "llamada", "ok": True, "contacto_id": c["i"], "score": c.get("score")})
        return "Llamando a %s" % nombre
