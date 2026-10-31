package com.cuentivo.app.domain

import java.text.Normalizer
import java.util.Locale

object CustomerNameNormalizer {
    fun key(name: String): String =
        Normalizer.normalize(name.trim(), Normalizer.Form.NFD)
            .replace("\\p{M}+".toRegex(), "")
            .lowercase(Locale.ROOT)
            .replace("\\s+".toRegex(), " ")
}
