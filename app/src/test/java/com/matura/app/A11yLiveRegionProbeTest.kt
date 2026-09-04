package com.matura.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.SemanticsMatcher
import com.matura.app.model.UiLanguage
import com.matura.app.ui.SetEditorScreen
import com.matura.app.ui.Str
import com.matura.app.ui.VoiceBar
import com.matura.app.ui.VoiceMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sonda pod zarzut "komunikaty bledu nie sa oglaszane".
 * Nie asercje - zrzut tego, co czytnik ekranu FAKTYCZNIE widzi.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class A11yLiveRegionProbeTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun setEditor_semantics_dump() {
        rule.setContent {
            SetEditorScreen(str = Str(UiLanguage.PL), existing = null, onBack = {}, onSave = {})
        }
        println("########## EDYTOR ##########")
        println(rule.onRoot().printToString(maxDepth = 100))
        println("########## /EDYTOR ##########")

        val clickable = rule.onAllNodes(
            SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        ).fetchSemanticsNodes()
        println("KLIKALNYCH WEZLOW: ${clickable.size}")
        clickable.forEachIndexed { i, n ->
            val txt = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("|")
            val cd = n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString("|")
            println("  [$i] text=$txt contentDescription=$cd")
        }
    }

    @Test
    fun voiceBar_semantics_dump() {
        rule.setContent {
            VoiceBar(
                listening = false,
                mode = VoiceMode.ONLINE,
                message = "Bez zgody na mikrofon tryb glosowy nie zadziala.",
                str = Str(UiLanguage.PL),
                onToggle = {},
            )
        }
        println("########## VOICEBAR ##########")
        println(rule.onRoot().printToString(maxDepth = 100))
        println("########## /VOICEBAR ##########")

        val live = rule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)
        ).fetchSemanticsNodes()
        println("WEZLOW Z LiveRegion: ${live.size}")
    }
}
