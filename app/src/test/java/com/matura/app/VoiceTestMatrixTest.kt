package com.matura.app

import com.matura.app.ui.KOD_BRAK_SILNIKA
import com.matura.app.ui.MAX_BIASING_WORDS
import com.matura.app.ui.SDK_BIASING
import com.matura.app.ui.SDK_ON_DEVICE
import com.matura.app.ui.SygnalMikrofonu
import com.matura.app.ui.VOICE_TEST_SLOWNIK_DLUGI
import com.matura.app.ui.VOICE_TEST_SLOWO
import com.matura.app.ui.VoiceKonfig
import com.matura.app.ui.VoiceProbaTyp
import com.matura.app.ui.VoiceTestTeksty
import com.matura.app.ui.VoiceWynik
import com.matura.app.ui.budujKonfiguracje
import com.matura.app.ui.dopisekMikrofon
import com.matura.app.ui.ktoraZadzialala
import com.matura.app.ui.mikrofonSlyszal
import com.matura.app.ui.nazwaBledu
import com.matura.app.ui.MAX_TEKST_RAPORTU
import com.matura.app.ui.opisWyniku
import com.matura.app.ui.przytnijTekst
import com.matura.app.ui.raportMowy
import com.matura.app.ui.sygnalMikrofonu
import com.matura.app.ui.werdyktMowy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DLACZEGO TEN TEST ISTNIEJE
 *
 * Zgloszenie z Motoroli brzmi "nadal nie dziala" i nie da sie z niego wyczytac NICZEGO: tak samo
 * wyglada silnik bez modelu polskiego, silnik odmawiajacy pracy aplikacjom trzecim i wyciszony
 * mikrofon. Ekran testu mowy ma to rozstrzygnac na urzadzeniu, do ktorego nie mamy dostepu —
 * wiec jego wynik musi byc wiarygodny bez mozliwosci sprawdzenia go recznie.
 *
 * Samego SpeechRecognizera na JVM nie ma. Testujemy to, co zostalo z niego wyciagniete: dobor
 * matrycy prob z tego, co urzadzenie o sobie mowi, oraz formatowanie raportu i werdykt — czyli
 * dokladnie te miejsca, w ktorych blad zamienilby raport w kolejne zgadywanie.
 */
class VoiceTestMatrixTest {

    private val teksty = VoiceTestTeksty(
        cisza = "cisza",
        blad = "blad",
        brakSilnika = "brak silnika",
        gotowy = "gotowosc",
        brakGotowosci = "bez gotowosci",
        czesciowe = "czesciowe",
        dziala = "DZIALA",
        zadna = "zadna konfiguracja nie zadzialala",
    )

    private fun wynik(
        nazwa: String,
        tekst: String? = null,
        blad: Int? = null,
        gotowy: Boolean = true,
        maxRms: Float = 3.5f,
        czesciowe: Boolean = false,
        czasMs: Long = 8_000L,
    ) = VoiceWynik(nazwa, gotowy, maxRms, czesciowe, tekst, blad, czasMs)

    // ---- matryca ----------------------------------------------------------

    @Test
    fun bezSilnikowMatrycaJestPusta() {
        // Telefon bez czegokolwiek: ekran ma powiedziec "nie ma czego sprawdzac",
        // a nie mielic osiem sekund na probach, ktore z gory nie maja szans.
        val m = budujKonfiguracje(sdk = 34, onDevice = false, uslugaDomyslna = false, uslugi = emptyList())
        assertTrue(m.isEmpty())
    }

    @Test
    fun ponizejApi31_niemaProbOnDevice() {
        // createOnDeviceSpeechRecognizer nie istnieje przed API 31, a minSdk to 24.
        val m = budujKonfiguracje(sdk = 30, onDevice = true, uslugaDomyslna = true, uslugi = emptyList())
        assertTrue(m.none { it.typ == VoiceProbaTyp.ON_DEVICE })
        // Zostaja dwie proby silnika domyslnego; proba z pelnym slownikiem tez wymaga API 33,
        // bo nizej EXTRA_BIASING_STRINGS nikt nie czyta.
        assertEquals(3, m.size)
        assertTrue("bez API 33 nie ma po co wysylac podpowiedzi", m.none { it.podpowiedzi })
    }

    @Test
    fun api31_jednaProbaOnDevice_bezPodpowiedzi() {
        // EXTRA_BIASING_STRINGS czyta dopiero API 33; nizej proba "z podpowiedziami" byla
        // by co do bitu tym samym zleceniem co proba bez nich — i marnowala 8 sekund.
        val m = budujKonfiguracje(sdk = SDK_ON_DEVICE, onDevice = true, uslugaDomyslna = false, uslugi = emptyList())
        assertEquals(1, m.size)
        assertEquals(VoiceProbaTyp.ON_DEVICE, m[0].typ)
        assertTrue(m[0].offline)
        assertTrue(!m[0].podpowiedzi)
    }

    @Test
    fun api33_trzyProbyOnDevice_wTymSlownikDlugosciGry() {
        val m = budujKonfiguracje(sdk = SDK_BIASING, onDevice = true, uslugaDomyslna = false, uslugi = emptyList())
        assertEquals(3, m.size)
        assertTrue("proba (a) ma slownik zestawu", m[0].podpowiedzi && !m[0].duzyBias)
        // SEDNO tej proby: gra zaczyna nasluch DOKLADNIE na tym szczeblu (VoiceInput startuje
        // z mode = ON_DEVICE i preferOffline = true) i wysyla do niego MAX_BIASING_WORDS
        // podpowiedzi. Bez tej pozycji hipoteza "dluga lista podpowiedzi dlawi silnik" byla
        // sprawdzana wylacznie na sciezce sieciowej — czyli nie tam, gdzie gra probuje najpierw.
        assertTrue("proba (b) niesie slownik dlugosci gry", m[1].podpowiedzi && m[1].duzyBias)
        assertEquals("on-device / offline / bias" + MAX_BIASING_WORDS, m[1].nazwa)
        // Stoi TUZ przed proba bez podpowiedzi: roznica miedzy sasiednimi wierszami raportu
        // jest odpowiedzia na pytanie, czy to podpowiedzi dlawia silnik.
        assertTrue("proba (c) jest bez slownika", !m[2].podpowiedzi)
        assertTrue("wszystkie prosza o offline", m.all { it.offline })
        assertTrue("i wszystkie ida droga on-device", m.all { it.typ == VoiceProbaTyp.ON_DEVICE })
    }

    @Test
    fun silnikDomyslny_offline_potemSiec_potemPelnySlownik() {
        val m = budujKonfiguracje(sdk = 34, onDevice = false, uslugaDomyslna = true, uslugi = emptyList())
        val domyslne = m.filter { it.typ == VoiceProbaTyp.DOMYSLNY }
        assertEquals(5, domyslne.size)
        assertTrue("najpierw offline", domyslne[0].offline)
        // SEDNO: tak startuje GRA na telefonie bez on-device — offline + pelny slownik zestawu.
        // Bez tej proby raport moglby uznac sciezke gry za sprawna, chociaz to ona zawodzi.
        assertTrue("druga to konfiguracja startowa gry", domyslne[1].offline && domyslne[1].duzyBias)
        assertEquals("default / offline / bias" + MAX_BIASING_WORDS, domyslne[1].nazwa)
        assertTrue("potem wolno uzyc sieci", !domyslne[2].offline)
        // SEDNO tej proby: gra wysyla do MAX_BIASING_WORDS podpowiedzi, a matryca z szescioma
        // slowami nie umiala rozstrzygnac, czy to wlasnie dluga lista dlawi silnik. Proba stoi
        // TUZ za identyczna proba bez podpowiedzi — roznica miedzy nimi jest odpowiedzia.
        assertTrue("proba porownawcza idzie bez podpowiedzi", !domyslne[2].podpowiedzi)
        // Google zaleca WEB_SEARCH dla krotkich wypowiedzi — proba stoi tuz za FREE_FORM, zeby
        // roznica miedzy sasiednimi wierszami raportu byla odpowiedzia.
        assertTrue("czwarta to ten sam silnik z modelem WEB_SEARCH", domyslne[3].webSearch && !domyslne[3].offline)
        assertTrue("reszta prob zostaje przy FREE_FORM", domyslne.count { it.webSearch } == 1)
        assertTrue("ostatnia niesie slownik dlugosci gry", domyslne[4].duzyBias && domyslne[4].podpowiedzi)
        assertTrue("i ta sama droga co porownanie (siec dozwolona)", !domyslne[4].offline)
        assertEquals("nazwa ma mowic, ile podpowiedzi poszlo",
            "default / net-ok / bias" + MAX_BIASING_WORDS, domyslne[4].nazwa)
    }

    @Test
    fun dlugiSlownik_maTyleSlowCoGra_iZawieraSlowoTestu() {
        assertEquals("inna dlugosc niz w grze nie rozstrzygalaby niczego",
            MAX_BIASING_WORDS, VOICE_TEST_SLOWNIK_DLUGI.size)
        assertEquals("bez slowa testu proba przegralaby z zupelnie innego powodu",
            VOICE_TEST_SLOWO.lowercase(), VOICE_TEST_SLOWNIK_DLUGI.first())
        assertEquals("powtorzenia skrocilyby faktyczna dlugosc listy",
            VOICE_TEST_SLOWNIK_DLUGI.size, VOICE_TEST_SLOWNIK_DLUGI.toSet().size)
    }

    @Test
    fun brakUslugiDomyslnej_pomijaProbyDomyslne() {
        // isRecognitionAvailable = false: zwiazanie z silnikiem domyslnym nie ma z czym sie udac,
        // ale konkretne uslugi z listy moga byc zywe (widziane po wylaczeniu aplikacji producenta).
        val m = budujKonfiguracje(
            sdk = 34, onDevice = false, uslugaDomyslna = false,
            uslugi = listOf("com.samsung.bixby" to "S"),
        )
        assertTrue(m.none { it.typ == VoiceProbaTyp.DOMYSLNY })
        assertEquals(1, m.size)
        assertEquals(VoiceProbaTyp.USLUGA, m[0].typ)
    }

    @Test
    fun uslugiGoogleIdaPierwsze_iBezPowtorzen() {
        val m = budujKonfiguracje(
            sdk = 34, onDevice = false, uslugaDomyslna = false,
            uslugi = listOf(
                "com.samsung.android.bixby.agent" to "A",
                "com.google.android.googlequicksearchbox" to "G",
                "com.samsung.android.bixby.agent" to "A", // ta sama usluga zglaszana dwa razy
                "com.motorola.speech" to "M",
            ),
        )
        assertEquals(3, m.size)
        assertEquals("service / com.google.android.googlequicksearchbox", m[0].nazwa)
        // Reszta zachowuje kolejnosc z systemu (sortowanie jest stabilne).
        assertEquals("com.samsung.android.bixby.agent", m[1].pakiet)
        assertEquals("com.motorola.speech", m[2].pakiet)
        // Wiazanie po ComponentName wymaga OBU czlonow — bez klasy proba byla by martwa.
        assertTrue(m.all { it.pakiet != null && it.klasa != null })
        assertTrue("uslugi testujemy bez offline i bez slownika", m.none { it.offline || it.podpowiedzi })
    }

    @Test
    fun pelnaMatryca_maWszystkiePiecSzczebli() {
        val m = budujKonfiguracje(
            sdk = 34, onDevice = true, uslugaDomyslna = true,
            uslugi = listOf("com.google.android.googlequicksearchbox" to "G", "com.motorola.speech" to "M"),
        )
        assertEquals(
            listOf(
                "on-device / offline / bias",
                "on-device / offline / bias" + MAX_BIASING_WORDS,
                "on-device / offline",
                "default / offline",
                "default / offline / bias" + MAX_BIASING_WORDS,
                "default / net-ok",
                "default / net-ok / web-search",
                "default / net-ok / bias" + MAX_BIASING_WORDS,
                "service / com.google.android.googlequicksearchbox",
                "service / com.motorola.speech",
            ),
            m.map { it.nazwa },
        )
        assertEquals("nazwy prob musza byc unikalne — inaczej raport klamie", m.size, m.map { it.nazwa }.toSet().size)
    }

    // ---- opis proby -------------------------------------------------------

    @Test
    fun opisProby_rozroznia_tekst_blad_cisze() {
        val dziala = opisWyniku(wynik("default / net-ok", tekst = "kolano", czesciowe = true), teksty)
        assertTrue(dziala.contains("kolano"))
        assertTrue(dziala.contains("DZIALA"))
        assertTrue(dziala.contains("czesciowe"))

        // Kod bledu ZOSTAJE w opisie razem z nazwa: "7" samo w sobie nic nie mowi,
        // a bez numeru nie da sie tego znalezc w dokumentacji.
        val blad = opisWyniku(wynik("on-device / offline", blad = 7), teksty)
        assertTrue(blad.contains("blad"))
        assertTrue(blad.contains("7 NO_MATCH"))

        // Zgloszenie z Motoroli w czystej postaci: zero bledow i zero wynikow.
        val cisza = opisWyniku(wynik("default / offline", blad = null), teksty)
        assertTrue(cisza.contains("cisza"))
    }

    @Test
    fun opisProby_mowiCzyByloOnReadyForSpeech() {
        assertTrue(opisWyniku(wynik("x", gotowy = true), teksty).contains("gotowosc"))
        assertTrue(opisWyniku(wynik("x", gotowy = false), teksty).contains("bez gotowosci"))
    }

    @Test
    fun brakProbekRms_niePokazujeLiczby() {
        // Silnik, ktory w ogole nie raportuje poziomu, nie moze wygladac jak gluchy mikrofon.
        val bez = opisWyniku(wynik("x", maxRms = Float.NEGATIVE_INFINITY), teksty)
        assertTrue(bez.contains("RMS max —"))
    }

    @Test
    fun nieudaneUtworzenieRozpoznawacza_niePokazujeSieJakoBladSilnika() {
        val w = opisWyniku(wynik("service / com.x", blad = KOD_BRAK_SILNIKA), teksty)
        assertTrue(w.contains("brak silnika"))
        assertTrue("ujemny kod nie ma trafic do opisu jako numer bledu", !w.contains("-1"))
    }

    @Test
    fun nazwyBledow_pokrywajaTeIstotneDlaTegoZgloszenia() {
        assertEquals("7 NO_MATCH", nazwaBledu(7))
        assertEquals("12 LANGUAGE_NOT_SUPPORTED", nazwaBledu(12))
        assertEquals("13 LANGUAGE_UNAVAILABLE", nazwaBledu(13))
        assertEquals("9 INSUFFICIENT_PERMISSIONS", nazwaBledu(9))
        assertEquals("77", nazwaBledu(77)) // nieznany kod zostaje liczba, nie znika
    }

    // ---- werdykt i raport -------------------------------------------------

    @Test
    fun werdykt_wskazujePIERWSZAdzialajacaKonfiguracje() {
        val wyniki = listOf(
            wynik("on-device / offline / bias", blad = 7),
            wynik("on-device / offline", blad = 7),
            wynik("default / offline", blad = 5),
            wynik("default / net-ok", tekst = "kolano"),
            wynik("service / com.google.android.googlequicksearchbox", tekst = "kolano"),
        )
        assertEquals("default / net-ok", ktoraZadzialala(wyniki)?.nazwa)
        assertEquals(
            "DZIALA: default / net-ok",
            werdyktMowy(wyniki, "DZIALA: ", teksty.zadna),
        )
    }

    @Test
    fun werdykt_gdyZadnaProbaNicNieRozpoznala() {
        val wyniki = listOf(
            wynik("on-device / offline", blad = 7),
            wynik("default / net-ok", blad = null),
            wynik("service / com.x", tekst = "   "), // sam bialy znak to nie jest rozpoznanie
        )
        assertNull(ktoraZadzialala(wyniki))
        assertEquals(teksty.zadna, werdyktMowy(wyniki, "DZIALA: ", teksty.zadna))
    }

    @Test
    fun werdykt_przedPierwszymPrzebiegiem_niczegoNieObiecuje() {
        assertNull(ktoraZadzialala(emptyList()))
        assertEquals(teksty.zadna, werdyktMowy(emptyList(), "DZIALA: ", teksty.zadna))
    }

    @Test
    fun gluchyMikrofonOdrozniaSieOdBrakuModeluJezyka() {
        // Oba przypadki wygladaja tak samo w wynikach (nic nie rozpoznano) i wymagaja zupelnie
        // innej naprawy — rozstrzyga je wylacznie poziom sygnalu z mikrofonu.
        val gluchy = listOf(
            wynik("on-device / offline", blad = 7, maxRms = -2.1f),
            wynik("default / net-ok", blad = 7, maxRms = -2.12f),
        )
        val slyszacy = listOf(
            wynik("on-device / offline", blad = 7, maxRms = -2.1f),
            wynik("default / net-ok", blad = 7, maxRms = 4.7f),
        )
        assertTrue(!mikrofonSlyszal(gluchy))
        assertTrue(mikrofonSlyszal(slyszacy))
        assertEquals(SygnalMikrofonu.DNO, sygnalMikrofonu(gluchy))
        assertEquals(SygnalMikrofonu.SLYSZAL, sygnalMikrofonu(slyszacy))
        assertEquals("sprawdz mikrofon", dopisekMikrofon(gluchy, "brak modelu", "sprawdz mikrofon", "nie wiadomo"))
        assertEquals("brak modelu", dopisekMikrofon(slyszacy, "brak modelu", "sprawdz mikrofon", "nie wiadomo"))
    }

    /**
     * SEDNO. Wywolanie onRmsChanged NIE jest obowiazkowe i czesc uslug wiazanych po ComponentName
     * go nie implementuje. Przy dwoch stanach taki przebieg trafial do worka "mikrofon milczal",
     * a raport z pelnym przekonaniem wysylal uzytkownika do przelacznika prywatnosci — czyli tam,
     * gdzie nie ma nic do naprawienia. Raport, ktory ma zastapic zgadywanie, nie moze twierdzic
     * rzeczy, ktorych nie zmierzyl.
     */
    @Test
    fun brakPomiaruRms_toTRZECI_stan_aNieGluchyMikrofon() {
        val brak = listOf(wynik("service / com.x", blad = 7, maxRms = Float.NEGATIVE_INFINITY))
        assertEquals(SygnalMikrofonu.NIEZMIERZONY, sygnalMikrofonu(brak))
        assertTrue("brak pomiaru nie moze udawac dzialajacego mikrofonu", !mikrofonSlyszal(brak))
        assertEquals(
            "ani wysylac do przelacznika prywatnosci",
            "nie wiadomo",
            dopisekMikrofon(brak, "brak modelu", "sprawdz mikrofon", "nie wiadomo"),
        )
    }

    /** Kazdy z trzech stanow ma byc osiagalny i dawac WLASNY dopisek. */
    @Test
    fun trzyStanySygnaluMikrofonu() {
        val slyszal = listOf(
            wynik("a", blad = 7, maxRms = Float.NEGATIVE_INFINITY),
            wynik("b", blad = 7, maxRms = 4.7f),
        )
        val dno = listOf(
            wynik("a", blad = 7, maxRms = Float.NEGATIVE_INFINITY),
            wynik("b", blad = 7, maxRms = -2.1f),
        )
        val brak = listOf(wynik("a", blad = 7, maxRms = Float.NEGATIVE_INFINITY))
        assertEquals(SygnalMikrofonu.SLYSZAL, sygnalMikrofonu(slyszal))
        assertEquals(SygnalMikrofonu.DNO, sygnalMikrofonu(dno))
        assertEquals(SygnalMikrofonu.NIEZMIERZONY, sygnalMikrofonu(brak))
        // Przebieg bez prob (test przerwany od razu) tez niczego nie zmierzyl.
        assertEquals(SygnalMikrofonu.NIEZMIERZONY, sygnalMikrofonu(emptyList()))
        val dopiski = listOf(slyszal, dno, brak).map { dopisekMikrofon(it, "A", "B", "C") }
        assertEquals(listOf("A", "B", "C"), dopiski)
    }

    @Test
    fun raport_maNaglowek_wszystkieProbyPoKoleiIWerdyktNaKoncu() {
        val wyniki = listOf(
            wynik("on-device / offline", blad = 7),
            wynik("default / net-ok", tekst = "kolano"),
        )
        val werdykt = werdyktMowy(wyniki, "DZIALA: ", teksty.zadna)
        val r = raportMowy("Android 34 · motorola moto g54\n", wyniki, werdykt, teksty)

        assertTrue("naglowek o urzadzeniu jest pierwszy", r.startsWith("Android 34"))
        assertTrue("proby sa ponumerowane", r.contains("1. on-device / offline -> "))
        assertTrue(r.contains("2. default / net-ok -> "))
        assertTrue("kod bledu zostaje w raporcie", r.contains("7 NO_MATCH"))
        assertTrue("werdykt konczy raport", r.trimEnd().endsWith("DZIALA: default / net-ok"))
        // Raport ma byc do wklejenia w rozmowie: kazda proba w swojej linii, nic sie nie skleja.
        assertEquals(2, r.lines().count { it.startsWith("1. ") || it.startsWith("2. ") })
    }

    @Test
    fun raportBezProb_dalejJestCzytelny() {
        // Uzytkownik moze przerwac test od razu; pusty raport nie moze byc wywrotka.
        val r = raportMowy("Android 34\n", emptyList(), teksty.zadna, teksty)
        assertTrue(r.startsWith("Android 34"))
        assertTrue(r.trimEnd().endsWith(teksty.zadna))
    }

    // ---- przebieg PRZERWANY ------------------------------------------------
    //
    // Przebieg trwa okolo minuty, przez ktora uczen ma tylko mowic. Przerwanie nie jest wiec
    // przypadkiem brzegowym: wystarczy zgaszony ekran, przycisk PRZERWIJ albo przelaczenie sie
    // do innej aplikacji. Raport z trzech prob nie ma prawa konczyc sie tym samym zdaniem co
    // raport z osmiu — to nie brak odpowiedzi, tylko pewna siebie zla odpowiedz, ktora wysyla
    // uzytkownika do pakietow jezyka albo do przelacznika prywatnosci na podstawie niczego.

    @Test
    fun werdyktPrzerwanegoPrzebiegu_nieOrzekaOTymTelefonie() {
        val wyniki = listOf(
            wynik("on-device / offline / bias", blad = 7),
            wynik("on-device / offline", blad = 7),
            wynik("default / offline", blad = 7),
        )
        val werdykt = werdyktMowy(
            wyniki, "DZIALA: ", teksty.zadna,
            pelny = false, przerwany = "PRZERWANY po 3 z 8 prob",
        )
        assertNotEquals("niepelny przebieg nie moze wydac werdyktu o telefonie", teksty.zadna, werdykt)
        assertEquals("PRZERWANY po 3 z 8 prob", werdykt)
    }

    /** Przerwanie przed pierwszym zapisem: to samo zdanie padalo po ZERU prob. */
    @Test
    fun werdyktPrzerwanegoPrzebiegu_bezAniJednejProby_tezNieOrzeka() {
        val werdykt = werdyktMowy(
            emptyList(), "DZIALA: ", teksty.zadna,
            pelny = false, przerwany = "PRZERWANY po 0 z 8 prob",
        )
        assertNotEquals(teksty.zadna, werdykt)
        assertEquals("PRZERWANY po 0 z 8 prob", werdykt)
    }

    /**
     * Odwrotna strona tej samej zasady: dzialajaca konfiguracja jest FAKTEM ZMIERZONYM, wiec
     * zostaje odpowiedzia takze w przebiegu przerwanym. Zdanie "zadna nie zadzialala" jest
     * wnioskiem z braku dowodow i tylko ono wymaga pelnego przebiegu.
     */
    @Test
    fun dzialajacaKonfiguracja_jestOdpowiedziaTakzeWprzebieguPrzerwanym() {
        val wyniki = listOf(
            wynik("on-device / offline", blad = 7),
            wynik("default / net-ok", tekst = "kolano"),
        )
        assertEquals(
            "DZIALA: default / net-ok",
            werdyktMowy(wyniki, "DZIALA: ", teksty.zadna, pelny = false, przerwany = "PRZERWANY"),
        )
    }

    /** Domyslnie werdykt zachowuje sie jak dotad — pelny przebieg orzeka. */
    @Test
    fun pelnyPrzebiegBezRozpoznania_dalejOrzeka() {
        val wyniki = listOf(wynik("default / net-ok", blad = 7))
        assertEquals(teksty.zadna, werdyktMowy(wyniki, "DZIALA: ", teksty.zadna, pelny = true, przerwany = "PRZERWANY"))
    }

    /** Wklejony raport czyta ktos, kto nie widzial ekranu — musi bronic sie sam. */
    @Test
    fun raportPrzerwanegoPrzebiegu_niesieLicznikProbISlowoPRZERWANY() {
        val wyniki = listOf(wynik("on-device / offline", blad = 7))
        val r = raportMowy(
            "Android 34 · motorola moto g54\n", wyniki, "PRZERWANY po 1 z 8 prob", teksty,
            ile = 8, przerwany = true,
        )
        assertTrue("licznik prob zaraz za naglowkiem", r.contains("próby: 1/8"))
        assertTrue("i slowo, ktorego nie da sie przeoczyc", r.contains("PRZERWANY"))
        assertTrue("naglowek o urzadzeniu nadal pierwszy", r.startsWith("Android 34"))
    }

    @Test
    fun raportPelnegoPrzebiegu_maLicznikBezSlowaPRZERWANY() {
        val wyniki = listOf(wynik("a", blad = 7), wynik("b", tekst = "kolano"))
        val r = raportMowy("Android 34\n", wyniki, "DZIALA: b", teksty, ile = 2, przerwany = false)
        assertTrue(r.contains("próby: 2/2"))
        assertTrue("pelny przebieg nie moze wygladac na przerwany", !r.contains("PRZERWANY"))
    }

    /**
     * Raport wklejany do rozmowy niesie to, co silnik USLYSZAL — przy blednym rozpoznaniu moze
     * to byc dowolna wypowiedz z otoczenia, a nie zamowione slowo. Do oceny proby wystarczy
     * kilkanascie znakow, wiec reszty nie przenosimy.
     */
    @Test
    fun rozpoznanyTekstWraporcieJestPrzyciety() {
        val dlugi = "kolano a przy okazji moj numer pesel to"
        assertEquals("krotkie zostaje bez zmian", "kolano", przytnijTekst("kolano"))
        assertTrue("dlugie sie konczy", przytnijTekst(dlugi).length <= MAX_TEKST_RAPORTU + 1)
        assertTrue("i widac, ze to nie calosc", przytnijTekst(dlugi).endsWith("…"))
        val opis = opisWyniku(wynik("default / net-ok", tekst = dlugi), teksty)
        assertTrue("proba dalej jest opisana jako dzialajaca", opis.contains("DZIALA"))
        assertTrue("ale bez calej wypowiedzi", !opis.contains("pesel"))
    }

    @Test
    fun konfiguracjaOpisujeSieSamaWRaporcie() {
        // Nazwa proby jest jedyna rzecza, po ktorej mozna odtworzyc ustawienie na telefonie —
        // musi jednoznacznie mowic: skad silnik, czy offline, czy ze slownikiem.
        val k = VoiceKonfig("on-device / offline / bias", VoiceProbaTyp.ON_DEVICE, offline = true, podpowiedzi = true)
        assertTrue(k.nazwa.contains("on-device"))
        assertTrue(k.nazwa.contains("offline"))
        assertTrue(k.nazwa.contains("bias"))
    }
}

/**
 * WERDYKT NIE MOZE WSKAZYWAC ZEPSUTEGO SILNIKA.
 *
 * Raport z motoroli edge 50 neo (11 prob) skonczyl sie zdaniem "Dziala konfiguracja:
 * on-device / offline / bias", chociaz ta proba oddala urwane "ko" i ani jednej probki poziomu,
 * a nizej lezaly trzy konfiguracje z pelnym "kolano" i zmierzonym dzwiekiem. Wybor "pierwsza
 * z brzegu" wysylal wiec uzytkownika prosto w jedyna martwa usluge na tym telefonie.
 */
class WerdyktWyboruTest {

    private fun w(nazwa: String, tekst: String?, rms: Float = Float.NEGATIVE_INFINITY, blad: Int? = null) =
        com.matura.app.ui.VoiceWynik(
            nazwa = nazwa, gotowy = true, maxRms = rms, czesciowe = tekst != null,
            tekst = tekst, blad = blad, czasMs = 8_000,
        )

    @Test
    fun pelneSlowoZeZmierzonymDzwiekiem_bijeUrwanyFragmentBezDzwieku() {
        val wyniki = listOf(
            w("on-device / offline / bias", "ko"),                    // pierwsza, ale martwy silnik
            w("default / net-ok", "kolano", rms = 10f),               // ta jest odpowiedzia
            w("service / com.google.android.tts", "kolano", rms = 10f),
        )
        assertEquals("default / net-ok", com.matura.app.ui.ktoraZadzialala(wyniki)?.nazwa)
    }

    @Test
    fun zadnegoTekstu_toBrakOdpowiedzi() {
        val wyniki = listOf(w("on-device / offline", null), w("service / as", null, blad = 7))
        assertNull(com.matura.app.ui.ktoraZadzialala(wyniki))
    }

    @Test
    fun przyRownymTekscie_decydujeZmierzonyDzwiek() {
        val wyniki = listOf(w("a", "kolano"), w("b", "kolano", rms = 3f))
        assertEquals("b", com.matura.app.ui.ktoraZadzialala(wyniki)?.nazwa)
    }

    @Test
    fun cudzeSlowo_przegrywaZZamowionym() {
        val wyniki = listOf(w("a", "telefon", rms = 10f), w("b", "kolano", rms = 10f))
        assertEquals("b", com.matura.app.ui.ktoraZadzialala(wyniki)?.nazwa)
    }
}
