package com.matura.app.learn

import com.matura.app.model.Dorobek
import com.matura.app.model.SaveData
import com.matura.app.model.ScoreRecord
import com.matura.app.model.TaskKind
import com.matura.app.model.WordProgress

/**
 * Za co ksiega dorobku dopisuje punkty nauki. Kazda stala to JEDNO przejscie, ktore policzyl
 * harmonogram ([Srs]): slowo weszlo o pudelko wyzej albo zostalo potwierdzone w terminie.
 * Innych zrodel punktow nie ma i byc nie moze, bo tylko takie przejscie dowodzi, ze uczen
 * cos o slowie pokazal. Trafienie potworka, szybkosc i seria w grze to punkty GRY (wynik
 * rundy, Profile.bestScore) i do tej ksiegi nie wchodza.
 *
 * Pierwsza droga slowa do utrwalenia jest warta 10 + 20 + 30 punktow, coraz wiecej z kazdym
 * szczeblem, bo kazdy nastepny wymaga pamietania slowa przez dluzsza przerwe. Powrot na
 * szczebel, na ktorym slowo juz stalo, jest wart tyle, co zwykla powtorka: gdyby byl wart
 * tyle, co pierwsze wejscie, oplacaloby sie mylic. Z tej samej tabeli migracja zapisu wylicza
 * punkty na start ([Nagrody.punktyNaStart]), wiec slowo zastane w pudelku 3 dostaje tyle, ile
 * ta droga.
 *
 * [zPamieci] rozdziela dwa rodzaje dowodu tak samo, jak robi to [Srs.ceilingFor]: wybranie
 * odpowiedzi sposrod podanych jest warte symboliczne trzy punkty i tylko za piec pierwszych
 * slow dziennie ([Nagrody.LIMIT_ROZPOZNAN]), bo przy dwoch przyciskach trafia sie co drugi raz
 * bez zadnej wiedzy. [powtorka] mowi, czy zaliczenie wchodzi do licznika powtorek w terminie
 * (Dorobek.powtorki): liczy sie kazde przypomnienie slowa, ktore WROCILO, a pierwsze spotkanie
 * powrotem nie jest.
 *
 * Nazwy stalych NIE sa czescia zapisu (ksiega trzyma same sumy), wiec wolno je zmieniac.
 * Kolejnosc stalych to kolejnosc wierszy na ekranie wyniku. [pl] i [en] to etykiety tych
 * wierszy, pisane czcionka systemowa; liczbe zaliczen i punkty dopisuje ekran.
 */
enum class Zaliczenie(
    val xp: Int,
    val zPamieci: Boolean,
    val powtorka: Boolean,
    val pl: String,
    val en: String,
) {
    /** Pudelko 0 -> 1 odpowiedzia z pamieci. */
    NOWE(10, true, false, "Nowe z pamięci", "New from memory"),

    /** Pudelko 1 -> 2 pierwszy raz: slowo przypomniane po przerwie. */
    AWANS(20, true, true, "Pierwsza powtórka po przerwie", "First review after a break"),

    /** Pudelko 2 -> 3 pierwszy raz: slowo utrwalone. */
    UTRWALENIE(30, true, true, "Utrwalone", "Consolidated"),

    /**
     * Kazde inne sprawdzenie z pamieci, ktore harmonogram przyjal: powrot na szczebel zdobyty
     * juz wczesniej albo potwierdzenie slowa z pudelka 3.
     */
    POWTORKA(5, true, true, "Powtórki w terminie", "On-time reviews"),

    /** Pudelko 0 -> 1 zadaniem z wyborem odpowiedzi (wielokrotny wybor, prawda/falsz). */
    ROZPOZNANE(3, false, false, "Rozpoznane w trybach wyboru", "Recognised in choice modes"),
}

/**
 * Dzienny limit, przez ktory zaliczenie nie dalo punktow. Zaliczenie i tak sie odbylo: slowo
 * awansowalo w harmonogramie, a ekran ma to uczniowi powiedziec zdaniem ([pl], [en], czcionka
 * systemowa), zamiast zostawic go z zerem bez wyjasnienia. Liczby w zdaniach to
 * [Nagrody.LIMIT_ROZPOZNAN] i [Nagrody.LIMIT_WLASNYCH]; pilnuje tego test.
 */
enum class Limit(val pl: String, val en: String) {
    /** Szoste i kolejne rozpoznanie nowego slowa tego samego dnia. */
    ROZPOZNANIA(
        "Tryby wyboru dają XP za 5 nowych słów dziennie. Kolejne ćwiczą się normalnie.",
        "Choice modes pay XP for 5 new words a day. The rest still count as practice.",
    ),

    /** Jedenaste i kolejne zaliczenie slowa spoza slownika gry tego samego dnia. */
    WLASNE(
        "Własne zestawy dają XP za 10 słów dziennie. Kolejne ćwiczą się normalnie.",
        "Your own sets pay XP for 10 words a day. The rest still count as practice.",
    ),
}

/**
 * Etapy dorobku, nazwane po kamieniach milowych klasy maturalnej: od zlozenia deklaracji we
 * wrzesniu po wakacje po egzaminie. Etap wynika WPROST z sumy punktow nauki, pokazuje wlozona
 * prace, niczego nie obiecuje i nie jest zapisywany, wiec progi i tytuly sa zwyklymi danymi:
 * mozna je przestawiac i przepisywac w tej jednej tabeli bez migracji. Stale musza zostac
 * w kolejnosci rosnacych progow, a pierwszy prog musi byc zerem (pilnuje tego test).
 *
 * Same kamienie milowe, zadnych tytulow dla osoby: tytul w rodzaju "postrach arkuszy" brzmialby
 * jak ocena ucznia, a etap mowi tylko, ile pracy za nim. Po ostatnim etapie rosnie licznik
 * okrazen ([Nagrody.okrazenie]), zeby dalsza nauka nie stala w miejscu.
 *
 * Tytuly sa pisane wersalikami, bo ida na ekran czcionka pikselowa, ktora ma polskie litery,
 * ale nie ma nawiasow ani cudzyslowow; mieszcza sie w 24 znakach.
 */
enum class Etap(val prog: Int, val pl: String, val en: String) {
    DEKLARACJA(0, "DEKLARACJA ZŁOŻONA", "DECLARATION FILED"),
    KARTKOWKA(30, "KARTKÓWKA", "POP QUIZ"),
    KLASOWKA(150, "KLASÓWKA", "CLASS TEST"),
    PROBNA_MATURA(500, "PRÓBNA MATURA", "MOCK EXAM"),
    POLROCZE(1200, "PÓŁROCZE", "END OF TERM"),
    STUDNIOWKA(2500, "STUDNIÓWKA", "PROM NIGHT"),
    POLONEZ(4000, "POLONEZ", "POLONAISE"),
    OSTATNI_DZWONEK(5500, "OSTATNI DZWONEK", "LAST BELL"),
    KASZTANY(7000, "KASZTANY KWITNĄ", "CHESTNUTS IN BLOOM"),
    CZARNY_DLUGOPIS(8500, "CZARNY DŁUGOPIS", "BLACK PEN"),
    ARKUSZ(10000, "ARKUSZ OTWARTY", "PAPER OPENED"),
    KARTA_ODPOWIEDZI(12000, "KARTA ODPOWIEDZI", "ANSWER SHEET"),
    WAKACJE(15000, "WAKACJE", "SUMMER BREAK");

    /** Numer etapu do pokazania ("ETAP 4 Z 13"): od jednego. */
    val numer: Int get() = ordinal + 1
}

/**
 * Co jedno zaliczenie zrobilo w ksiedze: jego rodzaj, punkty NAPRAWDE dopisane i limit, ktory
 * je ewentualnie zabral. [xp] rowne zero znaczy zawsze [limit] rozny od null i odwrotnie.
 *
 * Repository.recordAttempt oddaje to wolajacemu po udanym zapisie, a ekran wyniku rozpisuje
 * z tego runde ([Nagrody.roznica]). Dzieki temu rozpisanie mowi o tym, co trafilo na dysk,
 * a nie o tym, co wyszloby z ponownego przeliczenia prob.
 */
data class Wplyw(val rodzaj: Zaliczenie, val xp: Int, val limit: Limit?)

/** Wynik [Nagrody.dolicz]: ksiega po zaliczeniu i to, co zaliczenie w niej zrobilo. */
data class Doliczenie(val dorobek: Dorobek, val wplyw: Wplyw)

/** Jeden wiersz rozpisania rundy: rodzaj zaliczenia, ile ich bylo i ile dopisaly punktow. */
data class PozycjaNagrody(
    val rodzaj: Zaliczenie,

    /** Ile zaliczen tego rodzaju padlo w rundzie, razem z tymi, ktorym limit zabral punkty. */
    val ile: Int,

    /** Punkty naprawde dopisane za te zaliczenia. */
    val xp: Int,
)

/**
 * Co jedna runda dala dorobkowi: roznica dwoch stanow zapisu, sprzed rundy i po niej, oraz
 * rozpisanie z zaliczen, ktore oddalo Repository. Liczy ja [Nagrody.roznica]; ekran wyniku ma
 * z tego tylko czytac.
 *
 * Tak jak w [WynikRundy], kazde pole jest liczba policzona z danych. Runda bez punktow tez ma
 * swoj obiekt: ekran pisze wtedy zdanie, dlaczego punktow nie ma, zamiast przemilczec nauke.
 */
data class NagrodaRundy(
    /** Punkty nauki, o ktore ksiega urosla w tej rundzie. */
    val xp: Int,

    /** Zaliczenia rundy wedlug rodzaju, w kolejnosci stalych [Zaliczenie], bez pustych wierszy. */
    val rozpisanie: List<PozycjaNagrody>,

    /** Dzienne limity, ktore w tej rundzie zabraly punkty choc jednemu zaliczeniu. */
    val limity: Set<Limit>,

    /** Punkty nauki na dzis po rundzie i cel dnia, do ktorego sie odnosza. */
    val xpDzisPo: Int,
    val cel: Int,

    /** Cel dnia zostal zaliczony WLASNIE w tej rundzie (przed nia jeszcze nie byl). */
    val celZaliczonyTeraz: Boolean,

    /** Dzisiejszy dzien jest w ksiedze zaliczony, w tej rundzie albo wczesniej. */
    val celZaliczony: Boolean,

    /** Ta runda zaliczyla pierwszy dzien nauki w historii ksiegi. */
    val pierwszyDzien: Boolean,

    /** Ta runda zaczela serie od nowa po przerwie, ktorej zapas dni wolnych nie pokryl. */
    val nowaSeria: Boolean,

    /** Ile dni wolnych z zapasu zuzylo zaliczenie dzisiejszego dnia: 0, 1 albo 2. */
    val wolneUzyte: Int,

    /** Ile dni wolnych doszlo do zapasu w tej rundzie: 0 albo 1. */
    val wolneDodane: Int,

    /** Seria do pokazania po rundzie ([Nagrody.seriaNaDzis]). */
    val seriaPo: Int,

    /** Dni nauki lacznie, po rundzie. */
    val dniNauki: Int,

    val etapPrzed: Etap,
    val etapPo: Etap,

    /** Okrazenia na ostatnim etapie ([Nagrody.okrazenie]) przed runda i po niej. */
    val okrazeniePrzed: Int,
    val okrazeniePo: Int,

    /**
     * Odznaki do ogloszenia, w kolejnosci stalych [Odznaka]: zdobyte w tej rundzie oraz te,
     * ktore czekaly niepokazane od wczesniej (runda przerwana wyjsciem).
     */
    val odznaki: List<Odznaka>,

    /** Wynik gry pobil najlepszy wczesniejszy wynik w tym samym trybie. */
    val rekord: Boolean,

    /**
     * Najlepszy wczesniejszy wynik w tym trybie; null, gdy runda nie szla o wynik albo to
     * pierwsza gra w tym trybie (pierwszy wynik nie jest rekordem, bo nie ma czego pobic).
     */
    val poprzedniRekord: Int?,

    /** W tej rundzie licznik nowych slow na dzis przekroczyl [Nagrody.DUZO_NOWYCH]. */
    val noweDzisPrzekroczone: Boolean,
) {
    /** Runda wniosla na wyzszy etap; ekran ma wtedy co oglosic. */
    val nowyEtap: Boolean get() = etapPo.ordinal > etapPrzed.ordinal

    /** Runda domknela okrazenie na ostatnim etapie (samo wejscie na ten etap to [nowyEtap]). */
    val noweOkrazenie: Boolean get() = !nowyEtap && okrazeniePo > okrazeniePrzed
}

/**
 * Punkty nauki, cel dnia, seria z zapasem dni wolnych i etapy: czysta arytmetyka nad
 * przejsciami, ktore policzyl [Srs]. Bez Androida, bez zegara i bez zapisu; ksiege ([Dorobek])
 * utrwala Repository.
 *
 * DZIEN w tym pliku to dzien harmonogramu ([Srs.dayOf], doba UTC), ten sam, ktorym liczy sie
 * termin powtorki. Celowo jeden zegar: harmonogram przyjmuje jedno sprawdzenie slowa na dobe,
 * wiec doba celu dnia musi byc ta sama doba. Przy dwoch zegarach (lokalnym dla celu, UTC dla
 * harmonogramu) jeden wieczor nauki rozpadalby sie na dwa dni celu, przed lokalna polnoca i po
 * niej, a slowa zaliczonego przed polnoca nie daloby sie zaliczyc w "nowym dniu" jeszcze przez
 * godzine albo dwie. Koszt jest ten sam, co w [Srs]: doba lamie sie o 01:00 zima i o 02:00
 * latem czasu polskiego. Jedyny wyjatek to [odliczanie], ktore bierze dzien z kalendarza na
 * scianie ([Kalendarz]) i z ksiega nie ma nic wspolnego.
 *
 * Czego tu NIE ma: procentu gotowosci, prognozy wyniku i punktow za sama aktywnosc. Jest suma
 * punktow za zaliczenia i kilka licznikow dni.
 */
object Nagrody {

    /** Dozwolone cele dnia w punktach nauki, od najmniejszego. Ekran opcji ma oferowac te. */
    val CELE_DNIA: List<Int> = listOf(30, 60, 120)

    /** Cel dnia dla nowego ucznia; rowny wartosci domyslnej Options.celDnia. */
    const val CEL_DOMYSLNY = 30

    /**
     * Za ile rozpoznan dziennie sa punkty. Piec razy trzy punkty to pietnascie, polowa
     * najnizszego celu dnia: samymi trybami wyboru celu nie da sie zaliczyc, zawsze trzeba
     * choc kilku slow wpisanych albo powiedzianych z pamieci.
     */
    const val LIMIT_ROZPOZNAN = 5

    /**
     * Za ile zaliczen slow SPOZA slownika gry sa dziennie punkty. Wlasna lista to tez nauka,
     * wiec placi, ale bez limitu lista stu latwych slow dawalaby tysiac punktow jednego
     * popoludnia. Jedna stala: zmiana tej liczby (albo zniesienie limitu) to zmiana w tym
     * wierszu i w zdaniu [Limit.WLASNE].
     */
    const val LIMIT_WLASNYCH = 10

    /** Najwiekszy zapas dni wolnych (Dorobek.wolne). */
    const val MAX_WOLNYCH = 2

    /** Co ile dni serii do zapasu dochodzi jeden dzien wolny. */
    const val DNI_NA_WOLNE = 7

    /** Ile punktow ma jedno okrazenie po ostatnim etapie. */
    const val OKRAZENIE = 2500

    /**
     * Od ilu nowych slow jednego dnia ekran ma powiedziec, ze reszta moze poczekac: wszystkie
     * wroca jutro jako powtorki, a sesja jutra ma swoja pojemnosc.
     */
    const val DUZO_NOWYCH = 30

    /**
     * Cel dnia do uzycia. Wartosc spoza [CELE_DNIA] (plik poprawiany recznie, uszkodzony
     * zapis, cel sprzed zmiany jednostki ze slow na punkty) jest czytana jak domyslna: zero
     * albo liczba ujemna zaliczalyby cel bez nauki.
     */
    fun celDnia(zapisany: Int): Int = if (zapisany in CELE_DNIA) zapisany else CEL_DOMYSLNY

    /**
     * Co proba dala dorobkowi; null znaczy "nic". [przed] i [po] to stan slowa sprzed
     * [Srs.apply] i po nim.
     *
     * Zaliczenie jest TYLKO za poprawna, samodzielna odpowiedz, ktora harmonogram przyjal jako
     * sprawdzenie w terminie, czyli taka, po ktorej pudelko wzroslo albo termin przesunal sie
     * do przodu. Pomylka, podpowiedz, pokazana odpowiedz i proba przerwana nie daja nic. Kolejne
     * trafienie tego samego dnia tez nic: [Srs] nie rusza wtedy stanu, wiec nie ma przejscia.
     * Stad bierze sie cala odpornosc na nabijanie: jedno slowo w jednym kierunku to najwyzej
     * jedno zaliczenie dziennie, a proba przetworzona drugi raz (ten sam znacznik) nie daje
     * drugiego.
     *
     * Rozpoznanie zarabia wylacznie za wyjscie z pudelka 0. Przesuniecie terminu slowa
     * z pudelka 1 kaflem wyboru jest zmiana w harmonogramie, ale nie jest dowodem pamieci,
     * a placone dawaloby punkty codziennie za te same slowa bez jednej wpisanej litery.
     *
     * "Pierwszy raz" rozstrzyga [WordProgress.szczyt]: wejscie na szczebel, na ktorym slowo
     * juz kiedys stalo, to [Zaliczenie.POWTORKA]. Szczyt bierzemy razem z pudelkiem, bo stan
     * sprzed migracji moze miec pudelko wyzej niz szczyt.
     */
    fun zaliczenie(przed: WordProgress, po: WordProgress, proba: Attempt): Zaliczenie? {
        if (proba.outcome != Outcome.POPRAWNA) return null
        val awans = po.box > przed.box
        val potwierdzenie = po.box == przed.box && po.dueDay > przed.dueDay
        if (!awans && !potwierdzenie) return null

        if (proba.task == TaskKind.ROZPOZNANIE) {
            val pierwszyKontakt = przed.box == Srs.NO_BOX && po.box == Srs.FIRST_BOX
            return if (pierwszyKontakt) Zaliczenie.ROZPOZNANE else null
        }

        if (!awans || po.box <= maxOf(przed.szczyt, przed.box)) return Zaliczenie.POWTORKA
        return when (po.box) {
            Srs.FIRST_BOX -> Zaliczenie.NOWE
            Srs.MAX_BOX -> Zaliczenie.UTRWALENIE
            else -> Zaliczenie.AWANS
        }
    }

    /**
     * Punkty, z ktorymi do ksiegi wchodzi slowo ZASTANE w danym pudelku. Uzywa tego wylacznie
     * migracja zapisu (Repository.migrate, krok 3 -> 4): zapis sprzed ksiegi nie ma zaliczen,
     * ma tylko pudelka, wiec slowo dostaje tyle, ile ta sama droga dalaby dzis po raz pierwszy.
     * Pudelko 1 zdobyte rozpoznaniem jest warte tyle, co rozpoznanie. Powtorek w terminie
     * i powrotow po bledzie z pudelka nie widac, wiec nie ma ich w tej sumie: nie zgadujemy.
     */
    fun punktyNaStart(stan: WordProgress): Int = when (stan.box.coerceIn(Srs.NO_BOX, Srs.MAX_BOX)) {
        Srs.NO_BOX -> 0
        Srs.FIRST_BOX -> when (stan.lastTask) {
            TaskKind.ROZPOZNANIE -> Zaliczenie.ROZPOZNANE.xp
            else -> Zaliczenie.NOWE.xp
        }
        Srs.MAX_BOX -> Zaliczenie.NOWE.xp + Zaliczenie.AWANS.xp + Zaliczenie.UTRWALENIE.xp
        else -> Zaliczenie.NOWE.xp + Zaliczenie.AWANS.xp
    }

    /**
     * Dopisuje zaliczenie do ksiegi i mowi, co ono w niej zrobilo.
     *
     * @param dzien dzien proby.
     * @param cel cel dnia z opcji, juz przez [celDnia].
     * @param wlasne slowo jest spoza slownika gry (wlasny zestaw, wiersz dopisany przez ucznia).
     * @param naprawa to zaliczenie jest pierwszym utrwaleniem slowa ze slownika gry, przy
     *   ktorym uczen wczesniej sie pomylil. Rozstrzyga wolajacy, bo tu nie widac stanu slowa.
     *
     * DZIEN. Gdy ksiega pamieta inny dzien niz [dzien], cztery liczniki "na dzis" zaczynaja od
     * zera. Wczorajsze nie przepadaja, bo wczorajszy cel zostal policzony w chwili, w ktorej
     * padl. Dotyczy to takze dnia WCZESNIEJSZEGO niz zapisany (zegar telefonu cofniety):
     * ksiega idzie za zegarem, a nie czeka, az kalendarz ja dogoni. Przy czekaniu jedno zle
     * ustawienie daty zamrazaloby uczciwemu uczniowi cel dnia na cale dni.
     *
     * COFNIETY ZEGAR A SERIA. Gdy ostatni zaliczony dzien lezy w przyszlosci wzgledem [dzien],
     * przenosimy go na [dzien] i NIC nie doliczamy: ten dzien uchodzi za juz zajety, a kolejny
     * prawdziwy dzien liczy sie znow normalnie. Znany i przyjety koszt: kto celowo przestawia
     * zegar tam i z powrotem, moze zaliczyc jeden dzien kalendarza dwa razy. Nic tu nie idzie
     * do rankingu, wiec oszukuje tylko siebie.
     *
     * LIMITY. Zaliczenie ponad dzienny limit zostaje zaliczeniem (slowo awansowalo), ale
     * punktow nie dopisuje; [Wplyw.limit] mowi, ktory limit zadzialal. Limit wlasnych slow ma
     * pierwszenstwo, bo obejmuje kazdy rodzaj zaliczenia, takze rozpoznanie.
     *
     * CEL DNIA liczy sie w chwili, w ktorej punktow na dzis robi sie tyle, ile wynosi cel,
     * i tylko raz na dzien ([Dorobek.seriaDzien] pamieta, ze ten dzien juz wszedl). Dlatego
     * podniesienie celu pozniej tego samego dnia niczego nie cofa. Obnizenie celu ponizej
     * dzisiejszych punktow samo dnia nie zalicza: robi to dopiero nastepne zaliczenie, bo
     * ksiega zmienia sie wylacznie przy nauce (zmiana opcji nie zna zegara).
     *
     * SERIA rosnie, gdy poprzedni zaliczony dzien byl wczoraj. Dni opuszczone po drodze
     * pokrywa zapas dni wolnych ([Dorobek.wolne]): jesli starcza na wszystkie, zapas maleje
     * o tyle, a seria rosnie dalej; jesli nie starcza, seria zaczyna sie od jednego, a zapas
     * zostaje nietkniety. Za kazde [DNI_NA_WOLNE] dni serii dochodzi jeden dzien wolny, do
     * [MAX_WOLNYCH]. Rekord serii i liczba dni nauki nie maleja nigdy.
     */
    fun dolicz(
        d: Dorobek,
        z: Zaliczenie,
        dzien: Int,
        cel: Int,
        wlasne: Boolean,
        naprawa: Boolean,
    ): Doliczenie {
        // Srs.dayOf nie odda NO_DAY; to tylko straznik, zeby "nigdy" nie stalo sie dniem ksiegi.
        if (dzien == WordProgress.NO_DAY) {
            return Doliczenie(d.copy(xp = d.xp + z.xp), Wplyw(z, z.xp, null))
        }

        val tenSamDzien = d.dzien == dzien
        val xpDzis = if (tenSamDzien) d.xpDzis else 0
        val noweDzis = if (tenSamDzien) d.noweDzis else 0
        val rozpoznaneDzis = if (tenSamDzien) d.rozpoznaneDzis else 0
        val wlasneDzis = if (tenSamDzien) d.wlasneDzis else 0

        // NO_DAY to Int.MIN_VALUE: najpierw rownosc, dopiero potem porownanie dni.
        val zPrzyszlosci = d.seriaDzien != WordProgress.NO_DAY && d.seriaDzien > dzien
        val seriaDzien = if (zPrzyszlosci) dzien else d.seriaDzien

        val limit = when {
            wlasne && wlasneDzis >= LIMIT_WLASNYCH -> Limit.WLASNE
            z == Zaliczenie.ROZPOZNANE && rozpoznaneDzis >= LIMIT_ROZPOZNAN -> Limit.ROZPOZNANIA
            else -> null
        }
        val zaplacone = if (limit == null) z.xp else 0
        val platne = zaplacone > 0
        val pierwszeSpotkanie = z == Zaliczenie.NOWE || z == Zaliczenie.ROZPOZNANE

        val poDniu = d.copy(
            xp = d.xp + zaplacone,
            dzien = dzien,
            xpDzis = xpDzis + zaplacone,
            // Spotkania liczymy takze bez punktow: licznik mowi, ile slow wroci jutro.
            noweDzis = noweDzis + (if (pierwszeSpotkanie) 1 else 0),
            rozpoznaneDzis = rozpoznaneDzis + (if (platne && z == Zaliczenie.ROZPOZNANE) 1 else 0),
            wlasneDzis = wlasneDzis + (if (platne && wlasne) 1 else 0),
            seriaDzien = seriaDzien,
            powtorki = d.powtorki + (if (platne && z.powtorka) 1 else 0),
            naprawione = d.naprawione + (if (naprawa) 1 else 0),
        )
        val wplyw = Wplyw(z, zaplacone, limit)

        // coerceAtLeast: przy celu zerowym albo ujemnym dzien zaliczalby sie bez jednego punktu.
        if (poDniu.xpDzis < cel.coerceAtLeast(1) || seriaDzien == dzien) {
            return Doliczenie(poDniu, wplyw)
        }

        // Zapas z pliku poprawianego recznie moze byc dowolna liczba; liczymy na dozwolonej.
        val zapas = d.wolne.coerceIn(0, MAX_WOLNYCH)
        var wolne = zapas
        val seria = if (seriaDzien == WordProgress.NO_DAY) {
            1
        } else {
            // Long, bo roznica dwoch dalekich dni nie miesci sie w Int. Nie jest ujemna:
            // dzien z przyszlosci zostal wyzej przeniesiony na dzis i tu juz nie dochodzi.
            val opuszczone = dzien.toLong() - seriaDzien - 1
            if (opuszczone <= zapas) {
                wolne = zapas - opuszczone.toInt()
                d.seria.coerceAtLeast(0) + 1
            } else {
                1
            }
        }
        if (seria % DNI_NA_WOLNE == 0) wolne = minOf(MAX_WOLNYCH, wolne + 1)

        return Doliczenie(
            poDniu.copy(
                seria = seria,
                seriaDzien = dzien,
                wolne = wolne,
                rekordSerii = maxOf(d.rekordSerii, seria),
                dniNauki = d.dniNauki + 1,
            ),
            wplyw,
        )
    }

    /**
     * Seria do pokazania DZIS. [Dorobek.seria] trzyma dlugosc ostatniego lancucha takze wtedy,
     * gdy ten juz sie urwal (ksiega zmienia sie tylko przy nauce), wiec ekran nie moze czytac
     * go wprost. Seria zyje, dopoki opuszczone dni da sie pokryc zapasem dni wolnych, czyli
     * dokladnie tak dlugo, jak dzisiejszy cel by ja przedluzyl; potem pokazujemy zero.
     */
    fun seriaNaDzis(d: Dorobek, dzien: Int): Int {
        if (d.seriaDzien == WordProgress.NO_DAY) return 0
        return if (przerwa(d, dzien) <= zapas(d)) d.seria else 0
    }

    /**
     * Ile opuszczonych dni seria wlasnie przeczekuje na zapasie dni wolnych: 0, 1 albo 2.
     * Wiecej niz zero znaczy "seria czeka": zyje, ale dzisiejszy cel zuzyje tyle dni wolnych.
     * Zero jest i wtedy, gdy nic nie opuszczono, i wtedy, gdy seria juz sie urwala.
     */
    fun seriaCzeka(d: Dorobek, dzien: Int): Int {
        if (d.seriaDzien == WordProgress.NO_DAY) return 0
        val opuszczone = przerwa(d, dzien)
        return if (opuszczone in 1..zapas(d)) opuszczone.toInt() else 0
    }

    /** Dni miedzy ostatnim zaliczonym dniem a [dzien]; ujemna, gdy to ten sam albo wczesniejszy. */
    private fun przerwa(d: Dorobek, dzien: Int): Long = dzien.toLong() - d.seriaDzien - 1

    private fun zapas(d: Dorobek): Int = d.wolne.coerceIn(0, MAX_WOLNYCH)

    /** Punkty nauki z dnia [dzien]; zero, gdy ksiega pamieta inny dzien. */
    fun xpNaDzis(d: Dorobek, dzien: Int): Int = if (tenSam(d.dzien, dzien)) d.xpDzis else 0

    /** Slowa spotkane pierwszy raz w dniu [dzien]; zero, gdy ksiega pamieta inny dzien. */
    fun noweNaDzis(d: Dorobek, dzien: Int): Int = if (tenSam(d.dzien, dzien)) d.noweDzis else 0

    /**
     * Czy dzien [dzien] wszedl do ksiegi jako dzien z celem. To jest odpowiedz KSIEGI, a nie
     * porownanie punktow z celem: dzien raz zaliczony zostaje zaliczony takze po podniesieniu
     * celu, a ekran ma wtedy dalej pokazywac pelny pierscien.
     */
    fun celZaliczony(d: Dorobek, dzien: Int): Boolean = tenSam(d.seriaDzien, dzien)

    private fun tenSam(zapisany: Int, dzien: Int): Boolean =
        zapisany != WordProgress.NO_DAY && zapisany == dzien

    /**
     * Stan pierscienia celu dnia: 0..4, czyli puste kolo i cztery cwiartki.
     *
     * Pelny pierscien jest TYLKO dla dnia zaliczonego w ksiedze ([celZaliczony]), nigdy z samej
     * arytmetyki: po obnizeniu celu punktow bywa juz dosc, a dzien jeszcze nie jest policzony,
     * i pelne kolo obok niezaliczonej serii byloby nieprawda. Ponizej liczy sie pelne cwiartki,
     * bez naciagania pierwszej. Pierscien zawsze stoi obok liczby, wiec puste kolo przy
     * "5 / 30 XP" nie mowi "nic nie zrobiles".
     */
    fun pierscien(xpDzis: Int, cel: Int, zaliczony: Boolean): Int {
        if (zaliczony) return 4
        if (xpDzis <= 0) return 0
        return (xpDzis.toLong() * 4 / cel.coerceAtLeast(1)).coerceIn(0L, 3L).toInt()
    }

    /** Etap dla sumy punktow nauki. Ujemna suma (uszkodzony zapis) to pierwszy etap. */
    fun etap(xp: Int): Etap = Etap.entries.lastOrNull { xp >= it.prog } ?: Etap.entries.first()

    /** Nastepny etap; null na ostatnim. */
    fun nastepny(xp: Int): Etap? = Etap.entries.getOrNull(etap(xp).ordinal + 1)

    /** Ile punktow brakuje do nastepnego etapu; zero na ostatnim (tam rosna okrazenia). */
    fun doNastepnego(xp: Int): Int = nastepny(xp)?.let { it.prog - xp.coerceAtLeast(0) } ?: 0

    /**
     * Okrazenie na ostatnim etapie: zero ponizej niego, jeden od wejscia na ostatni etap
     * i o jeden wiecej co [OKRAZENIE] punktow. Ekran pokazuje je od drugiego, jako mnoznik
     * przy tytule etapu.
     */
    fun okrazenie(xp: Int): Int {
        val szczyt = Etap.entries.last().prog
        return if (xp < szczyt) 0 else 1 + (xp - szczyt) / OKRAZENIE
    }

    /**
     * Pasek etapu: ile punktow zrobione i ile ma caly odcinek, w obrebie biezacego etapu,
     * a na ostatnim etapie w obrebie biezacego okrazenia. Pierwsza liczba jest zawsze mniejsza
     * od drugiej: pelny pasek to juz poczatek nastepnego odcinka.
     */
    fun pasekEtapu(xp: Int): Pair<Int, Int> {
        val obecny = etap(xp)
        val wyzej = nastepny(xp) ?: return (xp - obecny.prog) % OKRAZENIE to OKRAZENIE
        return (xp - obecny.prog).coerceAtLeast(0) to (wyzej.prog - obecny.prog)
    }

    /**
     * Odliczanie do matury do pokazania; null, gdy uczen je wylaczyl albo termin minal.
     *
     * To jedyna funkcja w tym pliku, ktora bierze LOKALNY dzien kalendarzowy
     * ([Kalendarz.dzienLokalny]), a nie dzien harmonogramu: liczba ma sie zgadzac z kalendarzem
     * na scianie takze miedzy polnoca a pierwsza w nocy. Po terminie nie ma "0 dni" ani liczby
     * ujemnej, bo ekran ma wtedy zaproponowac nowa date, a nie odliczac wstecz.
     */
    fun odliczanie(maturaDzien: Int, dzienKalendarza: Int, wlaczone: Boolean): Odliczanie? {
        if (!wlaczone) return null
        val zostalo = Kalendarz.dniDoMatury(maturaDzien, dzienKalendarza)
        return when {
            zostalo < 0 -> null
            zostalo == 0 -> Odliczanie.Dzis
            zostalo == 1 -> Odliczanie.Jutro
            zostalo == Odliczanie.STO_DNI -> Odliczanie.Sto
            else -> Odliczanie.Dni(zostalo)
        }
    }

    /**
     * Co runda dala dorobkowi: czysta roznica dwoch stanow zapisu plus rozpisanie zaliczen.
     *
     * @param przed zapis z chwili WEJSCIA do rundy, sprzed pierwszej odpowiedzi.
     * @param po zapis po rundzie, czyli po Repository.recordGame (to tam stempluja sie odznaki
     *   za gre i tam ekran wyniku potwierdza odznaki jako pokazane).
     * @param wplywy to, co Repository.recordAttempt oddalo przez naWplyw za proby tej rundy,
     *   po kolei. Rozpisanie bierze sie WYLACZNIE stad: niczego nie przeliczamy drugi raz przez
     *   [Srs], bo drugie liczenie potrafi sie rozjechac z zapisem (nieudany zapis, runda przez
     *   polnoc), a wtedy ekran pokazywalby dwie rozne sumy.
     * @param wynik rekord oddany przez ekran gry, JUZ z wlasciwym trybem; null, gdy runda nie
     *   szla o wynik (spokojna runda sesji, runda przerwana). Bez wyniku nie ma rekordu.
     * @param dzien dzien harmonogramu, do ktorego odnosza sie "dzis" i seria: ten, w ktorym
     *   runda sie SKONCZYLA. Po rundzie, ktora przeszla przez polnoc UTC, ksiega pamieta juz
     *   nowy dzien i licznik "na dzis" liczony dniem wejscia do rundy pokazalby zero.
     */
    fun roznica(
        przed: SaveData,
        po: SaveData,
        wplywy: List<Wplyw>,
        wynik: ScoreRecord?,
        dzien: Int,
    ): NagrodaRundy {
        val a = przed.dorobek
        val b = po.dorobek

        val rozpisanie = Zaliczenie.entries.mapNotNull { rodzaj ->
            val tegoRodzaju = wplywy.filter { it.rodzaj == rodzaj }
            if (tegoRodzaju.isEmpty()) null
            else PozycjaNagrody(rodzaj, tegoRodzaju.size, tegoRodzaju.sumOf { it.xp })
        }
        val limity = Limit.entries.filterTo(LinkedHashSet()) { limit ->
            wplywy.any { it.limit == limit }
        }

        // Sam stempel dnia nie wystarcza: po cofnieciu zegara ksiega przenosi ostatni zaliczony
        // dzien na dzis, niczego nie doliczajac. Zaliczenie poznajemy po liczbie dni nauki.
        val zaliczonyTeraz =
            b.dniNauki > a.dniNauki && celZaliczony(b, dzien) && !celZaliczony(a, dzien)
        val pierwszyDzien = zaliczonyTeraz && a.dniNauki == 0
        val ciagDalszy =
            zaliczonyTeraz && a.seriaDzien != WordProgress.NO_DAY && b.seria == a.seria + 1
        // Zapasu nie da sie odjac wprost: w dniu, w ktorym seria dochodzi do siodemki, jeden
        // dzien wolny schodzi, a drugi dochodzi. Zuzycie widac po dniach miedzy stemplami.
        val wolneUzyte = if (ciagDalszy) {
            (b.seriaDzien.toLong() - a.seriaDzien - 1).coerceIn(0L, MAX_WOLNYCH.toLong()).toInt()
        } else {
            0
        }
        val wolneDodane = if (zaliczonyTeraz) {
            (zapas(b) - (zapas(a) - wolneUzyte)).coerceAtLeast(0)
        } else {
            0
        }

        // Rekord porownujemy z wynikami SPRZED rundy: w `po` nowy wynik juz lezy na liscie,
        // wiec kazda gra bilaby tam sama siebie.
        val najlepszy = wynik?.let { w ->
            przed.scores.filter { it.mode == w.mode }.maxOfOrNull { it.score }
        }
        val doOgloszenia = (b.odznaki.keys - a.odznaki.keys) + a.odznakiNowe

        return NagrodaRundy(
            // Ksiega nie maleje; ujemna roznica znaczylaby pomylone stany, a nie strate.
            xp = (b.xp - a.xp).coerceAtLeast(0),
            rozpisanie = rozpisanie,
            limity = limity,
            xpDzisPo = xpNaDzis(b, dzien),
            cel = celDnia(po.options.celDnia),
            celZaliczonyTeraz = zaliczonyTeraz,
            celZaliczony = celZaliczony(b, dzien),
            pierwszyDzien = pierwszyDzien,
            nowaSeria = zaliczonyTeraz && !pierwszyDzien && b.seria == 1,
            wolneUzyte = wolneUzyte,
            wolneDodane = wolneDodane,
            seriaPo = seriaNaDzis(b, dzien),
            dniNauki = b.dniNauki,
            etapPrzed = etap(a.xp),
            etapPo = etap(b.xp),
            okrazeniePrzed = okrazenie(a.xp),
            okrazeniePo = okrazenie(b.xp),
            odznaki = Odznaka.entries.filter { it.name in doOgloszenia },
            // Wynik zero albo ujemny nie jest poprzeczka: "pobicie" go to zadna nowina.
            rekord = wynik != null && najlepszy != null && najlepszy > 0 && wynik.score > najlepszy,
            poprzedniRekord = najlepszy,
            noweDzisPrzekroczone =
                noweNaDzis(a, dzien) < DUZO_NOWYCH && noweNaDzis(b, dzien) >= DUZO_NOWYCH,
        )
    }
}
