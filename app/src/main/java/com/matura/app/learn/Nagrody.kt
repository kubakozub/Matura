package com.matura.app.learn

import com.matura.app.model.Direction
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
 * [zPamieci] rozdziela dwa rodzaje dowodu tak samo, jak robi to [Srs.ceilingFor]: wybranie
 * odpowiedzi sposrod podanych jest warte symboliczne dwa punkty i NIE liczy sie do celu dnia,
 * bo przy dwoch przyciskach trafia sie co drugi raz bez zadnej wiedzy.
 *
 * Droga jednego slowa do utrwalenia jest warta 10 + 10 + 20 punktow. Powrot na szczebel, na
 * ktorym slowo juz stalo, jest wart mniej niz pierwsze wejscie: gdyby byl wart tyle samo,
 * oplacaloby sie mylic. Z tej samej tabeli migracja zapisu wylicza punkty na start
 * ([Nagrody.punktyNaStart]), wiec slowo zastane w pudelku 3 dostaje tyle, ile ta droga.
 *
 * Nazwy stalych NIE sa czescia zapisu (ksiega trzyma same sumy), wiec wolno je zmieniac.
 * [pl] i [en] to krotkie etykiety do rozpisania wyniku rundy; mieszcza sie w czcionce pikselowej.
 */
enum class Zaliczenie(val xp: Int, val zPamieci: Boolean, val pl: String, val en: String) {
    /** Pudelko 0 -> 1 zadaniem z wyborem odpowiedzi (ABCD, prawda/falsz). */
    ROZPOZNANE(2, false, "Rozpoznane", "Recognised"),

    /** Pudelko 0 -> 1 odpowiedzia z pamieci, pierwszy raz. */
    NOWE(10, true, "Nowe słowo", "New word"),

    /** Pudelko 1 -> 2 pierwszy raz: slowo przypomniane w pozniejszym terminie. */
    AWANS(10, true, "Pierwsza powtórka", "First review"),

    /** Pudelko 2 -> 3 pierwszy raz: slowo utrwalone. */
    UTRWALENIE(20, true, "Utrwalone", "Locked in"),

    /**
     * Kazde inne sprawdzenie z pamieci, ktore harmonogram przyjal i ktore ruszylo termin:
     * powrot na szczebel zdobyty juz wczesniej albo potwierdzenie slowa z pudelka 3.
     */
    POWTORKA(5, true, "Powtórka w terminie", "On-time review"),
}

/**
 * Szczeble dorobku, nazwane po rytualach klasy maturalnej: od zlozenia deklaracji we wrzesniu
 * po arkusz w maju. Szczebel wynika WPROST z sumy punktow nauki, niczego nie obiecuje i nie
 * jest zapisywany, wiec progi i tytuly sa zwyklymi danymi: mozna je przestawiac i przepisywac
 * w tej jednej tabeli bez migracji. Stale musza zostac w kolejnosci rosnacych progow, a
 * pierwszy prog musi byc zerem (pilnuje tego test).
 *
 * Tytuly sa pisane wersalikami, bo ida na ekran czcionka pikselowa, ktora ma polskie litery,
 * ale nie ma nawiasow ani cudzyslowow; mieszcza sie w 24 znakach.
 */
enum class Ranga(val prog: Int, val pl: String, val en: String) {
    DEKLARACJA(0, "DEKLARACJA ZŁOŻONA", "DECLARATION FILED"),
    PROBNA_MATURA(30, "PRÓBNA MATURA", "MOCK EXAM"),
    STUDNIOWKA(150, "STUDNIÓWKA", "PROM NIGHT"),
    POLONEZ(500, "POLONEZ", "POLONAISE"),
    OSTATNI_DZWONEK(1200, "OSTATNI DZWONEK", "LAST BELL"),
    KASZTANY(3000, "KASZTANY KWITNĄ", "CHESTNUTS IN BLOOM"),
    CZARNY_DLUGOPIS(6000, "CZARNY DŁUGOPIS", "BLACK PEN"),
    ARKUSZ(12000, "ARKUSZ OTWARTY", "PAPER OPENED"),
    KARTA_ODPOWIEDZI(25000, "KARTA ODPOWIEDZI", "ANSWER SHEET"),
    POSTRACH(50000, "POSTRACH ARKUSZY", "TERROR OF EXAM PAPERS"),
}

/**
 * Co jedna runda dala dorobkowi: roznica dwoch stanow zapisu, sprzed rundy i po niej.
 * Liczy ja [Nagrody.roznica]; ekran wyniku ma z tego tylko czytac.
 *
 * Tak jak w [WynikRundy], kazde pole jest liczba policzona z danych. Zrodlem prawdy jest
 * ksiega z zapisu: [xp], cel dnia, seria, szczebel i odznaki biora sie z niej, a nie
 * z odtwarzania rundy.
 */
data class NagrodaRundy(
    /** Punkty nauki, o ktore ksiega urosla w tej rundzie. */
    val xp: Int,

    /**
     * Rozpisanie rundy na rodzaje zaliczen: rodzaj -> ile razy, w kolejnosci stalych, bez zer.
     * To jedyne pole liczone z ODTWORZENIA prob ([Nagrody.zaliczeniaRundy]), wiec sluzy
     * wylacznie do pokazania, z czego wzial sie wynik. Patrz [xpZaliczen].
     */
    val zaliczenia: Map<Zaliczenie, Int>,

    /** Zaliczenia z pamieci na dzis, przed runda i po niej. */
    val slowaPrzed: Int,
    val slowaPo: Int,

    /** Cel dnia, do ktorego odnosza sie [slowaPrzed] i [slowaPo]. */
    val cel: Int,

    /** Cel dnia zostal zaliczony WLASNIE w tej rundzie (przed nia jeszcze nie byl). */
    val celZaliczonyTeraz: Boolean,

    /** Seria do pokazania ([Nagrody.seriaNaDzis]) przed runda i po niej. */
    val seriaPrzed: Int,
    val seriaPo: Int,

    val rangaPrzed: Ranga,
    val rangaPo: Ranga,

    /** Odznaki zdobyte w tej rundzie, w kolejnosci stalych [Odznaka]. */
    val odznaki: List<Odznaka>,

    /** Wynik gry pobil najlepszy wczesniejszy wynik w tym samym trybie. */
    val rekord: Boolean,

    /**
     * Najlepszy wczesniejszy wynik w tym trybie; null, gdy runda nie oddala wyniku albo to
     * pierwsza gra w tym trybie (pierwszy wynik nie jest rekordem, bo nie ma czego pobic).
     */
    val poprzedniRekord: Int?,
) {
    /** Runda wniosla na wyzszy szczebel; ekran ma wtedy co oglosic. */
    val awansRangi: Boolean get() = rangaPo.ordinal > rangaPrzed.ordinal

    /**
     * Punkty wynikajace z rozpisania. Rowne [xp] zawsze wtedy, gdy rozpisanie opowiada te sama
     * runde, ktora trafila do zapisu. Rozjezdza sie w dwoch znanych przypadkach: zapis ktorejs
     * odpowiedzi sie nie udal albo runda przeszla przez polnoc UTC. Ekran ma wtedy wierzyc
     * [xp] i rozpisania nie pokazywac, zamiast wyswietlac dwie sumy, ktore sie nie zgadzaja.
     */
    val xpZaliczen: Int get() = zaliczenia.entries.sumOf { it.key.xp * it.value }

    /**
     * Runda nie zostawila w dorobku nic do pokazania. Ekran ma wtedy pominac te czesc wyniku,
     * a nie wypisywac "+0": zero brzmi jak kara, a to byla po prostu runda bez nowych
     * sprawdzen (slowa juz dzis zaliczone, same pomylki, przerwany przebieg). Rozpisanie
     * celowo nie wchodzi do warunku: bez punktow w ksiedze nie ma czego rozpisywac.
     */
    val pusta: Boolean
        get() = xp == 0 && odznaki.isEmpty() && !rekord && !celZaliczonyTeraz
}

/**
 * Punkty nauki, cel dnia, seria i szczeble: czysta arytmetyka nad przejsciami, ktore policzyl
 * [Srs]. Bez Androida, bez zegara i bez zapisu; ksiege ([Dorobek]) utrwala Repository.
 *
 * DZIEN w tym pliku to zawsze dzien harmonogramu ([Srs.dayOf], doba UTC), ten sam, ktorym
 * liczy sie termin powtorki. Celowo jeden zegar: harmonogram przyjmuje jedno sprawdzenie slowa
 * na dobe, wiec doba celu dnia musi byc ta sama doba. Przy dwoch zegarach (lokalnym dla celu,
 * UTC dla harmonogramu) jeden wieczor nauki rozpadalby sie na dwa dni celu, przed lokalna
 * polnoca i po niej, a slowa zaliczonego przed polnoca nie daloby sie zaliczyc w "nowym dniu"
 * jeszcze przez godzine albo dwie. Koszt jest ten sam, co w [Srs]: doba lamie sie o 01:00
 * zima i o 02:00 latem czasu polskiego.
 *
 * Czego tu NIE ma: procentu gotowosci, prognozy wyniku i punktow za sama aktywnosc. Jest suma
 * zaliczen i kilka licznikow dni.
 */
object Nagrody {

    /** Dozwolone cele dnia, od najmniejszego. Ekran opcji ma oferowac dokladnie te. */
    val CELE_DNIA: List<Int> = listOf(5, 10, 20)

    /** Cel dnia dla nowego ucznia; rowny wartosci domyslnej Options.celDnia. */
    const val CEL_DOMYSLNY = 5

    /**
     * O ile dni wolno odejsc od poprzedniego dnia z celem, zeby seria rosla dalej. Dwa znaczy:
     * wczoraj albo przedwczoraj, czyli JEDEN opuszczony dzien jest wybaczony. Klasowka,
     * choroba i wyjazd zdarzaja sie kazdemu, a seria, ktora pada po jednym takim dniu, uczy
     * tylko tego, ze nie warto zaczynac nastepnej.
     */
    const val OKNO_SERII = 2

    /**
     * Cel dnia do uzycia. Wartosc spoza [CELE_DNIA] (plik poprawiany recznie, uszkodzony
     * zapis) jest czytana jak domyslna: zero albo liczba ujemna zaliczalyby cel bez nauki.
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
     * z pudelka 1 kaflem ABCD jest zmiana w harmonogramie, ale nie jest dowodem pamieci.
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
     * Dopisuje zaliczenie do ksiegi. [dzien] to dzien proby, [cel] to cel dnia z opcji.
     *
     * Punkty rosna zawsze. Reszta zalezy od dnia:
     *
     *  - ten sam dzien, co w ksiedze: liczniki "na dzis" rosna dalej;
     *
     *  - dzien pozniejszy: liczniki "na dzis" zaczynaja od zera. Wczorajsze nie przepadaja,
     *    bo wczorajszy cel zostal policzony w chwili, w ktorej padl;
     *
     *  - dzien WCZESNIEJSZY (zegar telefonu cofniety): dochodza same punkty i nic wiecej.
     *    Uczen odpowiedzial, wiec punktow nie tracimy, ale dnia, ktory "juz byl", nie da sie
     *    uczciwie przypisac ani do celu, ani do serii, a liczenie go jako nowego dnia
     *    pozwalaloby zaliczyc jeden dzien dwa razy.
     *
     * CEL DNIA liczy sie w chwili, w ktorej zaliczen z pamieci robi sie tyle, ile wynosi cel,
     * i tylko raz na dzien ([Dorobek.seriaDzien] pamieta, ze ten dzien juz wszedl). Dlatego
     * podniesienie celu pozniej tego samego dnia niczego nie cofa. Obnizenie celu ponizej
     * dzisiejszego licznika samo dnia nie zalicza: robi to dopiero nastepne zaliczenie, bo
     * ksiega zmienia sie wylacznie przy nauce.
     *
     * SERIA rosnie, gdy poprzedni dzien z celem byl wczoraj albo przedwczoraj ([OKNO_SERII]),
     * a po dluzszej przerwie zaczyna sie od jednego. Rekord serii i liczba dni nauki nie
     * maleja nigdy.
     */
    fun dolicz(d: Dorobek, z: Zaliczenie, dzien: Int, cel: Int): Dorobek {
        val zPunktami = d.copy(xp = d.xp + z.xp)
        if (dzien == WordProgress.NO_DAY) return zPunktami
        // NO_DAY to Int.MIN_VALUE: najpierw rownosc, dopiero potem porownanie dni.
        if (d.dzien != WordProgress.NO_DAY && dzien < d.dzien) return zPunktami

        val tenSamDzien = d.dzien == dzien
        val slowa = (if (tenSamDzien) d.slowaDzis else 0) + (if (z.zPamieci) 1 else 0)
        val poDniu = zPunktami.copy(
            dzien = dzien,
            slowaDzis = slowa,
            xpDzis = (if (tenSamDzien) d.xpDzis else 0) + z.xp,
            powtorki = d.powtorki + (if (z == Zaliczenie.POWTORKA) 1 else 0),
        )
        // coerceAtLeast: cel zerowy albo ujemny zaliczalby dzien pierwszym rozpoznaniem.
        if (slowa < cel.coerceAtLeast(1) || d.seriaDzien == dzien) return poDniu

        val ciagDalszy = d.seriaDzien != WordProgress.NO_DAY &&
            dzien.toLong() - d.seriaDzien in 1..OKNO_SERII
        val seria = if (ciagDalszy) d.seria + 1 else 1
        return poDniu.copy(
            seria = seria,
            seriaDzien = dzien,
            rekordSerii = maxOf(d.rekordSerii, seria),
            dniNauki = d.dniNauki + 1,
        )
    }

    /**
     * Seria do pokazania DZIS. [Dorobek.seria] trzyma dlugosc ostatniego lancucha takze wtedy,
     * gdy ten juz sie urwal (ksiega zmienia sie tylko przy nauce), wiec ekran nie moze czytac
     * go wprost: po przerwie dluzszej niz [OKNO_SERII] pokazujemy zero. Dopoki okno trwa,
     * seria zyje i dzisiejszy cel ja przedluzy.
     */
    fun seriaNaDzis(d: Dorobek, dzien: Int): Int {
        if (d.seriaDzien == WordProgress.NO_DAY) return 0
        return if (dzien.toLong() - d.seriaDzien <= OKNO_SERII) d.seria else 0
    }

    /** Zaliczenia z pamieci w dniu [dzien]; zero, gdy ksiega pamieta inny dzien. */
    fun slowaNaDzis(d: Dorobek, dzien: Int): Int = if (tenSam(d.dzien, dzien)) d.slowaDzis else 0

    /** Punkty nauki z dnia [dzien]; zero, gdy ksiega pamieta inny dzien. */
    fun xpNaDzis(d: Dorobek, dzien: Int): Int = if (tenSam(d.dzien, dzien)) d.xpDzis else 0

    /**
     * Czy dzien [dzien] wszedl do ksiegi jako dzien z celem. To jest odpowiedz KSIEGI, a nie
     * porownanie licznika z celem: dzien raz zaliczony zostaje zaliczony takze po podniesieniu
     * celu, a ekran ma wtedy dalej pokazywac pelny pierscien.
     */
    fun celZaliczony(d: Dorobek, dzien: Int): Boolean = tenSam(d.seriaDzien, dzien)

    private fun tenSam(zapisany: Int, dzien: Int): Boolean =
        zapisany != WordProgress.NO_DAY && zapisany == dzien

    /**
     * Stan pierscienia celu dnia: 0..4, czyli puste kolo i cztery cwiartki.
     *
     * Zero jest tylko dla zera zaliczen, a cztery tylko dla celu osiagnietego; pomiedzy nimi
     * liczy sie PELNE cwiartki, z jednym wyjatkiem: pierwsze zaliczenie zawsze zapala pierwsza
     * cwiartke. Przy celu 20 uczciwa arytmetyka zostawialaby kolo puste przez cztery slowa,
     * a puste kolo po pracy mowi "nic nie zrobiles".
     */
    fun pierscien(slowa: Int, cel: Int): Int {
        if (slowa <= 0) return 0
        val prog = cel.coerceAtLeast(1)
        if (slowa >= prog) return 4
        return (slowa.toLong() * 4 / prog).toInt().coerceIn(1, 3)
    }

    /** Szczebel dla sumy punktow nauki. Ujemna suma (uszkodzony zapis) to pierwszy szczebel. */
    fun ranga(xp: Int): Ranga =
        Ranga.entries.lastOrNull { xp >= it.prog } ?: Ranga.entries.first()

    /** Nastepny szczebel; null na samej gorze. */
    fun nastepna(xp: Int): Ranga? = Ranga.entries.firstOrNull { it.prog > xp }

    /** Ile punktow brakuje do nastepnego szczebla; zero na samej gorze. */
    fun doNastepnej(xp: Int): Int = nastepna(xp)?.let { it.prog - xp } ?: 0

    /**
     * Zaliczenia jednej rundy, po kolei: proby odtworzone przez [Srs.applyTo] od mapy postepu
     * z poczatku rundy.
     *
     * Sluzy WYLACZNIE do rozpisania wyniku na ekranie ("3 nowe slowa, 2 powtorki"). Suma w
     * zapisie zostaje zrodlem prawdy: Repository liczy kazda probe w jej wlasnym dniu i tylko
     * wtedy, gdy zapis sie udal, a tutaj cala runda dostaje jeden [dzien].
     *
     * Proba bez identyfikatora wpisu albo z kierunkiem [Direction.RANDOM] jest pomijana:
     * [Srs.key] rzucilby na niej wyjatkiem, a wyjatek na ekranie wyniku skasowalby uczniowi
     * skonczona runde (ta sama decyzja, co w [Podsumowanie.rundy]).
     */
    fun zaliczeniaRundy(
        progressPrzed: Map<String, WordProgress>,
        proby: List<Attempt>,
        dzien: Int,
    ): List<Zaliczenie> {
        var mapa = progressPrzed
        val wynik = ArrayList<Zaliczenie>()
        for (proba in proby) {
            if (proba.entryId.isBlank() || proba.direction == Direction.RANDOM) continue
            val klucz = Srs.key(proba.entryId, proba.direction)
            val przed = mapa[klucz] ?: WordProgress()
            mapa = Srs.applyTo(mapa, proba, dzien)
            zaliczenie(przed, mapa[klucz] ?: WordProgress(), proba)?.let { wynik += it }
        }
        return wynik
    }

    /**
     * Co runda dala dorobkowi: czysta roznica dwoch stanow zapisu.
     *
     * @param przed zapis z chwili WEJSCIA do rundy, sprzed pierwszej odpowiedzi.
     * @param po zapis po rundzie, czyli po Repository.recordGame, gdy runda oddala wynik
     *   (to tam stempluja sie odznaki za gre).
     * @param proby proby z tej rundy, po kolei; sluza tylko do rozpisania zaliczen.
     * @param wynik rekord oddany przez ekran gry, JUZ z wlasciwym trybem (App.kt dokleja tryb
     *   przed zapisem); null, gdy runda nie skonczyla sie wynikiem.
     * @param dzien dzien harmonogramu, do ktorego odnosza sie "dzis" i seria: ten, w ktorym
     *   runda sie SKONCZYLA. Po rundzie, ktora przeszla przez polnoc UTC, ksiega pamieta juz
     *   nowy dzien i licznik "na dzis" liczony dniem wejscia do rundy pokazalby zero.
     */
    fun roznica(
        przed: SaveData,
        po: SaveData,
        proby: List<Attempt>,
        wynik: ScoreRecord?,
        dzien: Int,
    ): NagrodaRundy {
        val odtworzone = zaliczeniaRundy(przed.progress, proby, dzien)
        val zaliczenia = LinkedHashMap<Zaliczenie, Int>()
        for (rodzaj in Zaliczenie.entries) {
            val ile = odtworzone.count { it == rodzaj }
            if (ile > 0) zaliczenia[rodzaj] = ile
        }
        // Rekord porownujemy z wynikami SPRZED rundy: w `po` nowy wynik juz lezy na liscie,
        // wiec kazda gra bilaby tam sama siebie.
        val najlepszy = wynik?.let { w ->
            przed.scores.filter { it.mode == w.mode }.maxOfOrNull { it.score }
        }
        return NagrodaRundy(
            // Ksiega nie maleje; ujemna roznica znaczylaby pomylone stany, a nie strate.
            xp = (po.dorobek.xp - przed.dorobek.xp).coerceAtLeast(0),
            zaliczenia = zaliczenia,
            slowaPrzed = slowaNaDzis(przed.dorobek, dzien),
            slowaPo = slowaNaDzis(po.dorobek, dzien),
            cel = celDnia(po.options.celDnia),
            celZaliczonyTeraz =
                !celZaliczony(przed.dorobek, dzien) && celZaliczony(po.dorobek, dzien),
            seriaPrzed = seriaNaDzis(przed.dorobek, dzien),
            seriaPo = seriaNaDzis(po.dorobek, dzien),
            rangaPrzed = ranga(przed.dorobek.xp),
            rangaPo = ranga(po.dorobek.xp),
            odznaki = Odznaka.entries.filter {
                it.name in po.dorobek.odznaki && it.name !in przed.dorobek.odznaki
            },
            rekord = wynik != null && najlepszy != null && wynik.score > najlepszy,
            poprzedniRekord = najlepszy,
        )
    }
}
