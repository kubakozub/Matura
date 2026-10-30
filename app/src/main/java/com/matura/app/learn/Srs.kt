package com.matura.app.learn

import com.matura.app.model.Direction
import com.matura.app.model.TaskKind
import com.matura.app.model.WordProgress

/**
 * Zrodlo czasu dla nauki.
 *
 * W tym pliku CELOWO nie ma implementacji siegajacej po System.currentTimeMillis(): gdyby
 * zegar byl schowany w regulach, nie dalo by sie przetestowac ani polnocy, ani dlugiej
 * przerwy w nauce — a to wlasnie tam mieszkaja bledy harmonogramu. Warstwa wywolujaca
 * podaje czas jawnie, w Kotlinie to jeden nawias: Clock { System.currentTimeMillis() }.
 */
fun interface Clock {
    fun nowMs(): Long
}

/**
 * Co sie stalo w jednej probie. Rozroznienie jest wazniejsze, niz wyglada: az trzy z tych
 * wartosci to NIE jest "bledna odpowiedz", a mimo to kazda znaczy co innego dla harmonogramu.
 */
enum class Outcome {
    /** Uczen odpowiedzial poprawnie, samodzielnie. */
    POPRAWNA,

    /** Uczen odpowiedzial zle. */
    BLEDNA,

    /** Odpowiedz poprawna, ale po podpowiedzi (odkryta litera, podane pierwsze znaki). */
    PODPOWIEDZ,

    /**
     * Aplikacja pokazala odpowiedz, a uczen ja powtorzyl. Ma wartosc cwiczeniowa i tylko taka:
     * przepisanie widocznego slowa nie jest zadnym dowodem pamieci.
     */
    POKAZANA,

    /**
     * Proba sie nie odbyla: blad techniczny silnika, anulowanie przez ucznia, brak rozpoznanej
     * mowy, wyjscie z ekranu. To NIE jest bledna odpowiedz i nie wolno za to karac — stan
     * zostaje dokladnie taki, jaki byl.
     */
    PRZERWANA,
}

/**
 * Jedna proba zaliczenia slowa.
 *
 * [id] to znacznik jednorazowosci. Ta sama proba potrafi dotrzec do zapisu dwa razy (ponowienie
 * po nieudanym zapisie, powrot na ekran, dwa zrodla zdarzen w trybie glosowym), a dwa awanse
 * z jednej odpowiedzi to nieprawda o uczniu. Wystarczy cokolwiek unikalnego w obrebie slowa,
 * np. "$entryId#$nowMs". Pusty znacznik wylacza straznika (dla wywolan, ktore i tak nie moga
 * sie powtorzyc).
 */
data class Attempt(
    val entryId: String,
    val direction: Direction,
    val task: TaskKind,
    val outcome: Outcome,
    val id: String = "",
)

/** Nazwa stanu nauki dla ekranu. Odpowiada pudelku 0..3 i nic wiecej nie znaczy. */
enum class Stage {
    /** Pudelko 0 — slowo nie bylo jeszcze sprawdzane. */
    BRAK_DANYCH,

    /** Pudelko 1 — slowo poznane albo rozpoznane; to jeszcze nie jest umiejetnosc. */
    POZNANE,

    /** Pudelko 2 — przypomniane samodzielnie w pozniejszym terminie. */
    POWTARZANE,

    /** Pudelko 3 — utrwalone: dwa samodzielne przypomnienia w kolejnych zaplanowanych terminach. */
    UTRWALONE,
}

/**
 * Harmonogram powtorek (Leitner, trzy pudelka) — JEDYNE miejsce w aplikacji, ktore decyduje
 * o awansie slowa. Wszystkie funkcje sa czyste: stan i dzien wchodza parametrem, nowy stan
 * wychodzi wynikiem.
 *
 * DZIEN liczy sie jako floor(czasMs / 86 400 000), czyli w DNIACH OD EPOKI UTC — dokladnie tak,
 * jak robi to strona matura.uk (klucz postepu "angielskie" + U+001F + "polskie", pudelka 1..3,
 * interwaly 1/3/7). Zgodnosc jest celem, a nie przypadkiem: bez wspolnej definicji doby
 * wspolny format wymiany postepu miedzy strona a aplikacja nie mialby sensu.
 *
 * SKUTEK do zakomunikowania w interfejsie: granica doby wypada o polnocy UTC, czyli o 01:00
 * (zima) albo 02:00 (lato) czasu polskiego. Nauka o 23:30 i o 00:30 to wciaz TEN SAM dzien
 * harmonogramu, wiec druga powtorka tego samego slowa juz nie awansuje. Zysk jest wiekszy niz
 * strata: doba liczona z zegara lokalnego przesuwalaby sie przy kazdej zmianie strefy i przy
 * kazdej zmianie czasu, a uczen w podrozy dostawalby albo dzien gratis, albo dzien odebrany.
 *
 * Czego tu NIE ma i nie bedzie: wyliczania "opanowania" z punktow za gry (punkt zdobywa sie
 * takze za szybkosc i za trafienie potworka, nie za wiedze) ani historii odpowiedzi.
 */
object Srs {

    const val MS_PER_DAY = 86_400_000L

    /** Pudelko dla slowa bez zadnej historii. */
    const val NO_BOX = 0

    /** Pierwsze pudelko z harmonogramem. Do niego wraca slowo po pomylce. */
    const val FIRST_BOX = 1

    /** Pudelko "utrwalone". */
    const val MAX_BOX = 3

    /** Sufit dla ROZPOZNANIA — patrz [ceilingFor]. */
    const val RECOGNITION_CEILING = 1

    /** Odstepy w dniach, indeksowane pudelkiem. Pudelko 0 nie ma odstepu: jest zalegle od razu. */
    private val INTERVAL_DAYS = intArrayOf(0, 1, 3, 7)

    /** Ile dni czeka slowo w danym pudelku. */
    fun intervalDays(box: Int): Int = INTERVAL_DAYS[box.coerceIn(NO_BOX, MAX_BOX)]

    /**
     * Dni od epoki UTC. floorDiv, a nie zwykle dzielenie, bo dla czasu sprzed 1970 (zegar
     * telefonu potrafi cofnac sie do 1970-01-01 po wyjeciu baterii) dzielenie w Kotlinie
     * obcina do zera i dwa rozne dni zlewaja sie w jeden.
     */
    fun dayOf(ms: Long): Int = Math.floorDiv(ms, MS_PER_DAY).toInt()

    fun dayOf(clock: Clock): Int = dayOf(clock.nowMs())

    /**
     * Klucz postepu: identyfikator wpisu + kierunek. Kierunek jest czescia klucza, bo
     * rozpoznanie PL->EN i EN->PL to dwie rozne umiejetnosci — kto umie przetlumaczyc
     * "dojrzaly" na "mature", nie musi umiec tego w druga strone.
     *
     * [Direction.RANDOM] jest USTAWIENIEM ("losuj kierunek"), a nie kierunkiem: gdyby wpadl do
     * klucza, polowa postepu ladowalaby w koszu, ktorego zaden ekran juz nie odczyta. Warstwa
     * gry ma rozlosowac kierunek PRZED zadaniem pytania i podac tutaj wynik losowania.
     */
    fun key(entryId: String, direction: Direction): String {
        require(direction != Direction.RANDOM) {
            "Direction.RANDOM to ustawienie, nie kierunek — rozlosuj go przed zapisem postepu"
        }
        require(entryId.isNotBlank()) { "postep bez identyfikatora wpisu nie ma do czego przylgnac" }
        return entryId + EntryId.UNIT_SEPARATOR + direction.name
    }

    fun stageOf(box: Int): Stage = when (box.coerceIn(NO_BOX, MAX_BOX)) {
        FIRST_BOX -> Stage.POZNANE
        2 -> Stage.POWTARZANE
        MAX_BOX -> Stage.UTRWALONE
        else -> Stage.BRAK_DANYCH
    }

    fun stageOf(progress: WordProgress): Stage = stageOf(progress.box)

    /**
     * Najwyzsze pudelko osiagalne danym rodzajem zadania.
     *
     * ROZPOZNANIE (ABCD, prawda/falsz) konczy sie na pudelku 1, bo wybor sposrod podanych
     * odpowiedzi nie dowodzi ani samodzielnego przypomnienia, ani pisowni — przy czterech
     * opcjach nawet zgadywanie trafia co czwarty raz. Pudelka 2 i 3 sa zarezerwowane dla
     * zadan, w ktorych uczen produkuje odpowiedz sam.
     */
    fun ceilingFor(task: TaskKind): Int =
        if (task == TaskKind.ROZPOZNANIE) RECOGNITION_CEILING else MAX_BOX

    /** Czy slowo czeka na sprawdzenie. Slowo bez historii jest zalegle od razu (dueDay = 0). */
    fun isDue(progress: WordProgress, today: Int): Boolean = today >= progress.dueDay

    fun progressOf(
        progress: Map<String, WordProgress>,
        entryId: String,
        direction: Direction,
    ): WordProgress = progress[key(entryId, direction)] ?: WordProgress()

    /**
     * Identyfikatory zalegle na dzis, w kolejnosci podanej przez wolajacego. Slowa nieznane
     * (bez wpisu w mapie) TEZ sa zalegle — inaczej pierwsza sesja z nowym zestawem byla by pusta.
     */
    fun dueEntryIds(
        progress: Map<String, WordProgress>,
        entryIds: List<String>,
        direction: Direction,
        today: Int,
    ): List<String> = entryIds.filter { isDue(progressOf(progress, it, direction), today) }

    /**
     * Nowy stan slowa po jednej probie. [today] w dniach od epoki UTC (patrz [dayOf]).
     *
     * Reguly, kazda z powodem:
     *
     *  - PRZERWANA nie zmienia NICZEGO. Awaria silnika mowy, cofniecie sie z ekranu i cisza w
     *    mikrofonie to nie sa bledy ucznia; karanie za nie uczy omijania trybu glosowego.
     *
     *  - BLEDNA cofa do pudelka 1 i ustawia termin na dzis, wiec slowo wraca do kolejki jeszcze
     *    w tej sesji. Slowo bez historii (pudelko 0) NIE awansuje przy okazji pomylki do
     *    pudelka 1 — "wroc do pierwszego pudelka" dotyczy tych, ktore z niego wyszly.
     *
     *  - PODPOWIEDZ nie cofa pudelka (uczen jednak wyprodukowal odpowiedz), ale nie przesuwa
     *    harmonogramu do przodu i przywraca termin na dzis. Slowo, przy ktorym trzeba bylo
     *    odkryc litery, nie jest pamietane — jest rozpoznawane po ksztalcie.
     *
     *  - POKAZANA nie rusza ani pudelka, ani terminu; znaczy tylko tyle, ze tego dnia uczen
     *    potrzebowal przy tym slowie pomocy.
     *
     *  - POPRAWNA przesuwa harmonogram TYLKO wtedy, gdy jest sprawdzeniem w terminie: slowo
     *    bylo zalegle, tego dnia nie bylo przy nim pomocy i nie bylo jeszcze awansu. Stad biora
     *    sie trzy rozne rzeczy z zalozen: pierwsza nauka (pudelko 0 -> 1), poprawka po bledzie
     *    w tej samej sesji (nic, bo tego dnia byla pomoc) i sprawdzenie w pozniejszym terminie
     *    (awans). Kolejne trafienia tego samego dnia nie przesuwaja nic, bo po awansie termin
     *    lezy juz w przyszlosci.
     *
     * "Utrwalone" wychodzi z tego samo: pudelko 3 wymaga awansu 0->1, potem 1->2 i 2->3, a
     * kazdy awans ustawia termin co najmniej na jutro. Seria klikniec jednego dnia nie ma jak
     * tego przejsc — potrzebne sa trzy rozne dni i dwa PRZYPOMNIENIA.
     */
    fun apply(before: WordProgress, attempt: Attempt, today: Int): WordProgress {
        // Ta sama proba przetworzona dwa razy nie moze dac dwoch awansow.
        if (attempt.id.isNotEmpty() && attempt.id == before.lastAttemptId) return before

        val po = when (attempt.outcome) {
            Outcome.PRZERWANA -> return before

            Outcome.BLEDNA -> before.copy(
                box = if (before.box > FIRST_BOX) FIRST_BOX else before.box,
                dueDay = today,
                assistedDay = today,
                mistakes = before.mistakes + 1,
            )

            Outcome.PODPOWIEDZ -> before.copy(
                dueDay = today,
                assistedDay = today,
                hints = before.hints + 1,
            )

            Outcome.POKAZANA -> before.copy(assistedDay = today)

            Outcome.POPRAWNA -> promote(before, attempt.task, today)
        }
        return po.copy(lastTask = attempt.task, lastAttemptId = attempt.id)
    }

    /** [apply] na calej mapie postepu; klucz sklada [key]. */
    fun applyTo(
        progress: Map<String, WordProgress>,
        attempt: Attempt,
        today: Int,
    ): Map<String, WordProgress> {
        val klucz = key(attempt.entryId, attempt.direction)
        val przed = progress[klucz] ?: WordProgress()
        val po = apply(przed, attempt, today)
        if (po == przed) return progress
        return progress + (klucz to po)
    }

    private fun promote(before: WordProgress, task: TaskKind, today: Int): WordProgress {
        val wTerminie = isDue(before, today)
        val bezPomocyDzis = before.assistedDay != today
        val pierwszyAwansDzis = before.lastPromotionDay != today
        if (!wTerminie || !bezPomocyDzis || !pierwszyAwansDzis) return before

        val nowe = minOf(before.box + 1, ceilingFor(task))
        if (nowe <= before.box) {
            // Sufit rodzaju zadania albo pudelko 3: pudelka nie podnosimy, ale slowo bylo
            // zalegle i zostalo sprawdzone, wiec nie moze zostac zalegle na zawsze.
            return before.copy(dueDay = today + intervalDays(before.box))
        }
        return before.copy(
            box = nowe,
            dueDay = today + intervalDays(nowe),
            lastPromotionDay = today,
        )
    }
}
