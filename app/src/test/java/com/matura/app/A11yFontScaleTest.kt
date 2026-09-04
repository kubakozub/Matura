package com.matura.app

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import com.matura.app.ui.GameKeyboard
import com.matura.app.ui.PixelText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class A11yFontScaleTest {

    @get:Rule
    val rule = createComposeRule()

    /**
     * Czy tekst rosnie, gdy uczen ustawi wieksza czcionke systemowa.
     * fontScale 1.0 vs 2.0 przy tej samej gestosci ekranu.
     */
    private fun measure(fs: Float) {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 3f, fontScale = fs)) {
                Box(Modifier.testTag("pixel")) { PixelText("GRAJ", glyphHeight = 11.dp) }
                Box(Modifier.testTag("system")) { Text("GRAJ", fontSize = 14.sp) }
            }
        }
        val p = rule.onNodeWithTag("pixel").getUnclippedBoundsInRoot()
        val s = rule.onNodeWithTag("system").getUnclippedBoundsInRoot()
        println("FONTSCALE=$fs  PixelText.h=${p.bottom - p.top}  PixelText.w=${p.right - p.left}  SystemText.h=${s.bottom - s.top}")
    }

    @Test
    fun fontScale_1() = measure(1.0f)

    @Test
    fun fontScale_2() = measure(2.0f)

    @Test
    fun keyboard_semantics() {
        rule.setContent { GameKeyboard({}, {}, {}, {}, {}, {}) }
        println("########## KLAWIATURA ##########")
        println(rule.onRoot().printToString(maxDepth = 100))
        println("########## /KLAWIATURA ##########")
    }
}
