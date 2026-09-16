package com.matura.app.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Cala logika rozpoznawania offline, ktora da sie sprawdzic BEZ telefonu: budowa gramatyki,
 * mapa powrotna i bramka pewnosci. [VoskVoiceInput] doklada do tego wylacznie mikrofon i watek.
 *
 * DLACZEGO GRAMATYKA, A NIE SWOBODNA TRANSKRYPCJA. Vosk potrafi rozpoznawac caly jezyk, ale
 * plansza ma zamkniety slownik — kilkanascie odpowiedzi. Recognizer z lista slow (gramatyka)
 * skraca zadanie do "ktore z tych" i w pomiarze na nagraniach slownictwa tej aplikacji dawal
 * 120/120 angielskich i 72/72 polskich trafien, wobec 78-93% bez gramatyki. Przy okazji z
 * modelu znika wtedy potrzeba trzymania calego modelu jezykowego — patrz tools/vosk-slim.py.
 */
object VoskGrammar {

    /**
     * Slowo-smietnik, ktore MUSI byc w kazdej gramatyce.
     *
     * Bez niego gramatyka jest zamknieta: dekoder nie ma jak powiedziec "to nie bylo zadne
     * z tych slow" i kazde chrzakniecie, kaszel czy zdanie z drugiego pokoju dostaje najblizsza
     * odpowiedz z planszy — czyli strzal w losowy cel. W pomiarze [unk] wylapal 51 z 72 polskich
     * i 110 ze 120 angielskich wypowiedzi spoza planszy; reszte odsiewa dopiero [przyjac].
     */
    const val UNK = "[unk]"

    /**
     * Gorny prog dlugosci gramatyki.
     *
     * NIE chodzi o czas: budowa Recognizera rosnie liniowo i wynosi 0,19 ms dla 24 slow,
     * 0,41 ms dla 240 i 0,66 ms dla 500 (pomiar na modelu polskim z app/src/main/assets).
     * Chodzi o falszywe akceptacje — im wiecej slow w zamknietym slowniku, tym wieksza szansa,
     * ze wypowiedz spoza planszy trafi w ktores z nich. Realny szczyt to zestaw 120 hasel przy
     * kierunku losowym, czyli ok. 240 pozycji po rozbiciu wariantow; 500 zostawia zapas na
     * zestaw wlasny ucznia i zamyka droge zestawowi z tysiacami hasel.
     */
    const val MAX_SLOW = 500

    /**
     * Prog [pewnoscNaSekunde] dla polskiego.
     *
     * Pomiar na modelu z assets (72 nagrania neuronowe, plansze po 24 slowa): prawdziwe
     * trafienia mialy min. 71, p10 = 74; wypowiedzi slow SPOZA planszy, ktore przeszly przez
     * [UNK], mialy max. 72 i median 62. Prog 68 zatrzymuje 20 z 21 takich falszywek i nie
     * traci ani jednego prawdziwego trafienia (przy 75 zaczyna wycinac prawdziwe: 62/72).
     */
    const val PROG_PL = 68.0

    /**
     * To samo dla angielskiego — nizej, bo rozklad jest inny.
     *
     * Ten sam pomiar na 120 nagraniach: najslabsze prawdziwe trafienie mialo 68, wiec prog 68
     * juz by je scial (119/120). 66 zostawia komplet trafien i odsiewa 7 z 10 falszywek.
     */
    const val PROG_EN = 66.0

    /**
     * Ile hipotez zamawiamy u Vosk-a (`setMaxAlternatives`).
     *
     * Liczba jest tu z jednego powodu: bez alternatyw wynik Voska NIE MA pola `confidence`,
     * a bez niego nie ma bramki pewnosci — ten sam pomiar, ktory dal [PROG_PL], zbieral dane
     * wlasnie z `alternatives[0].confidence`. Piec, bo tyle uzyto w pomiarze; wiecej nie daje
     * nic, bo i tak patrzymy wylacznie na pierwsza.
     */
    const val MAX_ALTERNATIVES = 5

    // Przecinek, srednik i ukosnik rozdzielaja ROWNOPRAWNE odpowiedzi ("towarzyski, kontaktowy",
    // "żonaty/zamężna") — dokladnie tak samo czyta je Matching.acceptedAnswers, z ktorego biora
    // sie odpowiedzi na planszy. Gdyby zostaly w napisie, do gramatyki weszlaby fraza
    // "towarzyski kontaktowy", ktorej uczen nigdy nie wypowie w calosci, a samo "towarzyski"
    // nie pasowaloby do niczego.
    private val ROZDZIELACZE = Regex("[,;/]")

    // Lacznik i apostrof zamieniamy na SPACJE, a nie usuwamy. To jedna zmiana, ktora w pomiarze
    // podniosla angielski z 96,7% do 100%: "open-minded" jako jeden token nie istnieje w
    // leksykonie modelu, a "open minded" to dwa tokeny, ktore istnieja — i sklejone akustycznie
    // dekoduja sie normalnie. Tak samo "butcher's" -> "butcher s".
    // Zakres ‐-― to myslniki typograficzne, ’ to apostrof z klawiatury telefonu.
    private val DO_SPACJI = Regex("[-‐-―'‘’`]+")

    // Wszystko, co nie jest litera, cyfra ani spacja, wypada. Znaki diakrytyczne zostaja:
    // leksykon polskiego modelu ma "łóżko", a nie "lozko" — zdjecie ogonkow (tak jak robi to
    // Matching.normalize dla porownywania tekstu) wywaliloby te slowa z gramatyki.
    private val NIE_LITERA = Regex("[^\\p{L}\\p{Nd} ]")
    private val WIELE_SPACJI = Regex("\\s+")

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Napis w postaci, ktora Vosk przyjmuje jako pozycje gramatyki: male litery, bez lacznikow,
     * apostrofow i interpunkcji, pojedyncze spacje. Zwraca "" dla napisu bez liter.
     */
    fun normalizeForGrammar(raw: String): String {
        val bezLacznikow = DO_SPACJI.replace(raw.lowercase(), " ")
        val czyste = NIE_LITERA.replace(bezLacznikow, " ")
        return WIELE_SPACJI.replace(czyste, " ").trim()
    }

    /**
     * Rownoprawne warianty jednego hasla: calosc plus czesci rozdzielone przecinkiem/ukosnikiem.
     * Calosc zostaje w liscie celowo — sa hasla, w ktorych przecinek jest czescia nazwy.
     */
    fun warianty(raw: String): List<String> {
        val out = LinkedHashSet<String>()
        normalizeForGrammar(raw).let { if (it.isNotEmpty()) out.add(it) }
        if (ROZDZIELACZE.containsMatchIn(raw)) {
            for (czesc in ROZDZIELACZE.split(raw)) {
                normalizeForGrammar(czesc).let { if (it.isNotEmpty()) out.add(it) }
            }
        }
        return out.toList()
    }

    /**
     * Gramatyka gotowa do podania Recognizerowi plus mapa powrotna.
     *
     * @property json tablica JSON ze slowami i [UNK] — dokladnie to, czego oczekuje konstruktor
     *           Recognizera.
     * @property powrot znormalizowana pozycja gramatyki -> napis, ktory dal ja poczatek. Vosk
     *           oddaje wylacznie postac z gramatyki ("open minded"), a plansza trzyma oryginal
     *           ("open-minded"); bez tej mapy dopasowanie musialoby zgadywac droge powrotna.
     * @property slowa pozycje gramatyki bez [UNK] — do diagnostyki i do testow.
     */
    data class Slownik(
        val json: String,
        val powrot: Map<String, String>,
        val slowa: List<String>,
    )

    /** Pusty slownik ma sens: Recognizer i tak nie powstanie, ale nikt nie musi tego sprawdzac dwa razy. */
    val PUSTY = Slownik(JsonArray(listOf(JsonPrimitive(UNK))).toString(), emptyMap(), emptyList())

    fun zbuduj(slowa: List<String>, limit: Int = MAX_SLOW): Slownik {
        val powrot = LinkedHashMap<String, String>()
        for (surowe in slowa) {
            if (powrot.size >= limit) break
            for (wariant in warianty(surowe)) {
                if (powrot.size >= limit) break
                // Pierwszy wygrywa: gdy dwa hasla normalizuja sie tak samo ("e-mail" i "email"),
                // droga powrotna ma wskazywac to, ktore weszlo na plansze wczesniej — inaczej
                // zaliczenie trafialoby w inny wiersz niz ten, ktory uczen wlasnie czytal.
                if (!powrot.containsKey(wariant)) powrot[wariant] = surowe.trim()
            }
        }
        val pozycje = powrot.keys.toList()
        if (pozycje.isEmpty()) return PUSTY
        val tablica = JsonArray((pozycje + UNK).map { JsonPrimitive(it) })
        return Slownik(tablica.toString(), powrot, pozycje)
    }

    /**
     * Pewnosc Voska podzielona przez dlugosc CALEGO odcinka rozpoznawania w sekundach.
     *
     * Vosk oddaje w `alternatives[].confidence` sume wiarygodnosci po klatkach, wiec sama liczba
     * rosnie z dlugoscia wypowiedzi i nie da sie jej porownywac z progiem. Mianownikiem musi byc
     * caly odcinek, a NIE czas samej mowy z `result[].start/end`, mimo ze ten drugi wyglada
     * madrzej. Pomiar z doklejona cisza (0 / 0,5 / 1,5 s wokol slowa) na modelu z assets:
     *
     *   conf/odcinek   : PL 72 -> 70 -> 72     EN 68 -> 83 -> 89   (prog trzyma sie ciszy)
     *   conf/czas-mowy : PL 155 -> 220 -> 371  EN 77 -> 177 -> 318 (prog nie do ustalenia)
     *
     * Cisza doklada do licznika mniej wiecej tyle, ile do mianownika, wiec iloraz jest odporny
     * na to, ile uczen milczal przed odpowiedzia. Drugi mianownik rosnie 2-4 razy i kazdy staly
     * prog przy nim klamie.
     */
    fun pewnoscNaSekunde(confidence: Double, sekundy: Double): Double =
        if (sekundy <= 0.0) 0.0 else confidence / sekundy

    /** Prog dla jezyka nasluchu. Tag jest w postaci "pl-PL" / "en-US". */
    fun prog(jezykPolski: Boolean): Double = if (jezykPolski) PROG_PL else PROG_EN

    /** Wynik odczytany z odpowiedzi Vosk-a. */
    data class Wynik(
        /** Tekst tak, jak oddal go Vosk (postac z gramatyki). */
        val tekst: String,
        /** Ten sam tekst sprowadzony do oryginalnego napisu z planszy, gdy mapa powrotna go zna. */
        val oryginal: String,
        val pewnoscNaSekunde: Double,
        val przyjete: Boolean,
    ) {
        /**
         * Hipotezy dla dopasowania: oryginal przodem, postac Vosk-a za nim. Podajemy OBIE, bo
         * VoiceMatch liczy podobienstwo do odpowiedzi na planszy i nie ma powodu odbierac mu
         * ktorejkolwiek — a przy hasle spoza mapy powrotnej zostaje tylko druga.
         */
        val hipotezy: List<String>
            get() = if (oryginal.isNotEmpty() && oryginal != tekst) listOf(oryginal, tekst) else listOf(tekst)
    }

    /**
     * Odczyt WYNIKU KONCOWEGO (`FinalResult`/`Result`). Zwraca null, gdy Vosk nie oddal niczego,
     * co da sie pokazac graczowi: pusty tekst albo sam [UNK].
     *
     * @param sekundy dlugosc odcinka, z ktorego pochodzi ten wynik — patrz [pewnoscNaSekunde].
     */
    fun czytajWynik(
        surowy: String,
        sekundy: Double,
        slownik: Slownik,
        jezykPolski: Boolean,
    ): Wynik? {
        val obiekt = parsuj(surowy) ?: return null
        // Z setMaxAlternatives wynik ma "alternatives"; bez niego samo "text" i zadnej pewnosci.
        // Obsluga obu jest po to, zeby zmiana ustawien Recognizera nie zamieniala bramki
        // pewnosci w cicha przepustke.
        val alt = (obiekt["alternatives"] as? JsonArray)?.firstOrNull() as? JsonObject
        val tekst = napis(alt?.get("text") ?: obiekt["text"]).trim()
        if (tekst.isEmpty() || tekst.contains(UNK)) return null
        val pewnosc = (alt?.get("confidence") as? JsonPrimitive)?.doubleOrNull
        val naSekunde = if (pewnosc == null) Double.NaN else pewnoscNaSekunde(pewnosc, sekundy)
        // Brak liczby pewnosci to nie to samo co niska pewnosc: przepuszczamy wynik, bo bez
        // alternatyw Vosk i tak nie mial jak sklamac, a odrzucanie wszystkiego byloby gluche.
        val przyjete = naSekunde.isNaN() || naSekunde >= prog(jezykPolski)
        return Wynik(tekst, slownik.powrot[tekst].orEmpty(), naSekunde, przyjete)
    }

    /**
     * Odczyt wyniku CZESCIOWEGO (`PartialResult`). Nie ma w nim pewnosci ani czasow, wiec nie ma
     * czego bramkowac — ekran i tak przyjmuje wynik czesciowy dopiero przy bardzo wysokim
     * podobienstwie (PlayScreen.onHeard: score < 0.9 odpada).
     */
    fun czytajCzesciowy(surowy: String, slownik: Slownik): List<String> {
        val obiekt = parsuj(surowy) ?: return emptyList()
        val tekst = napis(obiekt["partial"]).trim()
        if (tekst.isEmpty() || tekst.contains(UNK)) return emptyList()
        val oryginal = slownik.powrot[tekst].orEmpty()
        return if (oryginal.isNotEmpty() && oryginal != tekst) listOf(oryginal, tekst) else listOf(tekst)
    }

    // Vosk jest biblioteka natywna i jego wyjscie traktujemy jak dane z zewnatrz: zly JSON ma
    // wyciszyc jedna wypowiedz, a nie wywalic watek nasluchu.
    private fun parsuj(surowy: String): JsonObject? = try {
        json.parseToJsonElement(surowy) as? JsonObject
    } catch (_: Throwable) {
        null
    }

    private fun napis(element: kotlinx.serialization.json.JsonElement?): String =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
}
