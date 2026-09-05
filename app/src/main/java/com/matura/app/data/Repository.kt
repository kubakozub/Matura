package com.matura.app.data

import android.content.Context
import android.util.Log
import com.matura.app.model.Entry
import com.matura.app.model.Profile
import com.matura.app.model.Options
import com.matura.app.model.SaveData
import com.matura.app.model.ScoreRecord
import com.matura.app.model.WordSet
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Local persistence. Everything is one JSON document in the app's private files
 * directory — the design document's accounts, shared sets and global leaderboards
 * need a server, which this build deliberately does not have, so "play anonymously"
 * is the only path and all data stays on the device.
 *
 * Zapis idzie przez plik tymczasowy i zmiane nazwy, bo File.writeText nadpisuje w
 * miejscu: przerwanie w polowie (system ubija aplikacje, konczy sie bateria) zostawialo
 * obciety JSON, ktory przy nastepnym starcie nie parsowal sie i po cichu cofal ucznia
 * do danych domyslnych — z calym postepem i wlasnymi zestawami w srodku.
 *
 * Katalog jest wstrzykiwany, zeby test jednostkowy mial wlasny (Context.filesDir wymaga
 * emulatora albo Robolectrica, a te testy maja byc zwyklym JVM-em).
 */
class Repository(private val dir: File) {

    constructor(context: Context) : this(context.filesDir)

    private val file = File(dir, FILE_NAME)

    /** Ostatnia wersja, ktora w calosci trafila na dysk. Jedyne wejscie awaryjne, gdy glowny plik padnie. */
    private val backup = File(dir, BACKUP_NAME)

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Volatile
    private var cache: SaveData? = null

    /**
     * Wyjatek z ostatniego nieudanego zapisu; null, gdy ostatni zapis sie udal.
     *
     * Swiadomie zwykle pole, a nie obserwowalny strumien: pakiet data nie ma dzis ani
     * korutyn, ani Compose, wiec StateFlow albo mutableStateOf wciagalyby tu zaleznosc,
     * ktorej w tej warstwie nie ma. UI i tak po KAZDEJ zmianie wola load() (App.refresh()),
     * wiec przy okazji moze zajrzec tutaj. [save] dodatkowo zwraca false, dla wolajacych,
     * ktorzy chca zareagowac natychmiast.
     */
    @Volatile
    var lastSaveError: Throwable? = null
        private set

    @Synchronized
    fun load(): SaveData {
        cache?.let { return it }

        val data = readBestAvailable()

        val withBuiltIns = if (data.sets.none { it.builtIn }) {
            data.copy(sets = SampleData.builtInSets() + data.sets)
        } else data

        val migrated = migrate(withBuiltIns)
        cache = migrated
        return migrated
    }

    /**
     * @return true, gdy dane naprawde wyladowaly na dysku.
     *
     * Cache aktualizujemy DOPIERO po udanym zapisie. Wczesniej bylo odwrotnie, wiec po
     * nieudanym zapisie pamiec twierdzila, ze dane sa utrwalone: ekran pokazywal stan,
     * ktorego na dysku nie bylo, az do restartu aplikacji.
     */
    @Synchronized
    fun save(data: SaveData): Boolean {
        val text = try {
            json.encodeToString(SaveData.serializer(), data)
        } catch (e: Exception) {
            fail("nie udalo sie zserializowac zapisu", e)
            return false
        }
        return try {
            writeAtomically(text)
            cache = data
            lastSaveError = null
            true
        } catch (e: Exception) {
            fail("nie udalo sie zapisac $FILE_NAME", e)
            false
        }
    }

    /** @return to, co [save] — false oznacza, ze zmiana NIE przetrwa restartu. */
    fun update(block: (SaveData) -> SaveData): Boolean = save(block(load()))

    fun upsertSet(set: WordSet) = update { d ->
        val idx = d.sets.indexOfFirst { it.id == set.id }
        val sets = if (idx >= 0) d.sets.toMutableList().also { it[idx] = set } else d.sets + set
        d.copy(sets = sets)
    }

    fun deleteSet(id: String) = update { d -> d.copy(sets = d.sets.filterNot { it.id == id && !it.builtIn }) }

    fun setOptions(options: Options) = update { it.copy(options = options) }

    fun setProfile(profile: Profile) = update { it.copy(profile = profile) }

    /** Records the outcome of a finished run and rolls the profile counters forward. */
    fun recordGame(record: ScoreRecord, setId: String) = update { d ->
        val p = d.profile
        d.copy(
            profile = p.copy(
                gamesPlayed = p.gamesPlayed + 1,
                totalKills = p.totalKills + record.kills,
                bestScore = maxOf(p.bestScore, record.score),
                bestStreak = maxOf(p.bestStreak, record.bestStreak),
            ),
            // Leaderboard lokalny: top 50 osobno dla kazdego trybu, zeby wyniki
            // z jednego trybu nie wypychaly pozostalych.
            scores = (d.scores + record)
                .groupBy { it.mode }
                .flatMap { (_, list) -> list.sortedByDescending { it.score }.take(50) }
                .sortedByDescending { it.score },
            sets = d.sets.map { if (it.id == setId) it.copy(timesPlayed = it.timesPlayed + 1) else it },
        )
    }

    /**
     * Podnosi odczytane dane do [SCHEMA_VERSION].
     *
     * Musi byc IDEMPOTENTNA, bo migracja biegnie przy kazdym starcie az do pierwszego
     * udanego zapisu (load() celowo nie pisze po dysku) — gwarantuje to warunek nizej,
     * a kolejne kroki maja byc pisane tak, zeby dopisywac sie w tym samym schemacie:
     *
     *     if (out.schemaVersion < 2) out = ...krok 1 -> 2...
     *
     * Wersja 0 = zapis sprzed wersjonowania (pole nie istnialo w pliku, wiec deserializacja
     * daje wartosc domyslna). Krok 0 -> 1 to samo nadanie numeru: uklad danych sie nie zmienil,
     * zmienilo sie tylko to, ze od teraz umiemy go rozpoznac.
     */
    fun migrate(data: SaveData): SaveData {
        if (data.schemaVersion >= SCHEMA_VERSION) return data
        return data.copy(schemaVersion = SCHEMA_VERSION)
    }

    /**
     * Kolejnosc jest istotna: plik glowny, potem kopia, dopiero na koncu dane domyslne.
     * Przedtem kazdy blad parsowania szedl od razu do danych domyslnych, a pierwszy zapis
     * (juz samo podanie imienia na ekranie startowym) nadpisywal uszkodzony plik w miejscu
     * i kasowal resztki, z ktorych dalo sie jeszcze cokolwiek wylowic.
     */
    private fun readBestAvailable(): SaveData {
        read(file)?.let { return it }

        // Skoro plik glowny istnieje, a odczyt zwrocil null, to znaczy, ze sie nie sparsowal.
        if (file.exists()) quarantineMainFile()

        read(backup)?.let {
            Log.w(TAG, "odczyt z kopii $BACKUP_NAME — $FILE_NAME byl nie do odczytania")
            return it
        }
        if (backup.exists()) {
            // Uszkodzonej kopii tez nie kasujemy; ma juz wlasna nazwe, wiec zapis jej nie dotknie
            // (rotacja nadpisze ja dopiero przy DRUGIM udanym zapisie, gdy bedzie co rotowac).
            Log.w(TAG, "kopia $BACKUP_NAME tez nie do odczytania — start od danych domyslnych")
        }
        return SaveData(sets = SampleData.builtInSets())
    }

    /** Zwraca null, gdy pliku nie ma albo nie daje sie sparsowac. Blad nie ma prawa zginac bez logu. */
    private fun read(source: File): SaveData? {
        if (!source.exists()) return null
        return try {
            json.decodeFromString(SaveData.serializer(), source.readText())
        } catch (e: Exception) {
            Log.w(TAG, "${source.name}: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /**
     * Uszkodzonego pliku NIE kasujemy — to jedyna kopia postepu ucznia, a z obcietego JSON-a
     * da sie recznie wylowic wlasne zestawy. Odkladamy go obok, pod nazwa, ktorej zapis nie tyka.
     */
    private fun quarantineMainFile() {
        val target = File(dir, CORRUPT_NAME)
        val moved = file.renameTo(target) || runCatching {
            file.copyTo(target, overwrite = true)
            file.delete()
        }.getOrDefault(false)
        if (moved) Log.w(TAG, "uszkodzony $FILE_NAME odlozony jako $CORRUPT_NAME")
        else Log.w(TAG, "nie udalo sie odlozyc uszkodzonego $FILE_NAME — zostaje na miejscu")
    }

    /**
     * Zapis dwustopniowy: pelna tresc do pliku tymczasowego, potem zmiana nazwy. Zmiana nazwy
     * jest w systemie plikow niepodzielna, wiec plik docelowy w kazdej chwili jest albo caly
     * stary, albo caly nowy — nigdy obciety w polowie.
     */
    private fun writeAtomically(text: String) {
        val tmp = File(dir, TEMP_NAME)
        try {
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                // Bez sync() zmiana nazwy potrafi trafic na dysk przed trescia: po zaniku
                // zasilania zostaje plik o wlasciwej nazwie i zerowej dlugosci.
                out.fd.sync()
            }
            // Poprzednia — kompletna — wersja idzie do kopii ZANIM podmienimy plik glowny.
            if (file.exists() && !file.renameTo(backup)) {
                Log.w(TAG, "nie udalo sie odswiezyc $BACKUP_NAME — zostaje starsza kopia")
            }
            if (!tmp.renameTo(file)) {
                // Tresc jest wtedy w $BACKUP_NAME i load() sam po nia siegnie, ale zapis
                // uznajemy za nieudany — wolajacy ma sie o tym dowiedziec.
                throw IOException("zmiana nazwy $TEMP_NAME -> $FILE_NAME nie powiodla sie")
            }
        } finally {
            // Po udanej zamianie tmp juz nie istnieje. Po nieudanej nie zostawiamy smiecia:
            // niedopisany plik tymczasowy mylilby przy recznym odzyskiwaniu danych.
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun fail(what: String, e: Exception) {
        lastSaveError = e
        Log.w(TAG, "$what: ${e.javaClass.simpleName}: ${e.message}")
    }

    companion object {
        private const val TAG = "MaturaRepo"

        // Nazwy sa czescia kontraktu: opieraja sie na nich testy i instrukcja recznego
        // odzyskiwania danych z katalogu prywatnego aplikacji.
        const val FILE_NAME = "matura.json"
        const val BACKUP_NAME = FILE_NAME + ".bak"
        const val TEMP_NAME = FILE_NAME + ".tmp"
        const val CORRUPT_NAME = FILE_NAME + ".uszkodzony"

        /** Biezaca wersja schematu zapisu; 0 oznacza plik sprzed wersjonowania. Patrz [migrate]. */
        const val SCHEMA_VERSION = 1
    }
}

object SampleData {

    /**
     * Starter content: the thematic sets covering the Polish matura podstawowa
     * vocabulary. Generated into [BuiltInSets] from the curated word list, with a
     * hard rule that every term and every translation is at most two words.
     */
    fun builtInSets(): List<WordSet> = BuiltInSets.all()

    /** Kept so a set's shape stays obvious when reading this file. */
    @Suppress("unused")
    private val example = Entry("agile", "zręczny")
}
