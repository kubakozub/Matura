package com.matura.app

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.matura.app.data.Repository
import com.matura.app.data.RepositoryHolder
import com.matura.app.model.Options
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * JEDEN OBIEKT NA PLIK ZAPISU.
 *
 * Repository czyta dysk raz i dalej pracuje na kopii w pamieci. Dwa obiekty nad tym samym
 * katalogiem to dwie kopie, a zapis ze starszej kasuje wszystko, co w miedzyczasie zapisala
 * nowsza. Pierwszy test pokazuje te awarie na golym Repository (dlatego w ogole istnieje
 * RepositoryHolder), pozostale pilnuja, ze holder oddaje ten sam obiekt kazdemu kontekstowi
 * aplikacji.
 *
 * Ze MainActivity naprawde z niego korzysta, sprawdza MainActivityNaEkranieTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JednoRepozytoriumTest {

    @get:Rule
    val innyKatalog = TemporaryFolder()

    private val aplikacja get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `dwa repozytoria nad jednym katalogiem gubia zapis, i po to jest holder`() {
        val starsze = Repository(aplikacja.filesDir)
        val nowsze = Repository(aplikacja.filesDir)
        starsze.load()

        assertTrue(nowsze.setOptions(nowsze.load().options.copy(lives = 7)))
        assertTrue(starsze.setProfile(starsze.load().profile.copy(name = "Ala")))

        assertEquals(
            "tak wyglada awaria: zapis ze starszej kopii cofnal ustawienie zapisane przez nowsza",
            Options().lives,
            Repository(aplikacja.filesDir).load().options.lives,
        )
    }

    @Test
    fun `aplikacja i kazdy jej ekran dostaja to samo repozytorium`() {
        val zAplikacji = RepositoryHolder.of(aplikacja)

        Robolectric.buildActivity(MainActivity::class.java).use { pierwszy ->
            Robolectric.buildActivity(MainActivity::class.java).use { drugi ->
                assertSame(zAplikacji, RepositoryHolder.of(pierwszy.get()))
                assertSame(zAplikacji, RepositoryHolder.of(drugi.get()))
            }
        }
    }

    @Test
    fun `zapis przez holder widzi kazdy, kto o niego prosi`() {
        val pierwsze = RepositoryHolder.of(aplikacja)
        pierwsze.load()

        val drugie = RepositoryHolder.of(aplikacja)
        assertTrue(drugie.setOptions(drugie.load().options.copy(lives = 7)))
        assertTrue(pierwsze.setProfile(pierwsze.load().profile.copy(name = "Ala")))

        val naDysku = Repository(aplikacja.filesDir).load()
        assertEquals(7, naDysku.options.lives)
        assertEquals("Ala", naDysku.profile.name)
    }

    @Test
    fun `inny katalog zapisu to inne repozytorium, a pierwsze zostaje soba`() {
        val gdzieIndziej = object : ContextWrapper(aplikacja) {
            override fun getFilesDir(): File = innyKatalog.root
        }
        val glowne = RepositoryHolder.of(aplikacja)

        assertNotSame(glowne, RepositoryHolder.of(gdzieIndziej))
        assertSame(glowne, RepositoryHolder.of(aplikacja))
        assertSame(RepositoryHolder.of(gdzieIndziej), RepositoryHolder.of(gdzieIndziej))
    }
}
