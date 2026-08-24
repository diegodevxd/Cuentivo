package com.cuentivo.app.ui

import java.io.File
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
}
