"""Evidencias del frontend Android: captura (.png) + descripción (.txt) por prueba.

1. Pruebas JVM (unitarias FU, integración FI): ejecuta cada prueba con Gradle y guarda su
   salida real de consola como imagen (mismo formato que el backend).
2. Pruebas en el celular (caja negra FC, E2E FE, integración Room FI): instala la app y las
   pruebas, las ejecuta con `adb shell am instrument` y descarga las capturas de pantalla
   reales que toma cada prueba. (No se usa connectedAndroidTest porque desinstala la app y
   borra las capturas.)

Uso (desde mobile-android/, con el venv del backend):
    ../backend/.venv/Scripts/python scripts/generar_evidencias_android.py          # todo
    ../backend/.venv/Scripts/python scripts/generar_evidencias_android.py --solo-jvm
Requisito para la parte 2: celular con depuración USB conectado (`adb devices`).
"""

import argparse
import importlib.util
import json
import os
import shutil
import subprocess
import sys
import tempfile
import textwrap
import xml.etree.ElementTree as ET
from datetime import datetime
from pathlib import Path

ANDROID = Path(__file__).resolve().parents[1]
REPO = ANDROID.parent
SALIDA = REPO / "evidencias" / "frontend"
SDK = Path(os.environ.get("ANDROID_HOME", Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk"))
ADB = str(SDK / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb"))
GRADLEW = str(ANDROID / ("gradlew.bat" if os.name == "nt" else "gradlew"))
JAVA_HOME = os.environ.get("JAVA_HOME") or r"C:\Program Files\Android\Android Studio\jbr"
PAQUETE = "com.metrik.app"
DIR_DISPOSITIVO = f"/sdcard/Android/data/{PAQUETE}/files/evidencias"

TIPOS = {
    "FU": ("unitarias", "Unitaria (frontend)"),
    "FI": ("integracion", "Integración (frontend)"),
    "FC": ("caja-negra", "Caja negra (frontend)"),
    "FE": ("e2e", "End-to-End (frontend)"),
}

# Reutiliza el renderizador de terminal del script del backend
_spec = importlib.util.spec_from_file_location("ev_backend", REPO / "backend" / "scripts" / "generar_evidencias.py")
ev_backend = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ev_backend)
render_terminal, slug = ev_backend.render_terminal, ev_backend.slug

RUIDO = ("> Task", "Consider enabling", "Download", "Configuration cache", "Unable to strip")


def run(args: list[str]) -> subprocess.CompletedProcess:
    env = {**os.environ, "JAVA_HOME": JAVA_HOME}
    return subprocess.run(args, cwd=ANDROID, capture_output=True, text=True, encoding="utf-8", errors="replace", env=env)


def descripcion(r: dict, tipo: str, png: str, entorno: str) -> str:
    campos = [
        ("ID", r["id"]), ("Nombre", r["nombre"]), ("Tipo", tipo), ("Requisito", r.get("requisito") or "-"),
        ("Archivo", f"{r['clase']} :: {r['metodo']}"), ("Objetivo", r.get("objetivo") or "-"),
        ("Precondiciones", r.get("precondiciones") or "Ninguna."), ("Datos de entrada", r.get("entrada") or "-"),
        ("Resultado esperado", r.get("esperado") or "-"), ("Resultado obtenido", r.get("obtenido") or "-"),
        ("Estado", r["estado"]), ("Entorno", entorno), ("Fecha", datetime.now().strftime("%Y-%m-%d %H:%M:%S")),
        ("Captura", png),
    ]
    ancho = max(len(k) for k, _ in campos) + 2
    out = []
    for k, v in campos:
        lineas = textwrap.wrap(str(v), 100 - ancho) or [""]
        out.append(f"{k + ':':<{ancho}}{lineas[0]}")
        out.extend(" " * ancho + x for x in lineas[1:])
    return "\n".join(out) + "\n"


def destino(r: dict) -> tuple[Path, str]:
    carpeta, tipo = TIPOS[r["id"][:2]]
    return SALIDA / carpeta / f"{r['id']}_{slug(r['nombre'])}", tipo


# ---------------------------------------------------------------- 1. JVM


def evidencias_jvm() -> list[dict]:
    print("1/2 Pruebas JVM (unitarias e integración)...")
    suite = run([GRADLEW, ":app:testDebugUnitTest", "--console=plain", "--rerun-tasks", "-q"])
    registros: list[dict] = []
    for xml in sorted((ANDROID / "app" / "build" / "test-results" / "testDebugUnitTest").glob("TEST-*.xml")):
        salida = ET.parse(xml).getroot().findtext("system-out") or ""
        for linea in salida.splitlines():
            s = linea.strip()
            if s.startswith("EVIDENCIA_JSON "):
                registros.append(json.loads(s[len("EVIDENCIA_JSON "):]))
    registros.sort(key=lambda r: r["id"])
    for r in registros:
        base, tipo = destino(r)
        filtro = f"{r['clase']}.{r['metodo']}"
        res = run([GRADLEW, ":app:testDebugUnitTest", "--tests", filtro, "--console=plain", "--rerun"])
        lineas = [f'PS {ANDROID}> .\\gradlew :app:testDebugUnitTest --tests "{filtro}"']
        for ln in res.stdout.splitlines():
            s = ln.rstrip()
            if not s.strip() or s.startswith(RUIDO) or "EVIDENCIA_JSON" in s:
                continue
            lineas.append(s)
        render_terminal(lineas[-40:], f"Windows PowerShell — {r['id']} · {r['nombre']}", base.with_suffix(".png"))
        base.with_suffix(".txt").write_text(
            descripcion(r, tipo, base.name + ".png", "JVM (Gradle testDebugUnitTest)"), encoding="utf-8"
        )
        print(f"   {r['id']} {r['estado']}")
    if suite.returncode != 0:
        print("   (la suite JVM terminó con errores; revisar)")
    return registros


# ---------------------------------------------------------------- 2. Dispositivo


def hay_dispositivo() -> bool:
    out = subprocess.run([ADB, "devices"], capture_output=True, text=True).stdout
    return any(line.endswith("\tdevice") for line in out.splitlines())


def evidencias_dispositivo() -> list[dict]:
    print("2/2 Pruebas en el celular (caja negra, E2E, integración Room)...")
    if not hay_dispositivo():
        print("   No hay celular conectado (adb devices). Se omiten.")
        return []
    inst = run([GRADLEW, ":app:installDebug", ":app:installDebugAndroidTest", "--console=plain", "-q"])
    if inst.returncode != 0:
        print(inst.stdout[-2000:], inst.stderr[-2000:])
        sys.exit(1)
    subprocess.run([ADB, "shell", "rm", "-rf", DIR_DISPOSITIVO])
    salida = subprocess.run(
        [ADB, "shell", "am", "instrument", "-w", "-r", f"{PAQUETE}.test/androidx.test.runner.AndroidJUnitRunner"],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    ).stdout
    tmp = Path(tempfile.mkdtemp(prefix="metrik_ev_"))
    subprocess.run([ADB, "pull", DIR_DISPOSITIVO, str(tmp)], capture_output=True)
    origen = tmp / "evidencias"
    modelo = subprocess.run([ADB, "shell", "getprop", "ro.product.model"], capture_output=True, text=True).stdout.strip()
    version = subprocess.run([ADB, "shell", "getprop", "ro.build.version.release"], capture_output=True, text=True).stdout.strip()
    entorno = f"Celular {modelo} (Android {version})"
    registros = []
    for js in sorted(origen.glob("*.json")):
        r = json.loads(js.read_text(encoding="utf-8"))
        base, tipo = destino(r)
        base.parent.mkdir(parents=True, exist_ok=True)
        captura = origen / f"{r['id']}.png"
        if captura.exists():
            shutil.copy(captura, base.with_suffix(".png"))
        else:  # prueba sin pantalla (Room): se muestra la salida de la instrumentación
            bloque = [ln for ln in salida.splitlines() if r["metodo"] in ln or "OK (" in ln]
            lineas = [
                f"PS {ANDROID}> adb shell am instrument -w -e class {r['clase']}#{r['metodo']} {PAQUETE}.test/...",
                *bloque, f"[{r['id']}] {r['nombre']} -> {r['estado']}", f"  Obtenido  : {r['obtenido']}",
            ]
            render_terminal(lineas, f"Windows PowerShell — {r['id']} · {r['nombre']}", base.with_suffix(".png"))
        base.with_suffix(".txt").write_text(descripcion(r, tipo, base.name + ".png", entorno), encoding="utf-8")
        registros.append(r)
        print(f"   {r['id']} {r['estado']}")
    (SALIDA / "00_instrumentacion.txt").write_text(salida, encoding="utf-8")
    shutil.rmtree(tmp, ignore_errors=True)
    return registros


def indice(registros: list[dict]) -> None:
    aprobados = sum(1 for r in registros if r["estado"] == "APROBADO")
    filas = []
    for r in sorted(registros, key=lambda x: (list(TIPOS).index(x["id"][:2]), x["id"])):
        base, tipo = destino(r)
        rel = base.relative_to(SALIDA).as_posix()
        filas.append(
            f"| {r['id']} | [{r['nombre']}]({rel}.png) | {tipo.split(' ')[0]} | {r.get('requisito', '')} | "
            f"{'✅' if r['estado'] == 'APROBADO' else '❌'} {r['estado']} |"
        )
    (SALIDA / "README.md").write_text(
        f"# Evidencias de pruebas — Frontend (Android)\n\nGenerado: {datetime.now():%Y-%m-%d %H:%M}\n\n"
        f"**{aprobados} de {len(registros)} pruebas aprobadas.**\n\n"
        "Unitarias e integración JVM: captura de la ejecución en consola. "
        "Caja negra y E2E: captura real de la pantalla del celular.\n\n"
        "| ID | Prueba | Tipo | Requisito | Estado |\n|----|--------|------|-----------|--------|\n" + "\n".join(filas) + "\n",
        encoding="utf-8",
    )
    print(f"Listo: {aprobados}/{len(registros)} aprobadas → {SALIDA}")


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--solo-jvm", action="store_true")
    p.add_argument("--solo-dispositivo", action="store_true")
    a = p.parse_args()
    registros: list[dict] = []
    if not a.solo_dispositivo:
        registros += evidencias_jvm()
    if not a.solo_jvm:
        registros += evidencias_dispositivo()
    SALIDA.mkdir(parents=True, exist_ok=True)
    previos = SALIDA / "_registros.json"
    if previos.exists():
        viejos = {r["id"]: r for r in json.loads(previos.read_text(encoding="utf-8"))}
        viejos.update({r["id"]: r for r in registros})
        registros = list(viejos.values())
    previos.write_text(json.dumps(registros, ensure_ascii=False, indent=2), encoding="utf-8")
    indice(registros)


if __name__ == "__main__":
    main()
