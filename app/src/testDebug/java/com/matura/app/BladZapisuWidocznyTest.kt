package com.matura.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.matura.app.data.Repository
import com.matura.app.model.UiLanguage
import com.matura.app.ui.MaturaApp
import com.matura.app.ui.Str
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * DLACZEGO TEN TEST ISTNIEJE
 *
 * [Repository.save] zwracalo `false`, a [Repository.lastSaveError] trzymalo wyjatek — i nikt
 * tego nie czytal. Trzynascie wywolan w App.kt wyrzucalo wynik, a `lastSaveError` mialo w
 * calym repozytorium wylacznie wolajacych z testow. Dla ucznia wygladalo to tak, jakby
 * dotkniecie nie mialo skutku: cache jest odswiezany dopiero po UDANYM zapisie, wiec ekran
 * po cichu wracal do poprzedniego stanu, a nauka z tej rundy nie przetrwala zamkniecia gry.
 *
 * Tu sprawdzamy OBIE strony, bo test, ktory nie potrafi upasc, nic nie dowodzi:
 * przy zablokowanym zapisie komunikat MUSI byc, przy sprawnym NIE MOZE.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class BladZapisuWidocznyTest {

    @get:Rule val rule = createComposeRule()
    @get:Rule val katalog = TemporaryFolder()

    private val str = Str(UiLanguage.PL)

    /**
     * Katalog podstawiony pod nazwe pliku tymczasowego — strumienia nie da sie otworzyc.
     * Prawa dostepu odpadaja jako sposob na wymuszenie bledu: pod uzytkownikiem
     * uprzywilejowanym nic nie blokuja. Ta sama sztuczka co w PersistenceTest.
     */
    private fun zablokujZapis() {
        val blokada = File(katalog.root, Repository.TEMP_NAME)
        blokada.mkdirs()
        File(blokada, "zajete").writeText("x")
    }

    @Test
    fun `nieudany zapis mowi uczniowi, ze postep nie przetrwa`() {
        zablokujZapis()
        val repo = Repository(katalog.root)
        rule.setContent { MaturaApp(repo = repo, onExitApp = {}) }

        // Pierwsze dotkniecie w aplikacji JUZ zapisuje (setProfile z imieniem).
        rule.onNodeWithContentDescription(str.playAnonymously).performClick()
        rule.waitForIdle()

        rule.onNodeWithText(str.saveFailed).assertIsDisplayed()
    }

    @Test
    fun `udany zapis nie straszy komunikatem`() {
        val repo = Repository(katalog.root)
        rule.setContent { MaturaApp(repo = repo, onExitApp = {}) }

        rule.onNodeWithContentDescription(str.playAnonymously).performClick()
        rule.waitForIdle()

        assertEquals(
            "komunikat o bledzie zapisu pokazal sie, choc zapis sie udal",
            0,
            rule.onAllNodesWithText(str.saveFailed).fetchSemanticsNodes().size,
        )
    }
}
