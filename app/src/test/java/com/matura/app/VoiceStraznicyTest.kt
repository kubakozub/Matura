package com.matura.app

import com.matura.app.ui.APPOPS_MODE_IGNORED
import com.matura.app.ui.PAKIET_MOWY_GOOGLE
import com.matura.app.ui.PowodStop
import com.matura.app.ui.StanMikrofonu
import com.matura.app.ui.VOICE_DZWIEK_MS
import com.matura.app.ui.VOICE_MODEL_MS
import com.matura.app.ui.VOICE_WATCHDOG_MS
import com.matura.app.ui.VoiceLog
import com.matura.app.ui.indeksPakietu
import com.matura.app.ui.limitOknaStartu
import com.matura.app.ui.nastepnyIndeksSilnika
import com.matura.app.ui.opisMikrofonu
import com.matura.app.ui.opisStopu
import com.matura.app.ui.przelacznikPrywatnosciZAppOps
import com.matura.app.ui.straznikAktualny
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DLACZEGO TEN TEST ISTNIEJE
 *
 * Diagnostyka z motoroli edge 50 neo (Android 16): kazda usluga rozpoznawania melduje gotowosc
 * po 0,2 s i milczy — zero probek poziomu, zero wynikow, zero bledow. Wszystkie poprawki z 0.9.17
 * sa wariacjami jednego zdania: kazde czekanie ma miec limit, a kazdy straznik ma wiedziec, czyjej
 * sesji pilnuje. Tego nie da sie sprawdzic przez Handler bez urzadzenia, wiec decyzje siedza
 * w czystych funkcjach [com.matura.app.ui] (ten sam wzorzec co eskalowacPoPustym) — i tu sa
 * przypiete testem.
 */
class VoiceStraznicyTest {

    // ---- (1) OKNO STARTU MA LIMIT ---------------------------------------------------------

    /**
     * SEDNO USTERKI 1 i 2. start() w galezi on-device pytal o model i czekal — a limit sondy lecial
     * wspolnym handlerem, ktory kasuje stop() i kazdy scheduleRestart. Gdy sonda nie odpowiadala,
     * nie bylo juz ZADNEGO limitu: nasluch nie startowal nigdy, a pasek zostawal na "uruchamiam
     * mikrofon…". Okno startu musi wiec miec wlasny limit i musi on pomiescic OBA etapy.
     */
    @Test
    fun oknoStartu_zeSprawdzaniemModelu_miesciSondeIWiazanie() {
        assertEquals(
            "limit okna startu = czas sondy + czas na zwiazanie silnika",
            VOICE_MODEL_MS + VOICE_WATCHDOG_MS,
            limitOknaStartu(sprawdzamModel = true),
        )
        assertTrue(
            "limit krotszy niz sama sonda ucinalby odpowiedz, ktora wlasnie ma przyjsc",
            limitOknaStartu(sprawdzamModel = true) > VOICE_MODEL_MS,
        )
    }

    /** Bez pytania o model okno jest krotsze — ale nie ma go nie byc. */
    @Test
    fun oknoStartu_bezSprawdzaniaModelu_toSamWatchdog() {
        assertEquals(VOICE_WATCHDOG_MS, limitOknaStartu(sprawdzamModel = false))
        assertTrue("kazda droga startu ma jakis limit", limitOknaStartu(sprawdzamModel = false) > 0L)
    }

    /**
     * USTERKA 4. Straznik dzwieku stal na 2500 ms, a uzytkownik przerwal probe po 2,1 s — czyli
     * 0,6 s przed jedynym straznikiem, ktory mial te awarie zlapac. Straznik, ktorego nikt nie
     * doczeka, nie istnieje.
     */
    @Test
    fun straznikDzwieku_odpowiadaZanimUzytkownikPrzerwie() {
        assertTrue(
            "2,1 s to zmierzony czas cierpliwosci ze zgloszenia — straznik ma zdazyc przed nim",
            VOICE_DZWIEK_MS < 2_100L,
        )
        assertTrue("ale nie tak szybko, zeby ucinac zimny start silnika", VOICE_DZWIEK_MS >= 1_000L)
        assertTrue("dzwieku pilnujemy wczesniej niz pierwszego zdarzenia", VOICE_DZWIEK_MS < VOICE_WATCHDOG_MS)
    }

    // ---- (2) STRAZNIK NIE DEGRADUJE CUDZEJ SESJI ------------------------------------------

    /**
     * SEDNO USTERKI 3. start() wolno wolac na zywej sesji (drugie dotkniecie paska, rekompozycja):
     * podbija wtedy pokolenie, ale NIE czysci kolejki handlera. Straznik uzbrojony przez poprzednia
     * sesje budzil sie w nowej i zabieral szczebel silnikowi, ktory nie mial jeszcze kiedy zawiesc.
     */
    @Test
    fun straznikZPoprzedniegoPokolenia_niePilnujeNowejSesji() {
        assertTrue("straznik swojej sesji ma prawo glosu", straznikAktualny(7, 7, chceNasluchu = true))
        assertFalse(
            "straznik uzbrojony w pokoleniu N nie degraduje silnika w pokoleniu N+1",
            straznikAktualny(7, 8, chceNasluchu = true),
        )
    }

    /** Po stop() nie ma czego pilnowac — a stop() tez podbija pokolenie. */
    @Test
    fun poWylaczeniuNasluchu_zadenStraznikNieDziala() {
        assertFalse(straznikAktualny(7, 7, chceNasluchu = false))
        assertFalse(straznikAktualny(7, 8, chceNasluchu = false))
    }

    // ---- (3) CZARNA LISTA PAKIETOW --------------------------------------------------------

    private val trzyUslugi = listOf("com.google.android.as", PAKIET_MOWY_GOOGLE, "com.anthropic.claude")

    /** Bez czarnej listy drabinka wracala do uslugi, o ktorej juz wiadomo, ze nie odpowiada. */
    @Test
    fun czarnaLista_pomijaPakietKtoryJuzZawiodl() {
        assertEquals(
            "z silnika domyslnego (-1) idziemy na pierwsza usluge",
            0,
            nastepnyIndeksSilnika(trzyUslugi, biezacy = -1, czarnaLista = emptySet()),
        )
        assertEquals(
            "pakiet skreslony przeskakujemy w calosci",
            1,
            nastepnyIndeksSilnika(trzyUslugi, biezacy = -1, czarnaLista = setOf("com.google.android.as")),
        )
        assertEquals(
            "skreslenia lacza sie — zostaje ostatnia usluga",
            2,
            nastepnyIndeksSilnika(
                trzyUslugi,
                biezacy = -1,
                czarnaLista = setOf("com.google.android.as", PAKIET_MOWY_GOOGLE),
            ),
        )
    }

    /** Koniec listy to koniec drabinki — i ma byc rozpoznawalny, a nie zapetlony. */
    @Test
    fun czarnaLista_gdySkreslonoWszystko_drabinkaSieKonczy() {
        assertEquals(-1, nastepnyIndeksSilnika(trzyUslugi, biezacy = 2, czarnaLista = emptySet()))
        assertEquals(-1, nastepnyIndeksSilnika(trzyUslugi, biezacy = -1, czarnaLista = trzyUslugi.toSet()))
        assertEquals("pusta lista uslug to tez koniec", -1, nastepnyIndeksSilnika(emptyList(), -1, emptySet()))
    }

    /**
     * USTERKA 9. Trybu on-device nie da sie przeadresowac (createOnDeviceSpeechRecognizer ignoruje
     * ComponentName), wiec do silnika Google prowadzi wylacznie jawne wiazanie po nazwie pakietu —
     * i ono tez musi respektowac czarna liste, inaczej skreslony pakiet wracalby tylna furtka.
     */
    @Test
    fun jawnySzczebelGoogle_szukaPakietuAleSzanujeCzarnaListe() {
        assertEquals(1, indeksPakietu(trzyUslugi, PAKIET_MOWY_GOOGLE, emptySet()))
        assertEquals(
            "skreslony pakiet nie wraca jawnym szczeblem",
            -1,
            indeksPakietu(trzyUslugi, PAKIET_MOWY_GOOGLE, setOf(PAKIET_MOWY_GOOGLE)),
        )
        assertEquals(
            "telefon bez uslugi Google po prostu nie ma tego szczebla",
            -1,
            indeksPakietu(listOf("com.anthropic.claude"), PAKIET_MOWY_GOOGLE, emptySet()),
        )
    }

    // ---- (4) TRZYCZESCIOWA KONTROLA WYCISZENIA --------------------------------------------

    /**
     * SEDNO USTERKI 8. Przelacznik prywatnosci mikrofonu (Android 12+) jest w AOSP globalnym
     * ograniczeniem AppOps na OP_RECORD_AUDIO, wiec unsafeCheckOpNoThrow odpowiada MODE_IGNORED.
     * MODE_ERRORED to co innego (odmowa uprawnienia) i nie wolno tego mylic, bo od zgody jest
     * osobne pytanie.
     */
    @Test
    fun appOps_tylkoMODE_IGNORED_znaczyPrzelacznik() {
        assertEquals(true, przelacznikPrywatnosciZAppOps(APPOPS_MODE_IGNORED))
        assertEquals("MODE_ALLOWED", false, przelacznikPrywatnosciZAppOps(0))
        assertEquals("MODE_ERRORED to odmowa uprawnienia, nie przelacznik", false, przelacznikPrywatnosciZAppOps(2))
        assertEquals("MODE_DEFAULT", false, przelacznikPrywatnosciZAppOps(3))
    }

    /** API 24-28 nie ma unsafeCheckOpNoThrow: odpowiedz brzmi "nie wiadomo", nigdy "nie". */
    @Test
    fun appOps_ponizejApi29_toNieWiadomo_aNieNie() {
        assertNull(przelacznikPrywatnosciZAppOps(null))
        val stan = StanMikrofonu(wyciszonyProgramowo = false, przelacznikPrywatnosci = null, zgoda = true)
        assertFalse("brak odpowiedzi nie moze sam z siebie oskarzac mikrofonu", stan.gluchy)
        assertTrue(opisMikrofonu(stan).contains("nie wiadomo"))
    }

    /** Kazda z osmiu kombinacji ma dac dokladnie to, co nazywa — i kazda ma byc widoczna w opisie. */
    @Test
    fun trzyCzesciKontroli_kazdaKombinacja() {
        for (mute in listOf(false, true)) {
            for (toggle in listOf(false, true)) {
                for (zgoda in listOf(false, true)) {
                    val stan = StanMikrofonu(mute, toggle, zgoda)
                    assertEquals("gluchy = wyciszenie albo przelacznik", mute || toggle, stan.gluchy)
                    assertEquals("blokada dolicza brak zgody", mute || toggle || !zgoda, stan.blokada)
                    val opis = opisMikrofonu(stan)
                    assertTrue("wyciszenie ma byc w opisie: " + opis, opis.contains(if (mute) "wyciszony: TAK" else "wyciszony: nie"))
                    assertTrue("przelacznik ma byc w opisie: " + opis, opis.contains(if (toggle) "BLOKUJE" else "prywatności: nie"))
                    assertTrue("zgoda ma byc w opisie: " + opis, opis.contains(if (zgoda) "zgoda: tak" else "zgoda: NIE"))
                }
            }
        }
    }

    /**
     * KONTROLA UJEMNA dla usterki 8: sam AudioManager.isMicrophoneMute odpowiadal "nie" takze
     * wtedy, gdy mikrofon byl sciety przelacznikiem prywatnosci. Tu ten przypadek MA byc widoczny.
     */
    @Test
    fun przelacznikPrywatnosci_widacGoNawetGdyIsMicrophoneMuteMowiNie() {
        val stan = StanMikrofonu(wyciszonyProgramowo = false, przelacznikPrywatnosci = true, zgoda = true)
        assertTrue("to jest dokladnie ten przypadek, ktorego stara kontrola nie widziala", stan.gluchy)
        assertTrue(opisMikrofonu(stan).contains("wyciszony: nie"))
        assertTrue(opisMikrofonu(stan).contains("BLOKUJE"))
    }

    // ---- DZIENNIK: CIAGLOSC I POWODY ------------------------------------------------------

    /**
     * USTERKA 6. start() wolal dziennik.reset(), czyli kasowal historie proby, ktora wlasnie
     * zawiodla — a ponowne dotkniecie paska po nieudanej probie jest odruchem. Uczen otwieral
     * wiec diagnostyke juz po skasowaniu tego, po co ja otwieral.
     */
    @Test
    fun nowaSesja_zostawiaHistoriePoprzedniejProby() {
        val log = VoiceLog()
        log.dodaj(1_000L, "start (pl-PL)")
        log.dodaj(1_400L, "gotowy")
        log.nowaSesja(5_000L)
        log.dodaj(5_200L, "start (pl-PL)")

        val tekst = log.tekst()
        assertTrue("historia poprzedniej proby zostaje", tekst.contains("gotowy"))
        assertTrue("granica miedzy probami jest widoczna", tekst.contains(VoiceLog.SEPARATOR_SESJI))
        assertTrue(
            "czas nowej proby liczy sie od jej startu, nie od wlaczenia ekranu",
            tekst.trim().endsWith("0.2s start (pl-PL)"),
        )
    }

    /** Pusty dziennik nie dostaje separatora — pierwsza proba nie ma czego oddzielac. */
    @Test
    fun nowaSesja_naPustymDzienniku_nieDodajeSeparatora() {
        val log = VoiceLog()
        log.nowaSesja(1_000L)
        assertEquals("—", log.tekst())
    }

    /**
     * USTERKA 6, druga polowa: dziennik ciagly musi pomiescic wiecej niz jedna probe, inaczej
     * nieudana historia wypada gora, zanim ktokolwiek przytrzyma pasek.
     */
    @Test
    fun dziennikCiagly_maZapasNaDwieProby() {
        assertTrue("14 wpisow starczalo tylko dziennikowi kasowanemu co start", VoiceLog.LIMIT_WPISOW >= 30)
        val log = VoiceLog(limit = 3)
        log.dodaj(0L, "a")
        log.dodaj(0L, "b")
        log.dodaj(0L, "c")
        log.dodaj(0L, "d")
        assertFalse("najstarsze wpisy wypadaja, nowe zostaja", log.tekst().contains(" a"))
        assertTrue(log.tekst().contains(" d"))
    }

    /**
     * USTERKA 5. W dzienniku zostawal sam napis "stop", wiec pauza, tlo, dotkniecie paska i koniec
     * drabinki wygladaly w raporcie z telefonu identycznie — a to cztery rozne historie, z ktorych
     * tylko jedna jest awaria.
     */
    @Test
    fun powodStopu_kazdyMaWlasnyWpis() {
        assertEquals("stop (pauza)", opisStopu(PowodStop.PAUZA))
        assertEquals("stop (tło)", opisStopu(PowodStop.TLO))
        assertEquals("stop (dotknięcie)", opisStopu(PowodStop.DOTKNIECIE))
        assertEquals("stop (drabinka)", opisStopu(PowodStop.DRABINKA))
        assertEquals("wywolanie bez powodu zostaje jak dotad", "stop", opisStopu(PowodStop.NIEZNANY))
        assertEquals(
            "kazdy powod ma dac INNY wpis — inaczej dziennik dalej nie rozroznia",
            PowodStop.entries.size,
            PowodStop.entries.map { opisStopu(it) }.toSet().size,
        )
    }
}
