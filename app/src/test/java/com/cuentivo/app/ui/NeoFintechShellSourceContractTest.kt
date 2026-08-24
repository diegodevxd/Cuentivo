package com.cuentivo.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeoFintechShellSourceContractTest {

    @Test
    fun `animated content keys its subtree with target destination`() {
        val source = File("src/main/java/com/cuentivo/app/ui/NeoFintechShell.kt").readText()

        assertTrue(
            Regex(
                "\\) \\{ targetDestination ->\\s+key\\(targetDestination\\) \\{ content\\(padding\\) \\}",
            ).containsMatchIn(source),
        )
    }

    @Test
    fun `shell renders optional AI action before language action`() {
        val source = File("src/main/java/com/cuentivo/app/ui/NeoFintechShell.kt").readText()
        assertTrue(source.contains("onOpenAi: (() -> Unit)? = null"))
        assertTrue(source.indexOf("onOpenAi?.let") < source.indexOf("onClick = onOpenLanguage"))
    }

    @Test
    fun `AI entry uses local robot vector not star icon`() {
        val source = File("src/main/java/com/cuentivo/app/ui/NeoFintechShell.kt").readText()
        assertTrue("must reference the local robot vector", source.contains("ic_cuentivo_ai_robot"))
        val aiBlock = source.substring(
            source.indexOf("onOpenAi?.let"),
            source.indexOf("onClick = onOpenLanguage"),
        )
        assertFalse("AI action must not use Icons.Filled.Star", aiBlock.contains("Icons.Filled.Star"))
    }

    @Test
    fun `robot vector drawable exists`() {
        val drawable = File("src/main/res/drawable/ic_cuentivo_ai_robot.xml")
        assertTrue("robot vector drawable must exist", drawable.exists())
    }
}
