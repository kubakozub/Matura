package com.matura.app

import com.matura.app.data.Repository
import com.matura.app.data.SampleData
import com.matura.app.game.GameConfig
import com.matura.app.game.GameEngine
import com.matura.app.game.Matching
import com.matura.app.model.Direction
import com.matura.app.model.Entry
import com.matura.app.model.Options
import com.matura.app.model.Profile
import com.matura.app.model.SaveData
import com.matura.app.model.ScoreRecord
import com.matura.app.model.Texture
import com.matura.app.model.WordSet
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The save file is the one thing that runs on every cold start, so a broken
 * serializer would mean a crash before the menu ever appears. These tests exercise
 * the exact Json configuration the repository uses.
 */
class PersistenceTest {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Repository dostaje katalog w konstruktorze, wiec testy obchodza sie bez Contextu. */
    @get:Rule
    val katalog = TemporaryFolder()

    private fun plik(nazwa: String) = File(katalog.root, nazwa)

    @Test
    fun `save data survives a write and read round trip`() {
        val original = SaveData(
            profile = Profile(name = "Kuba", anonymous = true, gamesPlayed = 4, totalKills = 40, bestScore = 900, bestStreak = 7),
            options = Options(lives = 5, soundEnabled = false, texture = Texture.NIGHT, direction = Direction.RANDOM),
            sets = SampleData.builtInSets() + WordSet(
                id = "user-1",
                title = "Moje słówka",
                entries = listOf(Entry("dog", "pies")),
            ),
            scores = listOf(ScoreRecord("Kuba", "Moje słówka", 900, 5, 7, 40, 1_700_000_000_000L)),
        )

        val text = json.encodeToString(SaveData.serializer(), original)
        val restored = json.decodeFromString(SaveData.serializer(), text)

        assertEquals(original, restored)
        assertEquals(Texture.NIGHT, restored.options.texture)
        assertEquals(Direction.RANDOM, restored.options.direction)
        assertEquals("Moje słówka", restored.sets.last().title)
    }

    @Test
    fun `an empty save file falls back to defaults instead of throwing`() {
        val restored = json.decodeFromString(SaveData.serializer(), "{}")
        assertEquals(Profile(), restored.profile)
        assertEquals(Options(), restored.options)
        assertTrue(restored.sets.isEmpty())
    }

    @Test
    fun `a save file from a future version with extra keys still loads`() {
        val text = """{"profile":{"name":"X","somethingNew":123},"unknownSection":{"a":1}}"""
        val restored = json.decodeFromString(SaveData.serializer(), text)
        assertEquals("X", restored.profile.name)
    }

    @Test
    fun `every built-in set is immediately playable`() {
        val sets = SampleData.builtInSets()
        assertTrue(sets.isNotEmpty())
        for (set in sets) {
            assertTrue("${set.title} has no entries", set.entries.isNotEmpty())
            for (e in set.entries) {
                assertFalse("blank term in ${set.title}", e.term.isBlank())
                assertFalse("blank definition in ${set.title}", e.definition.isBlank())
                assertTrue(
                    "'${e.definition}' produces no typeable answer",
                    Matching.acceptedAnswers(e.definition).isNotEmpty(),
                )
            }
            val engine = GameEngine(set, GameConfig(autoSpawn = false))
            assertTrue("${set.title} is not playable", engine.isPlayable)
        }
    }

    @Test
    fun `shipped sets really do contain prefix collisions, so highlighting earns its keep`() {
        // The design document's example is two words whose translations share 'cie'.
        // The feature is only worth anything if the shipped vocabulary behaves the same
        // way, so assert it against real content rather than a hand-made fixture.
        var collisionsFound = 0
        for (set in SampleData.builtInSets()) {
            val answers = set.entries.map { it.term to Matching.acceptedAnswers(it.definition) }
            for ((_, a) in answers) {
                val prefix = a.firstOrNull()?.take(3) ?: continue
                if (prefix.length < 3) continue
                val matches = answers.count { (_, other) -> other.any { it.startsWith(prefix) } }
                if (matches > 1) collisionsFound++
            }
        }
        assertTrue(
            "no set has two entries whose answers share a three-letter prefix",
            collisionsFound > 0,
        )
    }

    @Test
    fun `built-in sets can be played end to end without diacritics on the keyboard`() {
        for (set in SampleData.builtInSets()) {
            val engine = GameEngine(set, GameConfig(autoSpawn = false))
            for (entry in set.entries) {
                engine.debugAddMonster(entry.term, entry.definition, 0.9f, 0.5f)
                // type the answer the way the A-Z keyboard would produce it
                val typed = Matching.normalize(entry.definition.substringBefore(','))
                engine.setTyped(typed)
                val result = engine.submit()
                assertTrue(
                    "'${entry.term}' -> '$typed' was not accepted in ${set.title}",
                    result is com.matura.app.game.SubmitResult.Fired,
                )
                repeat(200) { engine.tick(0.016f) }
            }
            assertEquals(set.entries.size, engine.kills)
        }
    }

    // --- Repository na prawdziwym katalogu -------------------------------------------------
    // Zapis byl nieatomowy (File.writeText nadpisywal w miejscu) i polykal bledy, wiec przerwana
    // operacja albo pelny dysk konczyly sie obcietym plikiem i cichym powrotem do danych
    // domyslnych. Ponizsze testy trzymaja te droge — kazdy odtwarza jedna awarie z audytu.

    private fun daneUcznia(imie: String) = SaveData(
        schemaVersion = Repository.SCHEMA_VERSION,
        profile = Profile(name = imie, anonymous = true, gamesPlayed = 3, totalKills = 30, bestScore = 800, bestStreak = 6),
        options = Options(lives = 5, soundEnabled = false, texture = Texture.NIGHT, direction = Direction.RANDOM),
        sets = SampleData.builtInSets() + WordSet(
            id = "user-1",
            title = "Moje słówka",
            entries = listOf(Entry("dog", "pies")),
        ),
        scores = listOf(ScoreRecord(imie, "Moje słówka", 800, 4, 6, 30, 1_700_000_000_000L)),
    )

    @Test
    fun `wlasne zestawy, profil, opcje i wyniki przezywaja zapis na dysk`() {
        val dane = daneUcznia("Kuba")

        assertTrue(Repository(katalog.root).save(dane))
        val odczytane = Repository(katalog.root).load()

        assertEquals(dane, odczytane)
        assertEquals("Moje słówka", odczytane.sets.last().title)
        assertEquals(Texture.NIGHT, odczytane.options.texture)
        assertEquals(1, odczytane.scores.size)
    }

    @Test
    fun `po zapisie nie zostaje plik tymczasowy, a poprzednia wersja laduje w kopii`() {
        val repo = Repository(katalog.root)
        assertTrue(repo.save(daneUcznia("Pierwszy")))
        assertTrue(repo.save(daneUcznia("Drugi")))

        assertFalse("plik tymczasowy przezyl zapis", plik(Repository.TEMP_NAME).exists())
        assertTrue(plik(Repository.FILE_NAME).exists())
        assertEquals("Drugi", Repository(katalog.root).load().profile.name)

        val kopia = json.decodeFromString(SaveData.serializer(), plik(Repository.BACKUP_NAME).readText())
        assertEquals("Pierwszy", kopia.profile.name)
    }

    @Test
    fun `uszkodzony plik nie kasuje danych - wracamy do kopii zapasowej`() {
        val repo = Repository(katalog.root)
        repo.save(daneUcznia("Kuba"))
        repo.save(daneUcznia("Kuba po drugiej grze"))

        // Tak wyglada przerwany zapis: ogon JSON-a nie doszedl na dysk.
        val obciety = plik(Repository.FILE_NAME).readText().take(120)
        plik(Repository.FILE_NAME).writeText(obciety)

        val odczytane = Repository(katalog.root).load()

        assertEquals("Kuba", odczytane.profile.name)
        assertEquals("Moje słówka", odczytane.sets.last().title)
        assertTrue("uszkodzony plik zniknal zamiast trafic do kwarantanny", plik(Repository.CORRUPT_NAME).exists())
        assertEquals(obciety, plik(Repository.CORRUPT_NAME).readText())
    }

    @Test
    fun `gdy i kopia jest zla, dostajemy dane domyslne, a uszkodzony plik zostaje na dysku`() {
        plik(Repository.FILE_NAME).writeText("{to nie jest JSON")
        plik(Repository.BACKUP_NAME).writeText("kopia tez nie")

        val odczytane = Repository(katalog.root).load()

        assertEquals(Profile(), odczytane.profile)
        assertEquals(Options(), odczytane.options)
        assertTrue(odczytane.sets.isNotEmpty())
        assertTrue("dane domyslne to same wbudowane zestawy", odczytane.sets.all { it.builtIn })
        assertEquals("{to nie jest JSON", plik(Repository.CORRUPT_NAME).readText())
        assertTrue("kopii tez nie wolno kasowac", plik(Repository.BACKUP_NAME).exists())
    }

    @Test
    fun `nieudany zapis nie udaje sukcesu`() {
        val repo = Repository(katalog.root)
        assertTrue(repo.save(daneUcznia("Kuba")))
        assertNull(repo.lastSaveError)

        // Katalog podstawiony pod nazwe pliku tymczasowego: strumienia nie da sie otworzyc,
        // wiec zapis wywraca sie w kontrolowany sposob. Prawa dostepu odpadaja jako sposob
        // na wymuszenie bledu — pod uzytkownikiem uprzywilejowanym nic nie blokuja.
        val blokada = plik(Repository.TEMP_NAME)
        assertTrue(blokada.mkdirs())
        File(blokada, "zajete").writeText("x")

        assertFalse("zapis zglosil sukces, choc nic nie zapisal", repo.save(daneUcznia("Nowe imie")))
        assertNotNull("blad zapisu przepadl bez sladu", repo.lastSaveError)
        // Pamiec nie ma prawa twierdzic, ze dane sa utrwalone — inaczej ekran pokazuje stan,
        // ktorego po restarcie nie bedzie.
        assertEquals("Kuba", repo.load().profile.name)
        assertEquals("Kuba", Repository(katalog.root).load().profile.name)

        File(blokada, "zajete").delete()
        blokada.delete()
        assertTrue(repo.save(daneUcznia("Nowe imie")))
        assertNull(repo.lastSaveError)
        assertEquals("Nowe imie", Repository(katalog.root).load().profile.name)
    }

    @Test
    fun `plik bez pola wersji dostaje numer schematu, a powtorne wczytanie nic nie zmienia`() {
        // Zapis sprzed wersjonowania: pola schemaVersion w pliku po prostu nie ma.
        plik(Repository.FILE_NAME).writeText("""{"profile":{"name":"Stary"},"sets":[],"scores":[]}""")

        val pierwsze = Repository(katalog.root).load()
        assertEquals("Stary", pierwsze.profile.name)
        assertEquals(Repository.SCHEMA_VERSION, pierwsze.schemaVersion)

        // load() celowo nie pisze po dysku, wiec migracja rusza ponownie przy kazdym starcie
        // az do pierwszego zapisu i musi dawac dokladnie to samo.
        assertEquals(pierwsze, Repository(katalog.root).load())

        val repo = Repository(katalog.root)
        assertEquals(repo.migrate(pierwsze), repo.migrate(repo.migrate(pierwsze)))
    }
}
