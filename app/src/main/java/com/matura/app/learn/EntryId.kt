package com.matura.app.learn

import com.matura.app.model.Entry
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Nadawanie TRWALYCH identyfikatorow wpisom slownika.
 *
 * Problem, ktory to rozwiazuje: postep nauki musi byc przypiety do slowa, a slowo nie mialo
 * dotad zadnej tozsamosci. Pozycja w tablicy odpada (edytor przestawia i usuwa wiersze,
 * a katalog jest generowany od nowa), sama tresc tez (poprawka tlumaczenia zerwalaby postep).
 *
 * Zasady:
 *
 *  1. Wpis KATALOGOWY dostaje "w" + 12 znakow szesnastkowych SHA-256 z pary
 *     znormalizowane(haslo) + U+001F + znormalizowane(SENS).
 *
 *  2. SENS to tlumaczenie ZAMROZONE w chwili nadania identyfikatora, a nie biezace. Dzieki
 *     temu poprawienie tlumaczenia nie zmienia identyfikatora. Zamrozone sensy i wszystkie
 *     brzmienia poboczne trzyma manifest data/entry-ids.json (czesc repozytorium), a kod
 *     odtwarza to samo odwzorowanie tablica [ALIASY] — patrz komentarz przy niej.
 *
 *  3. Rozne ZNACZENIA tego samego hasla EN maja rozne identyfikatory, bo sens wchodzi do
 *     skrotu ("patient" jako "cierpliwy" i jako "pacjent" to dwa cele nauki). To samo
 *     znaczenie w dwoch zestawach ma identyfikator ten sam i dzieli postep.
 *
 *  4. Wpis WLASNY dostaje "u" + 12 znakow szesnastkowych. Prefiks "u" gwarantuje, ze zadna
 *     tresc wpisana przez ucznia nie trafi w identyfikator z katalogu. Wartosc jest losowa,
 *     nadawana RAZ przy zapisie i nigdy nie przeliczana z tresci — inaczej poprawa literowki
 *     we wlasnym zestawie kasowalaby postep.
 *
 * Normalizacja CELOWO nie zdejmuje diakrytykow (inaczej niz Matching.normalize, ktore sluzy
 * do porownywania odpowiedzi wpisanych na klawiaturze bez polskich znakow). Tutaj chodzi o
 * tozsamosc slowa, a "ladowac" i "ladowac" (od land i od ladunek) to dwa rozne slowa, ktore
 * po zdjeciu ogonkow zlalyby sie w jedno i dzielilyby postep.
 */
object EntryId {

    /** Prefiks identyfikatora z katalogu wbudowanego. */
    const val PREFIX_BUILT_IN = "w"

    /** Prefiks identyfikatora wpisu wlasnego ucznia. */
    const val PREFIX_USER = "u"

    /** Ile znakow szesnastkowych skrotu wchodzi do identyfikatora (48 bitow). */
    const val HEX_LENGTH = 12

    /**
     * Rozdzielacz pol w kluczu tresci. Ten sam znak, ktorego uzywa strona matura.uk w kluczu
     * postepu — wspolny format wymiany ma sens tylko wtedy, gdy obie strony skladaja klucz
     * identycznie. Znak sterujacy jest tu wlasciwy, bo nie moze wystapic w tresci wpisu.
     */
    const val UNIT_SEPARATOR = '\u001F'

    /**
     * Korekty tlumaczen: brzmienie SPRZED poprawki -> sens zamrozony w manifescie.
     *
     * Etap 1 poprawil trzy tlumaczenia wezsze albo wprost inne niz angielski oryginal (regula
     * "najwyzej dwa slowa" wymuszala blad merytoryczny). Na dysku ucznia lezy jeszcze STARE
     * brzmienie — zapis trzyma wlasna kopie zestawow wbudowanych i to jej uzywa przy odczycie.
     * Bez tej tablicy migracja policzylaby dla takiego wpisu inny skrot niz katalog i to samo
     * slowo mialoby dwa identyfikatory na dwoch telefonach.
     *
     * Tablica jest odbiciem pola "aliasy" z data/entry-ids.json; generator identyfikatorow
     * (_matura-apk-tools/generuj_identyfikatory.py) sprawdza, czy oba miejsca sie zgadzaja,
     * i przerywa, gdy manifest zna alias, ktorego tutaj nie ma.
     */
    private val ALIASY: Map<String, String> = mapOf(
        contentKey("schoolmate", "rówieśnik") to
            contentKey("schoolmate", "kolega ze szkoły, koleżanka ze szkoły"),
        contentKey("boarding school", "internat") to
            contentKey("boarding school", "szkoła z internatem"),
        contentKey("single ticket", "bilet jednorazowy") to
            contentKey("single ticket", "bilet w jedną stronę"),
    )

    /**
     * Male litery, obciete konce, ciagi bialych znakow zbite do jednej spacji. Diakrytyki
     * ZOSTAJA (patrz komentarz obiektu). Odpowiednik po stronie generatora musi robic to samo,
     * inaczej identyfikatory z katalogu i te liczone w aplikacji rozjada sie.
     */
    fun normalize(raw: String): String {
        val sb = StringBuilder(raw.length)
        var lastWasSpace = true
        for (ch in raw.lowercase()) {
            if (ch.isWhitespace()) {
                if (!lastWasSpace) {
                    sb.append(' ')
                    lastWasSpace = true
                }
            } else {
                sb.append(ch)
                lastWasSpace = false
            }
        }
        return sb.toString().trim()
    }

    /** Klucz tresci: znormalizowane haslo, rozdzielacz, znormalizowany sens. */
    fun contentKey(term: String, sense: String): String =
        normalize(term) + UNIT_SEPARATOR + normalize(sense)

    /**
     * Identyfikator wpisu katalogowego liczony z tresci. Brzmienie sprzed korekty daje ten sam
     * wynik co brzmienie biezace, bo klucz idzie najpierw przez [ALIASY].
     */
    fun builtIn(term: String, definition: String): String {
        val klucz = contentKey(term, definition)
        return PREFIX_BUILT_IN + sha256Hex(ALIASY[klucz] ?: klucz).take(HEX_LENGTH)
    }

    /**
     * Nowy identyfikator wpisu wlasnego. Losowy, nie liczony z tresci — patrz punkt 4 opisu
     * obiektu. Zrodlo losowosci wchodzi parametrem, zeby test mogl je ustalic.
     */
    fun newUserId(random: Random = Random.Default): String =
        PREFIX_USER + java.lang.Long.toHexString(random.nextLong())
            .padStart(16, '0')
            .takeLast(HEX_LENGTH)

    /**
     * Identyfikator wpisu wlasnego dla MIGRACJI starych zapisow. Musi byc wyliczalny, bo
     * migracja biegnie przy kazdym starcie az do pierwszego udanego zapisu, a wartosc losowa
     * dawalaby za kazdym razem inny identyfikator (i za kazdym razem inny postep).
     *
     * Do skrotu wchodzi identyfikator zestawu, tresc i numer powtorzenia — ten ostatni tylko
     * po to, zeby dwa identyczne wiersze w jednym zestawie nie dostaly jednego identyfikatora.
     * Pozycja w liscie NIE wchodzi: przestawienie wierszy przed pierwszym zapisem nie moze
     * zmienic wyniku.
     */
    fun userIdFor(setId: String, term: String, definition: String, occurrence: Int = 0): String {
        val klucz = setId + UNIT_SEPARATOR + contentKey(term, definition) +
            UNIT_SEPARATOR + occurrence
        return PREFIX_USER + sha256Hex(klucz).take(HEX_LENGTH)
    }

    fun isBuiltIn(id: String): Boolean = id.startsWith(PREFIX_BUILT_IN) && isValid(id)

    fun isUser(id: String): Boolean = id.startsWith(PREFIX_USER) && isValid(id)

    /** Ksztalt, nie pochodzenie: prefiks i [HEX_LENGTH] znakow szesnastkowych. */
    fun isValid(id: String): Boolean =
        id.length == HEX_LENGTH + 1 &&
            (id.startsWith(PREFIX_BUILT_IN) || id.startsWith(PREFIX_USER)) &&
            id.drop(1).all { it in '0'..'9' || it in 'a'..'f' }

    /**
     * Przenosi identyfikatory z POPRZEDNIEJ wersji zestawu na liste wierszy zbudowana przez
     * edytor. Edytor sklada wpisy od zera (Screens.kt: rows.map { Entry(...) }), wiec bez
     * tego kroku kazdy zapis zestawu kasowalby cala nauke w nim zawarta.
     *
     * Dopasowanie idzie trzema podejsciami, od najpewniejszego:
     *  1. wiersz, ktory JUZ ma identyfikator, zatrzymuje swoj (edytor moze go kiedys przenosic);
     *  2. identyczna tresc — przezywa przestawienie wierszy i dopisanie nowych;
     *  3. ten sam NUMER wiersza — przezywa poprawke tresci (literowka, dodana alternatywa,
     *     zamiana kolumn przyciskiem "<>"), bo poprawiany wiersz zostaje na swoim miejscu.
     * Co zostalo bez pary, jest naprawde nowe i dostaje swiezy identyfikator.
     *
     * Heurystyka, nie dowod: przy JEDNOCZESNYM przestawieniu i przepisaniu wiersza postep moze
     * trafic w sasiada. Wybor jest swiadomy — alternatywa (identyfikator liczony z tresci) gubi
     * postep przy KAZDEJ poprawce literowki, co jest czestsze i bardziej dotkliwe.
     */
    fun reconcile(
        previous: List<Entry>,
        edited: List<Entry>,
        builtInSet: Boolean = false,
        random: Random = Random.Default,
    ): List<Entry> {
        val wynik = arrayOfNulls<String>(edited.size)
        val wolne = previous.indices.toMutableList()

        for (i in edited.indices) {
            val id = edited[i].id
            if (id.isNotBlank()) {
                wynik[i] = id
                // Wiersz, ktorego identyfikator przyszedl juz z ekranu, nie moze dodatkowo zjesc
                // pary z poprzedniej wersji - inaczej zabralby ja sasiadowi.
                wolne.removeAll { previous[it].id == id }
            }
        }

        for (i in edited.indices) {
            if (wynik[i] != null) continue
            val klucz = contentKey(edited[i].term, edited[i].definition)
            val trafiony = wolne.firstOrNull {
                previous[it].id.isNotBlank() &&
                    contentKey(previous[it].term, previous[it].definition) == klucz
            } ?: continue
            wynik[i] = previous[trafiony].id
            wolne.remove(trafiony)
        }

        for (i in edited.indices) {
            if (wynik[i] != null) continue
            if (i !in wolne || previous[i].id.isBlank()) continue
            wynik[i] = previous[i].id
            wolne.remove(i)
        }

        return edited.mapIndexed { i, e ->
            val id = wynik[i]
                ?: if (builtInSet) builtIn(e.term, e.definition) else newUserId(random)
            if (e.id == id) e else e.copy(id = id)
        }
    }

    private fun sha256Hex(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private const val HEX = "0123456789abcdef"
}
