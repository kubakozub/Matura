package com.matura.app

import com.matura.app.data.BuiltInSets
import com.matura.app.ui.VoskGrammar
import com.matura.app.ui.VoskVoiceInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cala logika drogi offline, ktora da sie sprawdzic bez mikrofonu i bez modelu.
 *
 * Kazdy przypadek tutaj odpowiada jednej awarii, ktora ta droga moglaby miec po cichu:
 * slowo, ktorego nie da sie wypowiedziec, bo weszlo do gramatyki razem z przecinkiem;
 * chrzakniecie zaliczone jako odpowiedz, bo w gramatyce zabraklo [unk]; falszywe trafienie
 * przepuszczone, bo bramka pewnosci liczyla sie z innego mianownika.
 */
class VoskGrammarTest {

    // ---- normalizacja ----------------------------------------------------------------

    @Test
    fun `lacznik i apostrof staja sie spacja`() {
        // To jedna zmiana, ktora w pomiarze podniosla angielski z 96,7% do 100%: leksykon
        // modelu nie ma "open-minded" jako jednego tokenu, ma "open" i "minded".
        assertEquals("open minded", VoskGrammar.normalizeForGrammar("open-minded"))
        assertEquals("butcher s", VoskGrammar.normalizeForGrammar("butcher's"))
        assertEquals("can t stand", VoskGrammar.normalizeForGrammar("can't stand"))
        // Apostrof z klawiatury telefonu (U+2019) i myslnik typograficzny (U+2013) tez.
        assertEquals("butcher s", VoskGrammar.normalizeForGrammar("butcher’s"))
        assertEquals("open minded", VoskGrammar.normalizeForGrammar("open–minded"))
    }

    @Test
    fun `znaki diakrytyczne zostaja nietkniete`() {
        // Odwrotnie niz w Matching.normalize: leksykon polskiego modelu ma "łóżko", a nie
        // "lozko". Zdjecie ogonkow wywalilo by te slowa z gramatyki i staly by sie nieme.
        assertEquals("łóżko", VoskGrammar.normalizeForGrammar("łóżko"))
        assertEquals("ścielić łóżka", VoskGrammar.normalizeForGrammar("Ścielić łóżka"))
    }

    @Test
    fun `interpunkcja i nadmiarowe spacje znikaja`() {
        assertEquals("dzien dobry", VoskGrammar.normalizeForGrammar("  Dzien   dobry!  "))
        assertEquals("", VoskGrammar.normalizeForGrammar("???"))
        assertEquals("", VoskGrammar.normalizeForGrammar("   "))
    }

    // ---- warianty i budowa gramatyki --------------------------------------------------

    @Test
    fun `przecinek i ukosnik rozbijaja haslo na osobne odpowiedzi`() {
        // Bez tego do gramatyki wchodzi fraza "towarzyski kontaktowy", ktorej nikt nie
        // wypowie w calosci — a samo "towarzyski" nie pasuje wtedy do niczego.
        val w = VoskGrammar.warianty("towarzyski, kontaktowy")
        assertTrue(w.contains("towarzyski"))
        assertTrue(w.contains("kontaktowy"))
        val z = VoskGrammar.warianty("żonaty/zamężna")
        assertTrue(z.contains("żonaty"))
        assertTrue(z.contains("zamężna"))
    }

    @Test
    fun `gramatyka zawsze konczy sie slowem smietnikiem`() {
        // Bez [unk] gramatyka jest zamknieta i KAZDY dzwiek dostaje najblizsza odpowiedz
        // z planszy — czyli strzal w losowy cel przy kazdym kaszlnieciu.
        val s = VoskGrammar.zbuduj(listOf("kolano", "plecy"))
        assertTrue(s.json.contains("\"" + VoskGrammar.UNK + "\""))
        assertFalse(s.slowa.contains(VoskGrammar.UNK))
    }

    @Test
    fun `gramatyka jest poprawna tablica JSON z polskimi znakami`() {
        val s = VoskGrammar.zbuduj(listOf("łóżko", "kolano"))
        assertTrue(s.json.startsWith("["))
        assertTrue(s.json.endsWith("]"))
        // Vosk czyta gramatyke wlasnym, bajtowym parserem JSON — polskie znaki maja pojsc
        // jako UTF-8, a nie jako ucieczki \\uXXXX.
        assertTrue(s.json.contains("łóżko"))
    }

    @Test
    fun `puste i bezliterowe hasla nie wchodza do gramatyki`() {
        val s = VoskGrammar.zbuduj(listOf("", "  ", "???", "kot"))
        assertEquals(listOf("kot"), s.slowa)
    }

    @Test
    fun `duplikaty po normalizacji lacza sie w jedna pozycje`() {
        val s = VoskGrammar.zbuduj(listOf("open-minded", "Open Minded!", "  open minded  "))
        assertEquals(1, s.slowa.size)
        // Pierwsze wygrywa: droga powrotna ma wskazywac haslo, ktore weszlo na plansze wczesniej,
        // bo to jego uczen czyta na potworze, ktory doszedl blizej dziala.
        assertEquals("open-minded", s.powrot["open minded"])
    }

    @Test
    fun `haslo z lacznikiem i bez lacznika to DWIE pozycje gramatyki`() {
        // Nie jest to niedopatrzenie, tylko skutek zasady "lacznik = spacja": "e-mail" staje
        // sie dwoma tokenami, "email" jednym. Obie postaci sa wymawialne i obie moga lezec na
        // planszy, wiec obie maja byc w gramatyce — sklejanie ich odebraloby jednej z nich glos.
        val s = VoskGrammar.zbuduj(listOf("e-mail", "email"))
        assertEquals(listOf("e mail", "email"), s.slowa)
    }

    @Test
    fun `gramatyka nie rosnie ponad limit`() {
        val duzo = (1..VoskGrammar.MAX_SLOW * 2).map { "slowo$it" }
        assertEquals(VoskGrammar.MAX_SLOW, VoskGrammar.zbuduj(duzo).slowa.size)
    }

    // ---- mapa powrotna ----------------------------------------------------------------

    @Test
    fun `mapa powrotna oddaje oryginalny napis planszy`() {
        val s = VoskGrammar.zbuduj(listOf("open-minded", "dry cleaner's"))
        assertEquals("open-minded", s.powrot["open minded"])
        assertEquals("dry cleaner's", s.powrot["dry cleaner s"])
    }

    @Test
    fun `hipotezy niosa oryginal przed postacia z gramatyki`() {
        val s = VoskGrammar.zbuduj(listOf("open-minded"))
        val w = VoskGrammar.czytajWynik(wynikJson("open minded", 200.0), 1.0, s, false)
        assertNotNull(w)
        assertEquals(listOf("open-minded", "open minded"), w!!.hipotezy)
    }

    @Test
    fun `haslo spoza mapy powrotnej zostawia sama postac z gramatyki`() {
        val s = VoskGrammar.zbuduj(listOf("kolano"))
        val w = VoskGrammar.czytajWynik(wynikJson("kolano", 200.0), 1.0, s, true)
        assertEquals(listOf("kolano"), w!!.hipotezy)
    }

    // ---- bramka pewnosci --------------------------------------------------------------

    @Test
    fun `pewnosc liczy sie na sekunde odcinka`() {
        assertEquals(70.0, VoskGrammar.pewnoscNaSekunde(140.0, 2.0), 1e-9)
        // Mianownik zero to nie "nieskonczona pewnosc", tylko brak danych.
        assertEquals(0.0, VoskGrammar.pewnoscNaSekunde(140.0, 0.0), 1e-9)
    }

    @Test
    fun `prog polski jest wyzszy niz angielski`() {
        // Rozklady sa inne: po polsku najslabsze prawdziwe trafienie mialo 71, po angielsku 68,
        // wiec ten sam prog scialby po angielsku prawdziwy wynik.
        assertTrue(VoskGrammar.PROG_PL > VoskGrammar.PROG_EN)
        assertEquals(VoskGrammar.PROG_PL, VoskGrammar.prog(true), 1e-9)
        assertEquals(VoskGrammar.PROG_EN, VoskGrammar.prog(false), 1e-9)
    }

    @Test
    fun `wynik ponizej progu jest odrzucany, powyzej przyjmowany`() {
        val s = VoskGrammar.zbuduj(listOf("kolano"))
        // 60 conf/s: w pomiarze taka wartosc miala mediana wypowiedzi SPOZA planszy.
        val slaby = VoskGrammar.czytajWynik(wynikJson("kolano", 60.0), 1.0, s, true)
        assertFalse(slaby!!.przyjete)
        // 79 conf/s: mediana prawdziwych trafien po polsku.
        val mocny = VoskGrammar.czytajWynik(wynikJson("kolano", 79.0), 1.0, s, true)
        assertTrue(mocny!!.przyjete)
    }

    @Test
    fun `dluzsza wypowiedz o tej samej pewnosci na sekunde przechodzi tak samo`() {
        // Sedno normalizacji: sama liczba pewnosci rosnie z dlugoscia odcinka, wiec bez
        // dzielenia prog znaczylby co innego przy kazdej dlugosci wypowiedzi.
        val s = VoskGrammar.zbuduj(listOf("kolano"))
        val krotka = VoskGrammar.czytajWynik(wynikJson("kolano", 79.0), 1.0, s, true)
        val dluga = VoskGrammar.czytajWynik(wynikJson("kolano", 237.0), 3.0, s, true)
        assertEquals(krotka!!.przyjete, dluga!!.przyjete)
        assertEquals(krotka.pewnoscNaSekunde, dluga.pewnoscNaSekunde, 0.5)
    }

    @Test
    fun `wynik bez pola pewnosci przechodzi zamiast gasnac po cichu`() {
        // Gdyby ktos zdjal setMaxAlternatives, Vosk przestaje oddawac "confidence". Odrzucanie
        // wszystkiego byloby wtedy gluchym mikrofonem bez jednej linii w logu.
        val s = VoskGrammar.zbuduj(listOf("kolano"))
        val w = VoskGrammar.czytajWynik("""{"text":"kolano"}""", 1.0, s, true)
        assertTrue(w!!.przyjete)
    }

    // ---- odsiew pustych odpowiedzi ----------------------------------------------------

    @Test
    fun `slowo smietnik nie jest wynikiem`() {
        val s = VoskGrammar.zbuduj(listOf("kolano"))
        assertNull(VoskGrammar.czytajWynik(wynikJson(VoskGrammar.UNK, 500.0), 1.0, s, true))
        assertTrue(VoskGrammar.czytajCzesciowy("""{"partial":"[unk]"}""", s).isEmpty())
    }

    @Test
    fun `pusty i uszkodzony wynik nie wywracaja niczego`() {
        val s = VoskGrammar.zbuduj(listOf("kolano"))
        assertNull(VoskGrammar.czytajWynik("", 1.0, s, true))
        assertNull(VoskGrammar.czytajWynik("{{{", 1.0, s, true))
        assertNull(VoskGrammar.czytajWynik("""{"text":"   "}""", 1.0, s, true))
        assertNull(VoskGrammar.czytajWynik("""{"alternatives":[]}""", 1.0, s, true))
        assertTrue(VoskGrammar.czytajCzesciowy("nie-json", s).isEmpty())
        assertTrue(VoskGrammar.czytajCzesciowy("""{"partial":""}""", s).isEmpty())
    }

    @Test
    fun `wynik czesciowy tez wraca przez mape powrotna`() {
        val s = VoskGrammar.zbuduj(listOf("open-minded"))
        assertEquals(
            listOf("open-minded", "open minded"),
            VoskGrammar.czytajCzesciowy("""{"partial":"open minded"}""", s),
        )
    }

    // ---- caly slownik gry --------------------------------------------------------------

    @Test
    fun `kazde haslo wbudowanych zestawow daje niepusta pozycje gramatyki`() {
        // Haslo, ktore po normalizacji zostaje puste, jest dla glosu nieme — uczen widzi je
        // na planszy i nie ma jak go powiedziec. Dzis takich nie ma i ma tak zostac.
        val nieme = mutableListOf<String>()
        for (zestaw in BuiltInSets.all()) {
            for (wpis in zestaw.entries) {
                if (VoskGrammar.warianty(wpis.term).isEmpty()) nieme.add(wpis.term)
                if (VoskGrammar.warianty(wpis.definition).isEmpty()) nieme.add(wpis.definition)
            }
        }
        assertEquals(emptyList<String>(), nieme)
    }

    @Test
    fun `plansza z calego zestawu miesci sie w gramatyce`() {
        // Najwiekszy realny slownik: zestaw przekrojowy przy kierunku losowym, czyli obie
        // strony kazdego hasla naraz.
        val zestaw = BuiltInSets.randomMix(count = 40, seed = 1)
        val slowa = zestaw.entries.flatMap { listOf(it.term, it.definition) }
        val s = VoskGrammar.zbuduj(slowa)
        assertTrue(s.slowa.size <= VoskGrammar.MAX_SLOW)
        assertTrue(s.slowa.isNotEmpty())
        // Kazde haslo ma droge powrotna — inaczej trafienie wrocilo by do gry w postaci,
        // ktorej plansza nie zna.
        for (w in slowa) {
            for (wariant in VoskGrammar.warianty(w)) {
                assertTrue("brak drogi powrotnej dla " + wariant, s.powrot.containsKey(wariant))
            }
        }
    }

    // ---- pasek glosu i wybor modelu ---------------------------------------------------

    @Test
    fun `pasek glosu stoi pusty tylko przy zerowym sygnale`() {
        assertEquals(0f, VoskVoiceInput.poziomPaska(0), 1e-6f)
        // Szum cichego pokoju (ok. -48 dBFS) to wciaz dno paska...
        assertTrue(VoskVoiceInput.poziomPaska(130) < 0.05f)
        // ...a normalnie wypowiedziane slowo (szczyt ok. 3000 z 32768) ma go wyraznie podniesc.
        // Przy skali liniowej wypadaloby tu 0,09 i pasek wygladalby jak martwy.
        assertTrue(VoskVoiceInput.poziomPaska(3000) > 0.5f)
        assertEquals(1f, VoskVoiceInput.poziomPaska(32767), 1e-3f)
    }

    @Test
    fun `pasek glosu rosnie monotonicznie i nie wychodzi poza zakres`() {
        var poprzedni = -1f
        for (szczyt in intArrayOf(0, 1, 10, 100, 1000, 5000, 20000, 32767, 40000)) {
            val v = VoskVoiceInput.poziomPaska(szczyt)
            assertTrue("poziom poza zakresem dla " + szczyt, v in 0f..1f)
            assertTrue("poziom spadl przy " + szczyt, v >= poprzedni)
            poprzedni = v
        }
    }

    @Test
    fun `tag jezyka wybiera wlasciwy model`() {
        assertEquals("pl", VoskVoiceInput.kodJezyka("pl-PL"))
        assertEquals("en", VoskVoiceInput.kodJezyka("en-US"))
        // Tag bez regionu i tag z innym regionem tez musza trafic w model, a nie odbic sie
        // od mapy: gra podaje "pl-PL"/"en-US", ale ekran testu mowy chodzi po wariantach.
        assertEquals("pl", VoskVoiceInput.kodJezyka("pl"))
        assertEquals("en", VoskVoiceInput.kodJezyka("en-GB"))
        // Cokolwiek innego to angielski — jedyny sensowny domysl w grze, ktora uczy angielskiego.
        assertEquals("en", VoskVoiceInput.kodJezyka("de-DE"))
    }

    /** Odpowiedz Vosk-a w postaci, ktora daje setMaxAlternatives — tylko pola, ktore czytamy. */
    private fun wynikJson(tekst: String, pewnosc: Double): String =
        """{"alternatives":[{"confidence":$pewnosc,"text":"$tekst"}]}"""
}
