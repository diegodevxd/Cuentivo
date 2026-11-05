package com.cuentivo.app.domain

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * La clave de `customers.normalizedName` tiene que salir idéntica en Android y en iOS: es la mitad
 * del índice `(businessId, normalizedName)` con el que se busca (y se empareja al importar).
 *
 * Corre en Android en `:shared:testAndroidHostTest` y en iOS en
 * `:shared:iosSimulatorArm64Test` (GitHub Actions, macOS).
 */
class CustomerNameNormalizerCommonTest {

    @Test
    fun accents_case_and_repeated_spaces_collapse_to_one_key() {
        assertEquals("maria lopez", CustomerNameNormalizer.key("  María   LÓPEZ  "))
    }

    @Test
    fun the_enye_is_a_letter_not_an_accent() {
        assertEquals("nunez", CustomerNameNormalizer.key("NÚÑEZ"))
        assertEquals("pena", CustomerNameNormalizer.key("Peña"))
    }

    @Test
    fun dieresis_and_cedilla_are_stripped_like_accents() {
        assertEquals("gongora", CustomerNameNormalizer.key("Góngora"))
        assertEquals("buenos dias", CustomerNameNormalizer.key("BUENOS   DIAS"))
    }

    @Test
    fun already_normalized_input_is_unchanged() {
        assertEquals("diego", CustomerNameNormalizer.key("diego"))
        assertEquals("", CustomerNameNormalizer.key("   "))
    }
}
