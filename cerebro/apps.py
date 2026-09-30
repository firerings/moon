# -*- coding: utf-8 -*-
"""Abrir y cerrar apps: catálogo, alias, resolución por nombre y confirmación de la app Moon."""
import difflib
import json
import os
import threading
import time

from . import config as cfg
from .util import norm, log_evento

# Apps: nombre hablado -> paquetes posibles (se usa el primero instalado).
# Se puede ampliar sin tocar codigo con apps_alias.json ({"nombre": ["paquete", ...]}).
ALIAS_BASE = {
    "camara": ["com.android.camera", "com.android.camera2", "com.google.android.GoogleCamera"],
    "galeria": ["com.miui.gallery", "com.google.android.apps.photos", "com.android.gallery3d"],
    "fotos": ["com.google.android.apps.photos", "com.miui.gallery"],
    "ajustes": ["com.android.settings"],
    "configuracion": ["com.android.settings"],
    "calculadora": ["com.miui.calculator", "com.google.android.calculator", "com.android.calculator2"],
    "reloj": ["com.android.deskclock", "com.google.android.deskclock"],
    "alarma": ["com.android.deskclock", "com.google.android.deskclock"],
    "contactos": ["com.android.contacts", "com.google.android.contacts"],
    "telefono": ["com.android.contacts", "com.google.android.dialer", "com.android.dialer"],
    "mensajes": ["com.google.android.apps.messaging", "com.android.mms"],
    "sms": ["com.google.android.apps.messaging", "com.android.mms"],
    "chrome": ["com.android.chrome"],
    "navegador": ["com.android.chrome", "com.mi.globalbrowser", "com.android.browser"],
    "whatsapp": ["com.whatsapp"],
    "youtube": ["com.google.android.youtube"],
    "telegram": ["org.telegram.messenger"],
    "facebook": ["com.facebook.katana"],
    "instagram": ["com.instagram.android"],
    "tiktok": ["com.zhiliaoapp.musically", "com.ss.android.ugc.trill"],
    "spotify": ["com.spotify.music"],
    "netflix": ["com.netflix.mediaclient"],
    "mapas": ["com.google.android.apps.maps"],
    "maps": ["com.google.android.apps.maps"],
    "gmail": ["com.google.android.gm"],
    "correo": ["com.google.android.gm"],
    "drive": ["com.google.android.apps.docs"],
    "play store": ["com.android.vending"],
    "tienda": ["com.android.vending"],
    "archivos": ["com.mi.android.globalFileexplorer", "com.google.android.documentsui", "com.android.fileexplorer"],
    "termux": ["com.termux"],
    "moon": ["com.exclusivo.moon"],
    "shizuku": ["moe.shizuku.privileged.api"],
}
RELLENO = {"el", "la", "los", "las", "mi", "mis", "un", "una", "app", "aplicacion",
           "de", "del", "por", "favor"}
GENERICOS = {"com", "org", "net", "android", "google", "apps", "app", "apk", "mobile", "www",
             "io", "me", "co", "de", "es", "mi", "miui", "xiaomi", "samsung", "system",
             "service", "services", "provider", "providers", "client"}

VERBOS_ABRIR = {"abre", "abrir", "abri", "abra", "abrime", "abrelo", "abrela", "lanza", "lanzar",
                "inicia", "iniciar", "ejecuta", "ejecutar", "ahora"}   # "ahora": Vosk oye asi "abre"
VERBOS_CERRAR = {"cierra", "cerrar", "cerra", "cierre", "termina", "terminar"}

NOMBRES = {
    "abrir_app": "Abrir app", "cerrar_app": "Cerrar app", "actualizar_apps": "Actualizar lista de apps",
}


class AppsMixin:
    def _acciones_apps(self):
        return {"abrir_app": self._a_abrir_app, "cerrar_app": self._a_cerrar_app,
                "actualizar_apps": self._a_actualizar_apps}

    @staticmethod
    def _regla_app(tn):
        """'abre X' / 'cierra X' sin necesitar el modelo. Devuelve (accion, app, verbo) o None."""
        toks = tn.split()
        for i, t in enumerate(toks[:3]):
            for accion, verbos in (("abrir_app", VERBOS_ABRIR), ("cerrar_app", VERBOS_CERRAR)):
                if t in verbos:
                    resto = [w for w in toks[i + 1:] if w not in RELLENO][:4]
                    if resto and len(toks) - i - 1 <= 6:
                        return accion, " ".join(resto), t
        return None

    def _catalogo_app(self):
        """{paquete: etiqueta} que la app Moon envia (nombres reales, como en tu lanzador)."""
        try:
            with open(os.path.join(cfg.DIR, "apps_app.json"), encoding="utf-8") as f:
                return {x["p"]: x["n"] for x in json.load(f)["apps"]}
        except Exception:
            return {}

    def apps_instaladas(self, forzar=False):
        cat = self._catalogo_app()
        if cat:
            return sorted(cat)
        ruta = os.path.join(cfg.DIR, "apps_cache.json")
        try:
            with open(ruta) as f:
                cache = json.load(f)
        except Exception:
            cache = None
        if cache and cache.get("pkgs") and not forzar and time.time() - cache.get("t", 0) < 12 * 3600:
            return cache["pkgs"]
        ok, out = self.rish("pm list packages", 20)
        pkgs = sorted({l.strip()[8:] for l in out.splitlines() if l.strip().startswith("package:")}) if ok else []
        if pkgs:
            try:
                with open(ruta, "w") as f:
                    json.dump({"t": time.time(), "pkgs": pkgs}, f)
            except OSError:
                pass
            return pkgs
        return cache["pkgs"] if cache and cache.get("pkgs") else []

    def _a_actualizar_apps(self, a):
        n = len(self.apps_instaladas(True))
        return "Encontré %d aplicaciones" % n if n else "No pude leer las aplicaciones"

    def _alias(self):
        alias = dict(ALIAS_BASE)
        try:
            with open(os.path.join(cfg.DIR, "apps_alias.json"), encoding="utf-8") as f:
                for k, v in json.load(f).items():
                    alias[norm(k)] = [v] if isinstance(v, str) else list(v)
        except Exception:
            pass
        return alias

    def _clave_app(self, texto):
        """Parte 'app' de una frase, con el mismo formato que usa resolver_app para los alias."""
        tn = norm(texto)
        r = self._regla_app(tn)
        base = r[1] if r else tn
        return " ".join(w for w in norm(base).split() if w not in RELLENO)

    def sugerir_alias(self, oido, dije):
        """Si 'dije' es una app instalada y 'oido' no lleva a ella, propone el alias oido -> app."""
        pkgs = self.apps_instaladas()
        if not pkgs:
            return None
        real = self.resolver_app(self._clave_app(dije), pkgs)
        if not real or real["score"] < 0.9:
            return None
        clave = self._clave_app(oido)
        if not clave or len(clave) > 60:
            return None
        previo = self.resolver_app(clave, pkgs)
        if previo and previo["pkg"] == real["pkg"] and previo["score"] >= 0.9:
            return None
        return {"alias": clave, "pkg": real["pkg"], "nombre": real["nombre"]}

    def guardar_alias(self, alias, pkg):
        """Guarda alias -> paquete en apps_alias.json (se lee en cada busqueda: vale al instante)."""
        clave = " ".join(w for w in norm(alias).split() if w not in RELLENO)
        if not clave or len(clave) > 60 or pkg not in set(self.apps_instaladas()):
            return False
        ruta = os.path.join(cfg.DIR, "apps_alias.json")
        try:
            with open(ruta, encoding="utf-8") as f:
                datos = json.load(f)
        except Exception:
            datos = {}
        actual = datos.get(clave, [])
        actual = [actual] if isinstance(actual, str) else list(actual)
        if pkg not in actual:
            actual.insert(0, pkg)
        datos[clave] = actual
        tmp = ruta + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(datos, f, ensure_ascii=False, indent=1)
        os.replace(tmp, ruta)
        return True

    def resolver_app(self, texto, pkgs):
        """Orden: etiqueta exacta (catalogo de la app) > alias > etiqueta parecida > nombre del paquete."""
        q = " ".join(w for w in norm(texto).split() if w not in RELLENO)
        if not q:
            return None
        cat = self._catalogo_app()
        etq = {p: norm(n) for p, n in cat.items()}
        for p, n in etq.items():
            if n == q:
                return {"pkg": p, "nombre": cat[p], "score": 1.0}
        inst, alias = set(pkgs), self._alias()
        k, score = None, 0.0
        if q in alias:
            k, score = q, 1.0
        else:
            m = difflib.get_close_matches(q, list(alias), n=1, cutoff=0.8)
            if m:
                k, score = m[0], difflib.SequenceMatcher(None, q, m[0]).ratio()
        if k:
            for p in alias[k]:
                if p in inst:
                    return {"pkg": p, "nombre": cat.get(p, k), "score": round(score, 2)}
        mejor = None
        for p, n in etq.items():
            s = difflib.SequenceMatcher(None, q, n).ratio()
            if len(n) >= 4 and (n in q or q in n):
                s = max(s, 0.85)
            if s >= 0.75 and (not mejor or s > mejor[0] or (s == mejor[0] and len(n) < len(mejor[2]))):
                mejor = (s, p, n)
        if mejor:
            return {"pkg": mejor[1], "nombre": cat[mejor[1]], "score": round(mejor[0], 2)}
        for p in pkgs:
            for n in [s for s in p.lower().split(".") if s not in GENERICOS and len(s) > 2]:
                s = 1.0 if q == n else difflib.SequenceMatcher(None, q, n).ratio()
                if len(n) >= 4 and (n in q or q in n) and s < 0.85:
                    s = 0.85
                if s >= 0.75 and (not mejor or s > mejor[0] or (s == mejor[0] and len(p) < len(mejor[1]))):
                    mejor = (s, p, n)
        return {"pkg": mejor[1], "nombre": mejor[2], "score": round(mejor[0], 2)} if mejor else None

    def _app_de(self, args):
        pkgs = self.apps_instaladas()
        if not pkgs:
            return None, "Todavía no tengo la lista de aplicaciones"
        r = self.resolver_app(args.get("app", ""), pkgs)
        if not r:
            return None, "No encontré la aplicación %s" % args.get("app", "")
        return r, None

    def _lanzar_shizuku(self, r):
        ok, out = self.rish("monkey -p %s -c android.intent.category.LAUNCHER 1" % r["pkg"], 15)
        return ok and "No activities found" not in out and "aborted" not in out

    def _a_abrir_app(self, a):
        """Camino normal: la app Moon abre la app (no necesita Shizuku).
        Respaldo: Shizuku, si la app Moon no esta conectada o no confirma."""
        r, err = self._app_de(a)
        if err:
            return err
        if self.env.get("app_activa") and self.env["app_activa"]():
            i = self.env["enviar"]({"tipo": "abrir", "pkg": r["pkg"], "nombre": r["nombre"]})
            self._abiertas[i] = r
            t = threading.Timer(cfg.ESPERA_ACK, self._sin_ack, args=(i,))
            t.daemon = True
            t.start()
            return "Abriendo %s" % r["nombre"]
        return ("Abriendo %s" % r["nombre"]) if self._lanzar_shizuku(r) else "No pude abrir %s" % r["nombre"]

    def resultado_app(self, i, ok):
        """La app Moon confirma (ok) o dice que no pudo (abrir una app / terminar de hablar)."""
        h = self._habla.get(i)
        if h is not None:
            h["ok"] = bool(ok)
            h["ev"].set()
            return
        r = self._abiertas.pop(i, None)
        log_evento({"tipo": "ack_app", "id": i, "ok": bool(ok), "app": r and r["nombre"]})
        if r and not ok:
            self._respaldo(r)

    def _sin_ack(self, i):
        r = self._abiertas.pop(i, None)
        if r:
            log_evento({"tipo": "ack_app", "id": i, "ok": False, "motivo": "sin_respuesta"})
            self._respaldo(r)

    def _respaldo(self, r):
        if not self._lanzar_shizuku(r):
            self.hablar("No pude abrir %s" % r["nombre"])

    def _a_cerrar_app(self, a):
        r, err = self._app_de(a)
        if err:
            return err
        ok, _ = self.rish("am force-stop %s" % r["pkg"], 10)
        return ("Cerré %s" % r["nombre"]) if ok else "No pude cerrar %s" % r["nombre"]
