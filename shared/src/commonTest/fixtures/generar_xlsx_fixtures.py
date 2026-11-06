"""Genera `XlsxFixtures.kt` con zips de verdad, en Base64, para las pruebas comunes.

Se corre desde la raíz del repo:

    python shared/src/commonTest/fixtures/generar_xlsx_fixtures.py

Los XML son los mismos que armaba `ProductSheetImportTest` con `ZipOutputStream`; lo único que
cambia es cómo se comprime el zip:

- `normal`: DEFLATE, escrito sobre un archivo posicionable.
- `streaming`: DEFLATE sobre un flujo **no posicionable**, así que zipfile deja los tamaños en cero en
  el encabezado local y los pone en un descriptor después de los datos (bit 3). Es exactamente el
  caso que rompe a quien lea los encabezados locales.
- `stored`: sin comprimir.
- `prefixed`: los mismos elementos con prefijo `x:`, como el archivo real que trajo un dueño.

`date_time` es fijo para que el Base64 salga idéntico en cada corrida.
"""

import base64
import io
import zipfile
from pathlib import Path

DATE_TIME = (2026, 11, 6, 9, 0, 0)
SPREADSHEET_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"

SHARED_STRINGS = (
    '<?xml version="1.0"?>'
    "<sst>"
    "<si><t>nombre</t></si>"
    "<si><t>precio</t></si>"
    "<si><t>Jabon &amp; jabon</t></si>"
    "</sst>"
)

SHEET = (
    '<?xml version="1.0"?>'
    "<worksheet><sheetData>"
    '<row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>'
    '<row r="2"><c r="A2" t="s"><v>2</v></c><c r="B2"><v>45.5</v></c></row>'
    '<row r="3"><c r="A3" t="inlineStr"><is><t>Escoba</t></is></c><c r="C3"><v>7</v></c></row>'
    '<row r="4"><c r="A4" t="inlineStr"><is><t>Cubeta 20 L</t></is></c><c r="B4"><v>89.9</v></c></row>'
    "</sheetData></worksheet>"
)

PREFIXED_SHARED_STRINGS = (
    '<?xml version="1.0"?>'
    f'<x:sst xmlns:x="{SPREADSHEET_NS}" />'
)

PREFIXED_SHEET = (
    '<?xml version="1.0"?>'
    f'<x:worksheet xmlns:x="{SPREADSHEET_NS}"><x:sheetData>'
    '<x:row r="1"><x:c r="A1" t="str"><x:v>Nombre</x:v></x:c>'
    '<x:c r="B1" t="str"><x:v>Precio</x:v></x:c></x:row>'
    '<x:row r="2"><x:c r="A2" t="str"><x:v>Organizador multiusos mediano</x:v></x:c>'
    '<x:c r="B2" t="n"><x:v>129.9</x:v></x:c></x:row>'
    '<x:row r="3"><x:c r="A3" t="str"><x:v>Cepillo limpiador para cocina</x:v></x:c>'
    '<x:c r="B3" t="n"><x:v>89</x:v></x:c></x:row>'
    "</x:sheetData></x:worksheet>"
)


class NonSeekable:
    """Suficiente para que `zipfile` no pueda volver atrás: escribe descriptor de datos (bit 3)."""

    def __init__(self):
        self._buffer = io.BytesIO()

    def write(self, data):
        return self._buffer.write(data)

    def flush(self):
        pass

    def tell(self):
        raise OSError("flujo no posicionable")

    def seek(self, *args):
        raise OSError("flujo no posicionable")

    def seekable(self):
        return False

    def writable(self):
        return True

    def getvalue(self):
        return self._buffer.getvalue()


def build(entries, compression, stream=None):
    target = stream if stream is not None else io.BytesIO()
    with zipfile.ZipFile(target, "w", compression) as archive:
        for name, text in entries:
            info = zipfile.ZipInfo(name, date_time=DATE_TIME)
            info.compress_type = compression
            archive.writestr(info, text.encode("utf-8"))
    return target.getvalue() if stream is None else stream.getvalue()


NORMAL_ENTRIES = [
    ("xl/sharedStrings.xml", SHARED_STRINGS),
    ("xl/worksheets/sheet1.xml", SHEET),
]

PREFIXED_ENTRIES = [
    ("xl/sharedStrings.xml", PREFIXED_SHARED_STRINGS),
    ("xl/worksheets/sheet1.xml", PREFIXED_SHEET),
]


def b64(payload):
    return base64.b64encode(payload).decode("ascii")


def main():
    fixtures = {
        "NORMAL": build(NORMAL_ENTRIES, zipfile.ZIP_DEFLATED),
        "STREAMING": build(NORMAL_ENTRIES, zipfile.ZIP_DEFLATED, stream=NonSeekable()),
        "STORED": build(NORMAL_ENTRIES, zipfile.ZIP_STORED),
        "PREFIXED": build(PREFIXED_ENTRIES, zipfile.ZIP_DEFLATED),
    }

    lines = [
        "package com.cuentivo.app.domain",
        "",
        "import kotlin.io.encoding.Base64",
        "import kotlin.io.encoding.ExperimentalEncodingApi",
        "",
        "/**",
        " * Zips de verdad en Base64, generados por `shared/src/commonTest/fixtures/generar_xlsx_fixtures.py`.",
        " *",
        " * No se escriben a mano a propósito: lo que hay que probar es el lector contra un zip real, no",
        " * contra la idea que tenemos de un zip. `date_time` es fijo, así que correr el script otra vez",
        " * produce exactamente el mismo archivo.",
        " *",
        " * Los cuatro llevan el mismo libro salvo `PREFIXED`, que es el archivo con prefijo `x:`:",
        " * `STREAMING` deja los tamaños en cero en el encabezado local (bit 3), y `STORED` no comprime.",
        " */",
        "@OptIn(ExperimentalEncodingApi::class)",
        "internal object XlsxFixtures {",
        "",
        "    /** DEFLATE sobre un archivo posicionable: el caso normal. */",
        "    val NORMAL: ByteArray = Base64.decode(NORMAL_B64)",
        "",
        "    /** DEFLATE escrito sobre un flujo no posicionable: tamaños en cero en el encabezado local. */",
        "    val STREAMING: ByteArray = Base64.decode(STREAMING_B64)",
        "",
        "    /** Las mismas entradas, sin comprimir. */",
        "    val STORED: ByteArray = Base64.decode(STORED_B64)",
        "",
        "    /** El mismo libro con los elementos prefijados con `x:` y los valores como `t=\"str\"`. */",
        "    val PREFIXED: ByteArray = Base64.decode(PREFIXED_B64)",
        "",
    ]
    for name, payload in fixtures.items():
        lines.append(f'    private const val {name}_B64 = "{b64(payload)}"')
        lines.append("")
    lines.append("}")

    target = Path(__file__).resolve().parents[1] / "kotlin" / "com" / "cuentivo" / "app" / "domain" / "XlsxFixtures.kt"
    target.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"escrito {target}")
    for name, payload in fixtures.items():
        print(f"{name}: {len(payload)} bytes, bit3={bool(int.from_bytes(payload[6:8], 'little') & 0x08)}")


if __name__ == "__main__":
    main()
