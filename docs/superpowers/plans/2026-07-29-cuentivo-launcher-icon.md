# Cuentivo Launcher Icon Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reemplazar el icono de plantilla de Android por la identidad aprobada de Cuentivo y producir un AAB firmado con `versionCode 6`.

**Architecture:** ImageGen producirá una fuente cuadrada de alta resolución con la marca aprobada. Un script local y determinista preparará el primer plano transparente, las densidades heredadas y las variantes redondas; Android seguirá usando iconos adaptativos con fondo y primer plano separados.

**Tech Stack:** ImageGen, PNG/WebP, Pillow, Android adaptive icons, Gradle, Android Emulator.

## Global Constraints

- Fondo azul marino oscuro y símbolo verde menta.
- La marca es una **C** construida como libreta o estado de cuenta, con una verificación integrada.
- Sin texto, cifras, monedas, símbolos de dólar, referencias bancarias o detalles finos.
- Mantener `versionName = "1.2"` e incrementar `versionCode` a `6`.
- No modificar Billing, Firebase, sincronización ni otras fases.
- Preservar los artefactos locales existentes bajo `artifacts/` sin incluirlos en Git.

---

### Task 1: Generar y aprobar la fuente visual

**Files:**
- Create: `design/icon/cuentivo-launcher-source.png`

**Interfaces:**
- Consumes: dirección visual de `docs/superpowers/specs/2026-07-29-cuentivo-launcher-icon-design.md`.
- Produces: PNG cuadrado de 1024×1024 utilizado por el generador de recursos.

- [ ] **Step 1: Generar la imagen con ImageGen**

Usar el modo integrado de ImageGen con este prompt:

```text
Use case: logo-brand
Asset type: Android launcher icon source, square 1024x1024
Primary request: Create a polished app icon for Cuentivo.
Subject: A bold mint-green letter C constructed from the visual language of a simple ledger or account statement, with one small integrated check mark.
Style/medium: clean flat vector-friendly brand mark, geometric, minimal, professional and friendly.
Composition/framing: centered symbol, generous safe padding, readable at 48 px, no elements near the outer 20 percent.
Color palette: deep navy background #001D26; mint symbol #08DDA5; optional restrained cyan accent #1593B2.
Text: none.
Constraints: high contrast; simple silhouette; recognizable under circular, rounded-square and teardrop Android masks.
Avoid: Android robot, dollar signs, coins, crypto imagery, banks, loans, gradients with banding, tiny details, letters other than C, watermark, mockup frame.
```

- [ ] **Step 2: Inspeccionar la fuente**

Abrirla con `view_image` y comprobar: C legible, verificación visible, ausencia de texto, zona segura y contraste.

- [ ] **Step 3: Guardar la fuente seleccionada**

Copiar el resultado elegido a `design/icon/cuentivo-launcher-source.png` sin sobrescribir otros recursos de diseño.

---

### Task 2: Preparar todos los recursos de Android

**Files:**
- Create: `tools/generate_launcher_assets.py`
- Modify: `app/src/main/res/drawable/ic_launcher_background.xml`
- Modify: `app/src/main/res/drawable/ic_launcher_foreground.xml`
- Create: `app/src/main/res/drawable/ic_launcher_monochrome.xml`
- Create: `app/src/main/res/mipmap-mdpi/ic_launcher_foreground.webp`
- Create: `app/src/main/res/mipmap-hdpi/ic_launcher_foreground.webp`
- Create: `app/src/main/res/mipmap-xhdpi/ic_launcher_foreground.webp`
- Create: `app/src/main/res/mipmap-xxhdpi/ic_launcher_foreground.webp`
- Create: `app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.webp`
- Modify: `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- Modify: `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`
- Modify: `app/src/main/res/mipmap-mdpi/ic_launcher.webp`
- Modify: `app/src/main/res/mipmap-mdpi/ic_launcher_round.webp`
- Modify: `app/src/main/res/mipmap-hdpi/ic_launcher.webp`
- Modify: `app/src/main/res/mipmap-hdpi/ic_launcher_round.webp`
- Modify: `app/src/main/res/mipmap-xhdpi/ic_launcher.webp`
- Modify: `app/src/main/res/mipmap-xhdpi/ic_launcher_round.webp`
- Modify: `app/src/main/res/mipmap-xxhdpi/ic_launcher.webp`
- Modify: `app/src/main/res/mipmap-xxhdpi/ic_launcher_round.webp`
- Modify: `app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp`
- Modify: `app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.webp`

**Interfaces:**
- Consumes: `design/icon/cuentivo-launcher-source.png`.
- Produces: recursos launcher completos manteniendo los nombres referenciados por el manifest.

- [ ] **Step 1: Crear el generador determinista**

Crear `tools/generate_launcher_assets.py` con este contenido:

```python
from __future__ import annotations

import argparse
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageOps, ImageStat


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "design" / "icon" / "cuentivo-launcher-source.png"
RES = ROOT / "app" / "src" / "main" / "res"
DENSITIES = {
    "mdpi": (48, 108),
    "hdpi": (72, 162),
    "xhdpi": (96, 216),
    "xxhdpi": (144, 324),
    "xxxhdpi": (192, 432),
}


def square_source() -> Image.Image:
    image = Image.open(SOURCE).convert("RGBA")
    return ImageOps.fit(image, (1024, 1024), method=Image.Resampling.LANCZOS)


def extracted_mark(image: Image.Image) -> Image.Image:
    background = Image.new("RGB", image.size, image.convert("RGB").getpixel((8, 8)))
    difference = ImageChops.difference(image.convert("RGB"), background).convert("L")
    alpha = difference.point(lambda value: 0 if value < 18 else min(255, (value - 18) * 5))
    mark = image.copy()
    mark.putalpha(alpha)
    bounds = alpha.getbbox()
    if bounds is None:
        raise ValueError("The generated source has no foreground mark")
    return mark.crop(bounds)


def save_webp(image: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, "WEBP", lossless=True, method=6)


def generate() -> None:
    source = square_source()
    mark = extracted_mark(source)
    for density, (legacy_size, foreground_size) in DENSITIES.items():
        folder = RES / f"mipmap-{density}"
        legacy = source.resize((legacy_size, legacy_size), Image.Resampling.LANCZOS)
        save_webp(legacy, folder / "ic_launcher.webp")

        mask = Image.new("L", (legacy_size, legacy_size), 0)
        ImageDraw.Draw(mask).ellipse((0, 0, legacy_size - 1, legacy_size - 1), fill=255)
        rounded = legacy.copy()
        rounded.putalpha(mask)
        save_webp(rounded, folder / "ic_launcher_round.webp")

        safe_size = round(foreground_size * 0.60)
        fitted_mark = ImageOps.contain(
            mark,
            (safe_size, safe_size),
            method=Image.Resampling.LANCZOS,
        )
        foreground = Image.new("RGBA", (foreground_size, foreground_size), (0, 0, 0, 0))
        foreground.alpha_composite(
            fitted_mark,
            (
                (foreground_size - fitted_mark.width) // 2,
                (foreground_size - fitted_mark.height) // 2,
            ),
        )
        save_webp(foreground, folder / "ic_launcher_foreground.webp")


def validate() -> None:
    expected = []
    for density, (legacy_size, foreground_size) in DENSITIES.items():
        folder = RES / f"mipmap-{density}"
        expected.extend(
            [
                (folder / "ic_launcher.webp", (legacy_size, legacy_size)),
                (folder / "ic_launcher_round.webp", (legacy_size, legacy_size)),
                (folder / "ic_launcher_foreground.webp", (foreground_size, foreground_size)),
            ]
        )
    for path, size in expected:
        image = Image.open(path)
        if image.size != size:
            raise ValueError(f"{path} has {image.size}, expected {size}")
        extrema = ImageStat.Stat(image.convert("RGB")).extrema
        if all(low == high for low, high in extrema):
            raise ValueError(f"{path} is visually uniform")
    print("Launcher assets generated and validated")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()
    if not args.verify_only:
        generate()
    validate()


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: Ejecutar el generador**

Run:

```powershell
python tools/generate_launcher_assets.py
```

Expected: quince recursos actualizados, sin excepción y con el mensaje `Launcher assets generated and validated`.

- [ ] **Step 3: Reemplazar las capas adaptativas**

Dejar `ic_launcher_background.xml` como:

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:fillColor="#001D26" android:pathData="M0,0h108v108h-108z" />
</vector>
```

Dejar `ic_launcher_foreground.xml` como una compatibilidad explícita con el raster por densidad:

```xml
<?xml version="1.0" encoding="utf-8"?>
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:drawable="@mipmap/ic_launcher_foreground" />
</layer-list>
```

Crear `ic_launcher_monochrome.xml` con una C y verificación de silueta simple:

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#000000"
        android:pathData="M78,33A31,31 0,1 0,78,75L69,66A19,19 0,1 1,69,42Z" />
    <path
        android:fillColor="#000000"
        android:pathData="M57,56l7,7 16,-19 7,6 -23,27 -14,-14z" />
</vector>
```

En ambos XML de `mipmap-anydpi-v26` usar exactamente:

```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
```

- [ ] **Step 4: Verificar recursos**

Run:

```powershell
python tools/generate_launcher_assets.py --verify-only
```

Expected: `Launcher assets generated and validated`, sin archivos faltantes ni dimensiones incorrectas.

---

### Task 3: Verificar el icono instalado

**Files:**
- No source changes expected.

**Interfaces:**
- Consumes: APK debug con los recursos nuevos.
- Produces: evidencia visual del icono en el launcher del AVD `Medium_Phone`.

- [ ] **Step 1: Compilar e instalar debug**

Run:

```powershell
.\gradlew.bat assembleDebug installDebug --no-daemon --no-configuration-cache --no-watch-fs --console=plain --no-problems-report
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Mostrar el launcher**

Cerrar Cuentivo, volver al inicio del emulador y abrir el cajón de aplicaciones.

- [ ] **Step 3: Inspeccionar visualmente**

Capturar una imagen del launcher. Confirmar que ya no aparece el robot, que la C no está recortada y que sigue legible bajo la máscara del dispositivo.

---

### Task 4: Preparar la versión 6 y el AAB

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `PROJECT_MEMORY.md`

**Interfaces:**
- Consumes: iconos aprobados y verificados.
- Produces: AAB release firmado con `versionCode 6`, `versionName 1.2`.

- [ ] **Step 1: Incrementar la versión**

En `app/build.gradle.kts` dejar exactamente:

```kotlin
versionCode = 6
versionName = "1.2"
```

- [ ] **Step 2: Ejecutar verificación completa**

Run cada tarea por separado:

```powershell
.\gradlew.bat testDebugUnitTest --rerun-tasks --no-daemon --no-configuration-cache --no-watch-fs --console=plain --no-problems-report
.\gradlew.bat lintDebug --no-daemon --no-configuration-cache --no-watch-fs --console=plain --no-problems-report
.\gradlew.bat assembleRelease --no-daemon --no-configuration-cache --no-watch-fs --console=plain --no-problems-report
.\gradlew.bat bundleRelease --no-daemon --no-configuration-cache --no-watch-fs --console=plain --no-problems-report
```

Expected: las cuatro tareas terminan con código 0; tests con 0 fallos; lint con 0 errores.

- [ ] **Step 3: Verificar el artefacto**

Comprobar:

```powershell
jarsigner -verify app\build\outputs\bundle\release\app-release.aab
apkanalyzer manifest version-code app\build\outputs\apk\release\app-release.apk
apkanalyzer manifest version-name app\build\outputs\apk\release\app-release.apk
```

Expected: `jar verified`, `6` y `1.2`.

- [ ] **Step 4: Actualizar memoria**

Registrar en `PROJECT_MEMORY.md` el diseño del icono, `versionCode 6`, rutas de recursos, pruebas reales y ruta del AAB.

- [ ] **Step 5: Revisar secretos y commit**

Run:

```powershell
git diff --check
git status --short
git diff --cached
```

Stagear solamente código, recursos, plan, fuente aprobada y memoria. Excluir `google-services.json`, `keystore.properties`, `*.jks`, `.kotlin/`, `build/` y `artifacts/`.

- [ ] **Step 6: Commit y push**

```powershell
git commit -m "feat: agrega identidad visual de Cuentivo"
git push origin phase1-financial-core
```

Expected: push aceptado sin force y working tree limpio salvo `artifacts/`, que pertenece a la grabación anterior.
