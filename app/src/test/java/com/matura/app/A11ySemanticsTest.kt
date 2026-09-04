package com.matura.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import com.matura.app.model.Options
import com.matura.app.model.Profile
import com.matura.app.model.UiLanguage
import com.matura.app.ui.MenuScreen
import com.matura.app.ui.OptionsScreen
import com.matura.app.ui.Str
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Co widzi czytnik ekranu. Nie asercja "ma byc dobrze" — zrzut drzewa semantyki,
 * zeby dalo sie policzyc, ile wezlow klikalnych nie ma ZADNEJ etykiety.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class A11ySemanticsTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun menuScreen_semantics() {
        rule.setContent {
            MenuScreen(
                str = Str(UiLanguage.PL),
                profile = Profile(name = "Ala"),
                onPlay = {}, onSets = {}, onOptions = {},
                onAchievements = {}, onScores = {}, onExit = {},
            )
        }
        println("########## MENU ##########")
        println(rule.onRoot().printToString(maxDepth = 100))
        println("########## /MENU ##########")
    }

    @Test
    fun optionsScreen_semantics() {
        rule.setContent {
            OptionsScreen(
                str = Str(UiLanguage.PL),
                options = Options(),
                onBack = {}, onChange = {},
            )
        }
        println("########## OPCJE ##########")
        println(rule.onRoot().printToString(maxDepth = 100))
        println("########## /OPCJE ##########")
    }
}
