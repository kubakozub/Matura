package com.matura.app.data

import android.content.Context
import android.util.Log
import com.matura.app.learn.Attempt
import com.matura.app.learn.Doliczenie
import com.matura.app.learn.EntryId
import com.matura.app.learn.Nagrody
import com.matura.app.learn.Odznaki
import com.matura.app.learn.Srs
import com.matura.app.learn.Wplyw
import com.matura.app.learn.Zaliczenie
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
 * directory: the design document's accounts, shared sets and global leaderboards
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
        // Zwarty zapis. Dokument jest przepisywany CALY po kazdej odpowiedzi, a wciecia
        // kosztowaly w nim wiecej niz dane: zmierzone na pelnym katalogu (102 zestawy,
        // 2084 wpisy) 376 824 B z prettyPrint wobec 153 650 B bez — 223 174 B zapisu z
        // fsync na kazda odpowiedz, za samo formatowanie. Czytelnosc przy recznym
        // odzyskiwaniu danych nie ginie: to dalej JSON, tylko w jednej linii.
        //
        // Reszta dokumentu (153 149 B) to tresc zestawow wbudowanych. Zostaje w pliku
        // SWIADOMIE: ekran ZESTAWY pozwala otworzyc wbudowany zestaw w edytorze, wiec
        // odtwarzanie go z katalogu przy kazdym odczycie kasowaloby uczniowi poprawki.
        // Patrz [odswiezZestawyWbudowane] — tam ten sam koszt jest opisany dla migracji,
        // ktora biegnie RAZ. Dane samego ucznia to 501 B.
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Volatile
    private var cache: SaveData? = null

    /**
     * Slownik gry DOLACZONY DO APLIKACJI, a nie zestawy z zapisu: te uczen moze otworzyc
     * w edytorze, skrocic albo dopisac do nich wlasne wiersze. Po katalogu z aplikacji licza
     * sie odznaki za slowa ([Odznaki.liczniki]) i po nim poznajemy slowo "wlasne" przy
     * dziennym limicie punktow. Leniwie i raz na instancje: SampleData.builtInSets() sklada
     * ponad dwa tysiace wpisow od nowa przy kazdym wywolaniu, a pytamy po kazdej odpowiedzi.
     */
    private val katalog: List<WordSet> by lazy { SampleData.builtInSets() }

    private val idKatalogu: Set<String> by lazy {
        val out = HashSet<String>()
        for (set in katalog) for (e in set.entries) if (e.id.isNotBlank()) out += e.id
        out
    }

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
     * @param sladWSesji co ta odpowiedz znaczy dla trwajacej dziennej sesji; null, gdy runda
     *   nie zostala uruchomiona z sesji. Slad idzie TYM SAMYM zapisem co postep, bo osobne
     *   wywolanie [setSesja] podwajaloby liczbe zapisow calego dokumentu po kazdej odpowiedzi.
     *   Regula, co sie liczy, zostaje w [com.matura.app.learn.Sesja] — tutaj wchodzi wynikiem.
     *
     * To jest tez JEDYNE miejsce, w ktorym rosnie dorobek ucznia ([SaveData.dorobek]): punkty
     * nauki, cel dnia i seria. Dorobek idzie tym samym zapisem co postep, z dwoch powodow.
     * Pierwszy jest ten sam, co przy sladzie sesji: jeden zapis calego dokumentu na odpowiedz.
     * Drugi: przy dwoch zapisach przerwanie miedzy nimi zostawialoby awans bez punktow albo
     * punkty bez awansu, a dorobek ma sie zgadzac z harmonogramem co do slowa. Nieudany zapis
     * nie zostawia punktow takze w pamieci, bo [save] rusza cache dopiero po sukcesie.
     *
     * @param naWplyw dostaje to, co proba zrobila w ksiedze ([Wplyw]), i TYLKO po udanym
     *   zapisie; takze wtedy, gdy dzienny limit zabral zaliczeniu punkty (xp rowne zero).
     *   Proba bez zaliczenia nie wola go wcale. Ekran gry zbiera z tego rozpisanie rundy
     *   ([Nagrody.roznica]), wiec rozpisanie nie moze wyprzedzic dysku. Stoi na koncu listy
     *   parametrow: slad sesji podaje sie odtad w nawiasie albo po nazwie, nie lambda za
     *   nawiasem.
     *
     * @return to, co [save]; true takze wtedy, gdy proba niczego nie zmienila (przerwana proba,
     *   kolejne trafienie tego samego dnia) — wtedy nie ma po co ruszac dysku.
     */
    @Synchronized
    fun recordAttempt(
        attempt: Attempt,
        nowMs: Long,
        sladWSesji: ((com.matura.app.learn.StanSesji) -> com.matura.app.learn.StanSesji)? = null,
        naWplyw: ((Wplyw) -> Unit)? = null,
    ): Boolean {
        val przed = load()
        val dzien = Srs.dayOf(nowMs)
        val po = Srs.applyTo(przed.progress, attempt, dzien)
        val sesjaPrzed = przed.sesja
        val sesjaPo =
            if (sesjaPrzed != null && sladWSesji != null) sladWSesji(sesjaPrzed) else sesjaPrzed
        // StanSesji porownujemy WARTOSCIA, bo Sesja oddaje kopie takze wtedy, gdy nic sie nie
        // zmienilo (data class copy zawsze tworzy nowy obiekt) - tozsamosc dawalaby tu zapis
        // po kazdej odpowiedzi.
        //
        // Dorobek liczy sie dopiero ZA tym warunkiem. Proba, ktora nie ruszyla harmonogramu,
        // nie ma jak niczego zarobic (zaliczenie to przejscie w harmonogramie), wiec nie moze
        // tez byc powodem, zeby przepisywac caly dokument.
        if (bezZmianyPozaKsiegowa(przed.progress, po, attempt) && sesjaPo == sesjaPrzed) return true
        val zPostepem = przed.copy(progress = po, sesja = sesjaPo)
        val doliczenie = doliczProbe(przed, zPostepem, attempt, dzien)
        val zapisane =
            save(if (doliczenie == null) zPostepem else zPostepem.copy(dorobek = doliczenie.dorobek))
        if (zapisane && doliczenie != null) naWplyw?.invoke(doliczenie.wplyw)
        return zapisane
    }

    /**
     * Dorobek po jednej probie: zaliczenie, a po nim odznaki; null, gdy proba nie dala
     * zaliczenia (pomylka, podpowiedz, sam slad sesji).
     *
     * Reguly mieszkaja w [Nagrody] i [Odznaki]; tutaj sa tylko dwie rzeczy, ktorych tamte nie
     * widza, oraz kolejnosc. Po pierwsze, czy slowo jest WLASNE: spoza slownika dolaczonego
     * do aplikacji. Po drugie, czy zaliczenie jest NAPRAWA: pierwszym utrwaleniem slowa ze
     * slownika, przy ktorym uczen wczesniej sie pomylil (licznik pomylek sprzed tej proby).
     * Odznaki licza sie z zapisu JUZ PO doliczeniu, bo czesc z nich patrzy na liczniki samego
     * dorobku (dni nauki, seria, powtorki). Dzien proby jest dniem i zaliczenia, i odznaki.
     *
     * Bez zaliczenia odznak nie przeliczamy. To nie jest skrot kosztem poprawnosci: liczniki
     * odznak zaleza od szczytow pudelek i od dorobku, a jedno i drugie rosnie tylko razem
     * z zaliczeniem, wiec nie ma czego szukac. Oszczedza za to przejscie po calym katalogu
     * (ponad dwa tysiace hasel w obu kierunkach) po kazdej blednej odpowiedzi w rundzie.
     * Z tego samego powodu liczenie odpada, gdy uczen ma juz wszystkie odznaki.
     */
    private fun doliczProbe(
        przed: SaveData,
        po: SaveData,
        attempt: Attempt,
        dzien: Int,
    ): Doliczenie? {
        val klucz = Srs.key(attempt.entryId, attempt.direction)
        val stanPrzed = przed.progress[klucz] ?: WordProgress()
        val zaliczenie =
            Nagrody.zaliczenie(stanPrzed, po.progress[klucz] ?: WordProgress(), attempt)
                ?: return null
        val wlasne = attempt.entryId !in idKatalogu
        val naprawa = zaliczenie == Zaliczenie.UTRWALENIE && stanPrzed.mistakes > 0 && !wlasne
        val doliczenie = Nagrody.dolicz(
            po.dorobek, zaliczenie, dzien, Nagrody.celDnia(po.options.celDnia), wlasne, naprawa,
        )
        val ksiega = doliczenie.dorobek
        if (Odznaki.komplet(ksiega)) return doliczenie
        val liczniki = Odznaki.liczniki(po.copy(dorobek = ksiega), katalog)
        return doliczenie.copy(dorobek = Odznaki.odblokuj(ksiega, liczniki, dzien))
    }

    /**
     * Czy proba zmienila w harmonogramie cokolwiek poza polami KSIEGOWYMI, czyli
     * [WordProgress.lastTask] i [WordProgress.lastAttemptId].
     *
     * Po co to jest: obietnica "kolejne trafienie tego samego dnia nie rusza dysku" nie
     * dzialala ani razu. Ekrany gry skladaja znacznik proby z zegara ("$entryId#$nowMs"), wiec
     * nawet gdy harmonogram oddawal stan co do bitu ten sam, sam znacznik czynil mape nowa i
     * caly dokument (kilkaset kilobajtow z fsync) szedl na dysk w srodku rundy. A wlasnie tak
     * wyglada typowa runda: to samo slowo wraca kilka razy, a po awansie nic juz nie zmienia.
     *
     * Pominiecie zapisu jest bezpieczne, bo oba pola sa ksiegowe: [WordProgress.lastAttemptId]
     * strzeze przed dwukrotnym przetworzeniem TEJ SAMEJ proby, a proba, ktora niczego nie
     * zmienila, przetworzona drugi raz znowu niczego nie zmieni.
     *
     * [Srs.applyTo] rusza wylacznie klucz tej jednej proby, wiec wystarczy porownac ten klucz.
     *
     * Trzecie pole pomijane w porownaniu to [WordProgress.szczyt]. Gdy pudelko stoi w miejscu,
     * szczyt moze sie zmienic tylko w jeden sposob: stan zlozony poza [Srs] mial go nizej niz
     * pudelko i [Srs.apply] wlasnie go wyrownal. To nie jest zmiana w harmonogramie, a
     * wyrownanie liczy sie od nowa przy kazdej probie, wiec nieutrwalone niczego nie gubi.
     * Awans podnosi szczyt zawsze razem z pudelkiem i tego ten warunek nie przepusci.
     */
    private fun bezZmianyPozaKsiegowa(
        przed: Map<String, WordProgress>,
        po: Map<String, WordProgress>,
        attempt: Attempt,
    ): Boolean {
        if (po === przed) return true
        if (attempt.entryId.isBlank() || attempt.direction == Direction.RANDOM) return false
        val klucz = Srs.key(attempt.entryId, attempt.direction)
        val stary = przed[klucz] ?: WordProgress()
        val nowy = po[klucz] ?: WordProgress()
        return nowy.copy(
            lastTask = stary.lastTask,
            lastAttemptId = stary.lastAttemptId,
            szczyt = stary.szczyt,
        ) == stary
    }

    fun deleteSet(id: String) = update { d -> d.copy(sets = d.sets.filterNot { it.id == id && !it.builtIn }) }

    fun setOptions(options: Options) = update { it.copy(options = options) }

    fun setProfile(profile: Profile) = update { it.copy(profile = profile) }

    /**
     * Records the outcome of a finished run and rolls the profile counters forward.
     *
     * @param doRankingu false dla rundy, ktora nie idzie o wynik (spokojna runda sesji dziennej,
     *   trudne slowa, poprawka). Liczba gier, liczba pokonanych i licznik rozgrywek zestawu
     *   rosna wtedy dalej, ale wynik NIE trafia na liste wynikow i nie podnosi rekordow profilu
     *   (najlepszy wynik, najdluzsza seria): runda z zapasem zyc i bez presji czasu nie moze
     *   ustawiac poprzeczki rundom granym na serio. Skutek uboczny, zamierzony: taka runda nie
     *   liczy sie tez do odznaki za cztery tryby, bo ta czyta liste wynikow.
     *
     * @param nowMs chwila konca gry; daje dzien odznakom zdobytym w tej grze. Wartosc domyslna
     *   jest tylko po to, zeby wywolanie bez czasu (rekord, zestaw, ewentualnie ranking)
     *   dzialalo bez poprawek; testy podaja czas jawnie, jak wszedzie w tej klasie.
     *
     * @param potwierdzOdznaki ekran wyniku za chwile pokaze odznaki czekajace na pokazanie
     *   ([com.matura.app.model.Dorobek.odznakiNowe]), wiec ten sam zapis je odhacza. Zdobyte
     *   w tej grze ekran i tak znajdzie: sa w ksiedze, a nie bylo ich w stanie sprzed rundy
     *   ([Nagrody.roznica]).
     *
     * Po grze stempluja sie odznaki, ktore licza sie z profilu i z listy wynikow (rozegrane
     * gry, najlepszy wynik, liczba trybow). PUNKTOW NAUKI gra nie dopisuje: wynik rundy placi
     * takze za szybkosc i za trafienie potworka, a ksiega dorobku rosnie wylacznie z prob
     * ([recordAttempt]).
     */
    fun recordGame(
        record: ScoreRecord,
        setId: String,
        doRankingu: Boolean = true,
        nowMs: Long = System.currentTimeMillis(),
        potwierdzOdznaki: Boolean = false,
    ) = update { d ->
        val p = d.profile
        val poGrze = d.copy(
            profile = p.copy(
                gamesPlayed = p.gamesPlayed + 1,
                totalKills = p.totalKills + record.kills,
                bestScore = if (doRankingu) maxOf(p.bestScore, record.score) else p.bestScore,
                bestStreak = if (doRankingu) maxOf(p.bestStreak, record.bestStreak) else p.bestStreak,
            ),
            // Leaderboard lokalny: top 50 osobno dla kazdego trybu, zeby wyniki
            // z jednego trybu nie wypychaly pozostalych.
            scores = if (!doRankingu) d.scores else (d.scores + record)
                .groupBy { it.mode }
                .flatMap { (_, list) -> list.sortedByDescending { it.score }.take(50) }
                .sortedByDescending { it.score },
            sets = d.sets.map { if (it.id == setId) it.copy(timesPlayed = it.timesPlayed + 1) else it },
        )
        val ksiega = poGrze.dorobek
        val zOdznakami =
            if (Odznaki.komplet(ksiega)) ksiega
            else Odznaki.odblokuj(ksiega, Odznaki.liczniki(poGrze, katalog), Srs.dayOf(nowMs))
        poGrze.copy(dorobek = if (potwierdzOdznaki) Odznaki.potwierdz(zOdznakami) else zOdznakami)
    }

    /**
     * Odhacza odznaki jako pokazane: czysci zbior czekajacych, samych odznak nie rusza. Wola
     * to ekran, ktory wlasnie je pokazal (osiagniecia); ekran wyniku robi to samo przez
     * [recordGame]. Gdy nic nie czeka, dysku nie dotyka.
     *
     * @return to, co [save]; true takze wtedy, gdy nie bylo czego odhaczac.
     */
    @Synchronized
    fun potwierdzOdznaki(): Boolean {
        val d = load()
        val potwierdzony = Odznaki.potwierdz(d.dorobek)
        if (potwierdzony === d.dorobek) return true
        return save(d.copy(dorobek = potwierdzony))
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
        if (out.schemaVersion < 3) out = odswiezZestawyWbudowane(out)
        if (out.schemaVersion < 4) out = zalozKsiegeDorobku(out)
        return out.copy(schemaVersion = SCHEMA_VERSION)
    }

    /**
     * Krok 3 -> 4: zapis dostaje szczyty pudelek ([WordProgress.szczyt]) i ksiege dorobku
     * ([SaveData.dorobek]).
     *
     * Wszystko jest WYLICZALNE z samego zapisu, bez zegara i bez losowosci, bo krok biegnie
     * od nowa przy kazdym starcie az do pierwszego udanego zapisu. Stoi ZA odswiezeniem
     * katalogu (krok 2 -> 3) celowo: liczy po identyfikatorach wpisow, a te musza byc juz
     * biezace i miec przeprowadzony postep.
     *
     *  (a) Kazdy wpis postepu dostaje szczyt nie nizszy niz biezace pudelko. Czy slowo stalo
     *      kiedys wyzej, tego stary zapis nie pamieta, wiec przyjmujemy to, co w nim widac.
     *
     *  (b) Ksiega zaczyna z punktami za pudelka, do ktorych uczen NAPRAWDE doszedl
     *      ([Nagrody.punktyNaStart]). Liczymy po roznych identyfikatorach wpisow biezacych
     *      zestawow, takze wlasnych, w obu kierunkach, a nie po mapie postepu: po korekcie
     *      katalogu to samo slowo lezy w niej pod starym i pod nowym kluczem (patrz
     *      [przeniesPostep]) i liczyloby sie dwa razy. Z punktow za gry nie bierzemy nic,
     *      z tego samego powodu, dla ktorego krok 1 -> 2 nie wymysla z nich postepu. Dni
     *      nauki, seria, powtorki i naprawione slowa zaczynaja od zera, a zapas dni wolnych
     *      od jednego, jak u nowego ucznia: stary zapis tego nie notowal, wiec liczymy od
     *      tej wersji i niczego nie zgadujemy (takze z dni ostatnich awansow).
     *
     *  (c) Odznaki, ktorych prog jest juz spelniony, wchodza do ksiegi PO CICHU, z dniem
     *      [Odznaki.DZIEN_SPRZED_KSIEGI] i bez odkladania do pokazania. Inaczej pierwsza runda
     *      po aktualizacji oglaszalaby jako nowosc odznaki, ktore uczen ma od miesiecy (osiem
     *      z nich to dawne osiagniecia).
     *
     * Ksiegi, ktora MA JUZ PUNKTY, krok nie zaklada drugi raz i robi samo (a). Wyliczenie
     * z pudelek nadpisaloby wtedy punkty zarobione nauka (powtorek w terminie z pudelek nie
     * widac), a odznaki czekajace na prawdziwy dzien dostalyby stempel "sprzed ksiegi".
     */
    private fun zalozKsiegeDorobku(data: SaveData): SaveData {
        val zeSzczytami = data.copy(
            progress = data.progress.mapValues { (_, stan) ->
                if (stan.szczyt < stan.box) stan.copy(szczyt = stan.box) else stan
            },
        )
        if (data.dorobek.xp > 0) return zeSzczytami

        val wpisy = LinkedHashSet<String>()
        for (set in zeSzczytami.sets) {
            for (e in set.entries) if (e.id.isNotBlank()) wpisy += e.id
        }
        var punkty = 0
        for (id in wpisy) {
            // RANDOM to ustawienie, nie kierunek: Srs.key by go odrzucil.
            for (kierunek in listOf(Direction.TERM_TO_DEF, Direction.DEF_TO_TERM)) {
                val stan = zeSzczytami.progress[Srs.key(id, kierunek)] ?: continue
                punkty += Nagrody.punktyNaStart(stan)
            }
        }
        val zPunktami = zeSzczytami.copy(dorobek = zeSzczytami.dorobek.copy(xp = punkty))
        return zPunktami.copy(
            dorobek = Odznaki.odblokuj(
                zPunktami.dorobek,
                Odznaki.liczniki(zPunktami, katalog),
                Odznaki.DZIEN_SPRZED_KSIEGI,
                poCichu = true,
            ),
        )
    }

    /**
     * Krok 2 -> 3: zestawy wbudowane wracaja do tresci z KATALOGU.
     *
     * Awaria, ktora to wymusila: zapis trzyma wlasna kopie zestawow wbudowanych, a [load]
     * dokleja katalog tylko wtedy, gdy w zapisie nie ma ani jednego zestawu wbudowanego. Po
     * pierwszym zapisie (wystarczy podanie imienia na ekranie startowym) warunek nigdy wiecej
     * nie jest spelniony, wiec ZADNA poprawka slownictwa nie docierala do kogos, kto juz ma
     * aplikacje. Uczen aktualizujacy ze sklepowego 0.9.4 (vc21) zostawal z literowka w hasle
     * angielskim ("accomodation") i z trzema wezszymi tlumaczeniami, a poprawna pisownia byla
     * u niego oceniana jako blad — aplikacja do matury uczyla bledu.
     *
     * Regula: dopasowanie po [WordSet.id], podmiana tytulu, jezykow i wpisow; z zapisu zostaje
     * to, co nalezy do UCZNIA, a nie do katalogu (licznik rozgrywek, data utworzenia).
     * Zestawow wlasnych (builtIn = false) krok nie dotyka w ogole, tak samo jak zestawu
     * wbudowanego o identyfikatorze, ktorego w dzisiejszym katalogu nie ma — usuniecie jest
     * nieodwracalne, a nadmiarowy zestaw nikomu nie szkodzi.
     *
     * Kazda przyszla korekta katalogu wymaga NASTEPNEGO kroku (5, 6, ...): numer schematu jest
     * jedynym stemplem, po ktorym widac, ze zapis nie widzial jeszcze nowego slownictwa.
     *
     * SWIADOMY KOSZT: ekran ZESTAWY pozwala otworzyc w edytorze takze zestaw wbudowany, wiec
     * uczen, ktory poprawil w nim wiersz przed aktualizacja, dostanie z powrotem brzmienie
     * katalogowe. Krok biegnie RAZ (potem zapis ma juz wersje 3), wiec poprawki zrobione po
     * aktualizacji zostaja. Rozroznienie "tresc stara" od "tresc poprawiona przez ucznia"
     * wymaga znacznika we wpisie, ktorego model dzis nie ma; do czasu, gdy bedzie, pierwszenstwo
     * ma dostarczenie poprawnego slownictwa, bo z bledna pisownia uczen idzie na mature.
     */
    private fun odswiezZestawyWbudowane(data: SaveData): SaveData {
        val katalog = SampleData.builtInSets().associateBy { it.id }
        if (katalog.isEmpty()) return data

        val poTresci = katalogPoTresci()
        val idKatalogu = poTresci.values.toHashSet()
        val przeprowadzki = HashMap<String, String>()

        val sets = data.sets.map { set ->
            if (!set.builtIn) return@map set
            val biezacy = katalog[set.id] ?: return@map set
            for (e in set.entries) {
                if (e.id.isBlank()) continue
                // Tresc wpisu mowi, ktorym slowem on JEST (aliasy przepuszczaja znane korekty);
                // jesli lezy pod innym identyfikatorem, postep trzeba przeprowadzic razem z nim.
                val nalezyDo = poTresci[EntryId.contentKey(e.term, e.definition)]
                    ?: EntryId.builtIn(e.term, e.definition)
                if (nalezyDo != e.id && nalezyDo in idKatalogu) {
                    przeprowadzki.putIfAbsent(e.id, nalezyDo)
                }
            }
            set.copy(
                title = biezacy.title,
                termLanguage = biezacy.termLanguage,
                definitionLanguage = biezacy.definitionLanguage,
                entries = biezacy.entries,
            )
        }
        return data.copy(sets = sets, progress = przeniesPostep(data.progress, przeprowadzki))
    }

    /**
     * Postep spod identyfikatora sprzed korekty laduje TAKZE pod identyfikatorem katalogowym.
     *
     * Bez tego kroku odswiezenie katalogu bylo by dla ucznia gorsze niz sama literowka:
     * podmiana tresci zmienia identyfikator wpisu (liczy sie go z tresci), wiec cala nauka
     * tego slowa zostalaby pod kluczem, ktorego zaden ekran juz nie odczyta.
     *
     * Dwie zasady, obie na "nie gubimy":
     *  - starego klucza NIE kasujemy (tak samo jak reszty mapy — patrz [SaveData.progress]);
     *  - gdy pod nowym kluczem cos juz jest, zostaje to, co tam bylo. Uczen mogl w miedzyczasie
     *    cwiczyc to slowo z losowania, ktore czyta swiezy katalog, a swiezsza historia nie moze
     *    przegrac ze starsza.
     */
    private fun przeniesPostep(
        progress: Map<String, WordProgress>,
        przeprowadzki: Map<String, String>,
    ): Map<String, WordProgress> {
        if (przeprowadzki.isEmpty() || progress.isEmpty()) return progress
        val out = HashMap(progress)
        for ((stary, nowy) in przeprowadzki) {
            // RANDOM to ustawienie, nie kierunek — Srs.key by go odrzucil.
            for (kierunek in listOf(Direction.TERM_TO_DEF, Direction.DEF_TO_TERM)) {
                val zKlucz = Srs.key(stary, kierunek)
                val doKlucz = Srs.key(nowy, kierunek)
                val stan = progress[zKlucz] ?: continue
                if (out.containsKey(doKlucz)) continue
                out[doKlucz] = stan
            }
        }
        return out
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
     *
     * Jeden wyjatek od "potem kopia": gdy pliku glownego NIE MA, pierwszenstwo ma kompletny
     * plik tymczasowy. [writeAtomically] robi dwie zmiany nazw (glowny na kopie, tymczasowy na
     * glowny) i miedzy nimi pliku glownego nie ma; aplikacja ubita w tej chwili zostawia kopie
     * z POPRZEDNIM stanem i caly, zsynchronizowany plik tymczasowy z NAJNOWSZYM. Odczyt z samej
     * kopii po cichu gubil wtedy ostatni zapis, choc lezal obok.
     *
     * Odczyt dalej niczego nie pisze: plik tymczasowy zostaje na miejscu, a plik glowny
     * odtwarza dopiero nastepny zapis. Do tego czasu najnowszy stan lezy na dysku tylko w pliku
     * tymczasowym, a [writeAtomically] zaczyna od nadpisania wlasnie jego: ubicie w trakcie
     * TEGO jednego zapisu cofa wiec do kopii. Zamkniecie tej luki wymaga zmiany w zapisie,
     * nie w odczycie.
     */
    private fun readBestAvailable(): SaveData {
        read(file)?.let { return it }

        if (file.exists()) {
            // Skoro plik glowny istnieje, a odczyt zwrocil null, to znaczy, ze sie nie sparsowal.
            quarantineMainFile()
        } else {
            // Urwany plik tymczasowy (ubicie w trakcie jego pisania) nie sparsuje sie i wtedy
            // read() zwraca null, wiec dalej idziemy do kopii jak dotad.
            read(File(dir, TEMP_NAME))?.let {
                Log.w(TAG, "odczyt z $TEMP_NAME: zapis przerwany przed zmiana nazwy na $FILE_NAME")
                return it
            }
        }

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
         *
         * 2 -> 3: zestawy wbudowane w zapisie wracaja do tresci z katalogu, a postep zapisany
         * pod identyfikatorem sprzed korekty przeprowadza sie pod katalogowy. Numer podnosi
         * KAZDA korekta slownictwa, bo inaczej poprawka nie ma jak dotrzec do telefonu, ktory
         * ma juz wlasna kopie katalogu na dysku.
         *
         * 3 -> 4: kazdy wpis postepu ma szczyt (WordProgress.szczyt), a zapis ma ksiege dorobku
         * (SaveData.dorobek), zalozona z pudelek, do ktorych uczen doszedl, i z odznak, ktore
         * juz spelnia. Ten krok nie dotyka slownictwa: nastepna korekta katalogu bierze numer 5
         * i ponawia odswiezenie zestawow, a ksiega zostaje, jak jest.
         */
        const val SCHEMA_VERSION = 4
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
