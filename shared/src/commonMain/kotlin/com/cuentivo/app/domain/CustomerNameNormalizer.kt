package com.cuentivo.app.domain

/**
 * Descomposición canónica Unicode (NFD) del texto.
 *
 * Es el único paso de `CustomerNameNormalizer` que depende de la plataforma: Android usa
 * `java.text.Normalizer`, iOS `NSString.decomposedStringWithCanonicalMapping`. Las dos devuelven
 * los acentos y la diéresis como marcas combinantes separadas, que el resto del normalizador
 * elimina igual en ambas plataformas.
 */
internal expect fun canonicalDecomposition(text: String): String

object CustomerNameNormalizer {
    fun key(name: String): String =
        canonicalDecomposition(name.trim())
            .replace("\\p{M}+".toRegex(), "")
            .lowercase()
            .replace("\\s+".toRegex(), " ")
}
