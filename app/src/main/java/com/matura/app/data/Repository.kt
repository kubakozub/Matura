package com.matura.app.data

import android.content.Context
import android.util.Log
import com.matura.app.learn.Attempt
import com.matura.app.learn.EntryId
import com.matura.app.learn.Srs
import com.matura.app.model.Direction
import com.matura.app.model.Entry
import com.matura.app.model.Profile
import com.matura.app.model.Options
import com.matura.app.model.SaveData
import com.matura.app.model.ScoreRecord
import com.matura.app.model.WordProgress
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

    /**
     * Zapis zestawu z ZACHOWANIEM tozsamosci wpisow.
     *
     * Edytor sklada liste wpisow od zera z wierszy ekranu (Screens.kt), wiec przychodzi tu
     * zestaw, ktorego wpisy nie maja identyfikatorow. Gdyby zapisac go wprost, kazde otwarcie
     * i zapisanie wlasnego zestawu — nawet bez zmiany — kasowaloby cala nauke w nim zawarta.
     * Dopasowanie do poprzedniej wersji robi EntryId.reconcile; tam tez opisane sa jego granice.
     */
    fun upsertSet(set: WordSet) = update { d ->
        val idx = d.sets.indexOfFirst { it.id == set.id }
        val poprzednie = if (idx >= 0) d.sets[idx].entries else emptyList()
        val zTozsamoscia = set.copy(
            entries = EntryId.reconcile(poprzednie, set.entries, builtInSet = set.builtIn),
        )
        val sets = if (idx >= 0) {
            d.sets.toMutableList().also { it[idx] = zTozsamoscia }
        } else {
            d.sets + zTozsamoscia
        }
        d.copy(sets = sets)
    }

    /** Stan nauki jednego celu; brak wpisu znaczy "pudelko 0", a nie blad. */
    /** Zapis albo skasowanie przerwanej sesji. null konczy sesje. */
    fun setSesja(stan: com.matura.app.learn.StanSesji?): Boolean = save(load().copy(sesja = stan))

    fun progressOf(entryId: String, direction: Direction): WordProgress =
        Srs.progressOf(load().progress, entryId, direction)

    /**
     * Zapisuje wynik jednej proby. Czas wchodzi PARAMETREM, bo caly harmonogram ma byc
     * testowalny na granicy doby — patrz [Srs].
     *
     * @return to, co [save]; true takze wtedy, gdy proba niczego nie zmienila (przerwana proba,
     *   kolejne trafienie tego samego dnia) — wtedy nie ma po co ruszac dysku.
     */
    @Synchronized
    fun recordAttempt(attempt: Attempt, nowMs: Long): Boolean {
        val przed = load()
        val po = Srs.applyTo(przed.progress, attempt, Srs.dayOf(nowMs))
        // applyTo oddaje TE SAMA mape, gdy stan sie nie zmienil - stad porownanie tozsamosci.
        if (po === przed.progress) return true
        return save(przed.copy(progress = po))
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
        var out = data
        if (out.schemaVersion < 2) out = nadajIdentyfikatoryWpisom(out)
        return out.copy(schemaVersion = SCHEMA_VERSION)
    }

    /**
     * Krok 1 -> 2: kazdy wpis dostaje trwaly identyfikator (patrz EntryId).
     *
     * Czego ten krok NIE robi, i to jest w nim najwazniejsze:
     *
     *  - nie tworzy ANI JEDNEGO wpisu postepu. Kusi, zeby ze starych punktow za gry wyliczyc
     *    "opanowane slowa", ale punkt zdobywa sie tez za szybkosc i za trafienie potworka, a
     *    zestaw gralo sie w calosci — z takich danych nie wynika, ktore slowo uczen umie.
     *    Aplikacja wolalaby wtedy "utrwalone" o slowie, ktorego uczen nigdy nie napisal.
     *    Wszystkie slowa startuja wiec ze stanem "brak danych" (pudelko 0);
     *
     *  - nie rusza profilu, ustawien, wynikow ani wlasnych zestawow — zmienia sie wylacznie
     *    puste pole id we wpisach;
     *
     *  - nie sprzata mapy postepu. Postep wisi na identyfikatorze wpisu, a nie na zestawie,
     *    wiec przebudowa katalogu nie ma jak go uniewaznic: slowo, ktore znika ze slownika,
     *    zachowuje swoja historie i odzyskuje ja, jesli wroci.
     *
     * Wynik musi byc WYLICZALNY, bo load() celowo nie pisze po dysku i migracja biegnie od nowa
     * przy kazdym starcie az do pierwszego zapisu. Stad brak losowosci: wpisy wlasne dostaja
     * identyfikator liczony z zestawu i tresci (EntryId.userIdFor), a nie losowany.
     */
    private fun nadajIdentyfikatoryWpisom(data: SaveData): SaveData {
        if (data.sets.none { s -> s.entries.any { it.id.isBlank() } }) return data

        val katalog = katalogPoTresci()
        return data.copy(
            sets = data.sets.map { set ->
                if (set.entries.none { it.id.isBlank() }) return@map set
                val powtorzenia = HashMap<String, Int>()
                set.copy(
                    entries = set.entries.map { e ->
                        val klucz = EntryId.contentKey(e.term, e.definition)
                        val nr = powtorzenia[klucz] ?: 0
                        powtorzenia[klucz] = nr + 1
                        when {
                            e.id.isNotBlank() -> e
                            // Zapis trzyma WLASNA kopie zestawow wbudowanych, wiec moze w niej
                            // lezec brzmienie sprzed korekty tlumaczenia. Katalog jest zrodlem
                            // prawdy; gdy pary w nim nie ma, EntryId.builtIn liczy ten sam skrot
                            // z tresci (i sam przepuszcza znane korekty przez tablice aliasow).
                            set.builtIn -> e.copy(
                                id = katalog[klucz] ?: EntryId.builtIn(e.term, e.definition),
                            )
                            else -> e.copy(id = EntryId.userIdFor(set.id, e.term, e.definition, nr))
                        }
                    },
                )
            },
        )
    }

    /** Tresc wpisu katalogowego -> jego identyfikator. Pierwszy wygrywa: te same pary powtarzaja
     *  sie miedzy dzialami i MAJA dzielic jeden identyfikator (a wiec i jeden postep). */
    private fun katalogPoTresci(): Map<String, String> {
        val out = HashMap<String, String>()
        for (set in SampleData.builtInSets()) {
            for (e in set.entries) {
                if (e.id.isBlank()) continue
                val klucz = EntryId.contentKey(e.term, e.definition)
                if (!out.containsKey(klucz)) out[klucz] = e.id
            }
        }
        return out
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

        /**
         * Biezaca wersja schematu zapisu; 0 oznacza plik sprzed wersjonowania. Patrz [migrate].
         *
         * 1 -> 2: kazdy wpis ma trwaly identyfikator (Entry.id), a zapis ma mape postepu
         * per slowo (SaveData.progress).
         */
        const val SCHEMA_VERSION = 2
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
