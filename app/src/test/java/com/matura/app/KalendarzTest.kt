package com.matura.app

import com.matura.app.learn.DataKalendarzowa
import com.matura.app.learn.Kalendarz
import com.matura.app.learn.Srs
import com.matura.app.model.Options
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.TimeZone

/**
 * Kalendarz do odliczania dni do matury.
 *
 * Kod pod testem liczy na golych liczbach, bo na Androidzie 7 nie ma java.time. Testy biegna
 * na JVM, wiec moga porownac kazdy wynik z java.time: to jest tu sedzia, a nie druga
 * implementacja tej samej arytmetyki.
 */
class KalendarzTest {

    private companion object {
        const val GODZINA = 3_600_000L
        val WARSZAWA: ZoneId = ZoneId.of("Europe/Warsaw")
        val STREFA: TimeZone = TimeZone.getTimeZone("Europe/Warsaw")
    }

    /** Chwila w milisekundach dla czasu sciennego w Polsce. */
    private fun wPolsce(rok: Int, miesiac: Int, dzien: Int, godzina: Int, minuta: Int = 0): Long =
        LocalDateTime.of(rok, miesiac, dzien, godzina, minuta).atZone(WARSZAWA).toInstant().toEpochMilli()

    /** Lokalny dzien tak, jak policzy go aplikacja: z chwili i z przesuniecia strefy w tej chwili. */
    private fun dzienWPolsce(ms: Long): Int = Kalendarz.dzienLokalny(ms, STREFA.getOffset(ms))

    // --- data matury ----------------------------------------------------------------------

    @Test
    fun `domyslny dzien matury to 6 maja 2027`() {
        assertEquals(20944, Kalendarz.dzienEpoki(2027, 5, 6))
        assertEquals(DataKalendarzowa(2027, 5, 6), Kalendarz.data(20944))
        assertEquals(20944L, LocalDate.of(2027, 5, 6).toEpochDay())
        assertEquals("opcje i kalendarz mowia o tym samym dniu", 20944, Options().maturaDzien)
        assertTrue("odliczanie jest domyslnie wlaczone", Options().odliczanie)
    }

    @Test
    fun `od 20 listopada 2026 do matury zostalo 167 dni`() {
        val dzis = Kalendarz.dzienEpoki(2026, 11, 20)

        assertEquals(167, Kalendarz.dniDoMatury(Options().maturaDzien, dzis))
    }

    @Test
    fun `w dniu egzaminu zostaje zero dni, a po terminie liczba jest ujemna`() {
        val matura = Options().maturaDzien

        assertEquals(1, Kalendarz.dniDoMatury(matura, matura - 1))
        assertEquals(0, Kalendarz.dniDoMatury(matura, matura))
        // Nie zero: ekran ma wiedziec, ze termin minal, i zrobic cos innego niz odliczanie.
        assertEquals(-1, Kalendarz.dniDoMatury(matura, matura + 1))
        assertEquals(-365, Kalendarz.dniDoMatury(matura, matura + 365))
    }

    // --- zamiana daty na numer dnia i z powrotem ------------------------------------------

    @Test
    fun `numer dnia zgadza sie z java time dzien po dniu przez dwa stulecia`() {
        var data = LocalDate.of(1899, 12, 25)
        val koniec = LocalDate.of(2101, 1, 5)
        while (data.isBefore(koniec)) {
            val numer = data.toEpochDay().toInt()
            assertEquals("$data", numer, Kalendarz.dzienEpoki(data.year, data.monthValue, data.dayOfMonth))
            assertEquals(
                "$data",
                DataKalendarzowa(data.year, data.monthValue, data.dayOfMonth),
                Kalendarz.data(numer),
            )
            data = data.plusDays(1)
        }
    }

    @Test
    fun `daty odlegle i sprzed epoki tez sie zgadzaja`() {
        assertEquals(0, Kalendarz.dzienEpoki(1970, 1, 1))
        assertEquals(-1, Kalendarz.dzienEpoki(1969, 12, 31))
        assertEquals(DataKalendarzowa(1969, 12, 31), Kalendarz.data(-1))

        val odlegle = listOf(
            LocalDate.of(1, 1, 1), LocalDate.of(1600, 2, 29), LocalDate.of(1700, 3, 1),
            LocalDate.of(1900, 2, 28), LocalDate.of(1900, 3, 1), LocalDate.of(2000, 2, 29),
            LocalDate.of(2400, 2, 29), LocalDate.of(2400, 12, 31), LocalDate.of(9999, 12, 31),
            LocalDate.of(0, 3, 1), LocalDate.of(-400, 2, 29),
        )
        for (data in odlegle) {
            val numer = data.toEpochDay().toInt()
            assertEquals("$data", numer, Kalendarz.dzienEpoki(data.year, data.monthValue, data.dayOfMonth))
            assertEquals(
                "$data",
                DataKalendarzowa(data.year, data.monthValue, data.dayOfMonth),
                Kalendarz.data(numer),
            )
        }
        assertEquals(
            Kalendarz.dzienEpoki(2027, 5, 6),
            Kalendarz.dzienEpoki(DataKalendarzowa(2027, 5, 6)),
        )
    }

    // --- lata przestepne i dlugosc miesiecy -----------------------------------------------

    @Test
    fun `lata przestepne - co cztery, ale stulecia tylko co czterysta`() {
        assertTrue(Kalendarz.przestepny(2024))
        assertFalse(Kalendarz.przestepny(2026))
        assertFalse("rok matury nie jest przestepny", Kalendarz.przestepny(2027))
        assertTrue(Kalendarz.przestepny(2028))
        assertFalse(Kalendarz.przestepny(1900))
        assertTrue(Kalendarz.przestepny(2000))
        assertFalse(Kalendarz.przestepny(2100))
        assertTrue(Kalendarz.przestepny(2400))

        for (rok in 1890..2110) {
            assertEquals("rok $rok", java.time.Year.isLeap(rok.toLong()), Kalendarz.przestepny(rok))
        }
    }

    @Test
    fun `dlugosc kazdego miesiaca zgadza sie z kalendarzem`() {
        assertEquals(28, Kalendarz.dniWMiesiacu(2027, 2))
        assertEquals(29, Kalendarz.dniWMiesiacu(2028, 2))
        assertEquals(28, Kalendarz.dniWMiesiacu(2100, 2))
        assertEquals(30, Kalendarz.dniWMiesiacu(2027, 4))
        assertEquals(31, Kalendarz.dniWMiesiacu(2027, 5))

        for (rok in listOf(1900, 2000, 2024, 2027, 2028, 2100)) {
            for (miesiac in 1..12) {
                assertEquals(
                    "$rok-$miesiac",
                    YearMonth.of(rok, miesiac).lengthOfMonth(),
                    Kalendarz.dniWMiesiacu(rok, miesiac),
                )
            }
        }
    }

    // --- data z trzech osobnych pol -------------------------------------------------------

    @Test
    fun `niemozliwa data jest przycinana do ostatniego dnia miesiaca`() {
        assertEquals(DataKalendarzowa(2027, 2, 28), Kalendarz.popraw(2027, 2, 30))
        assertEquals(DataKalendarzowa(2028, 2, 29), Kalendarz.popraw(2028, 2, 30))
        assertEquals(DataKalendarzowa(2027, 2, 28), Kalendarz.popraw(2027, 2, 29))
        assertEquals(DataKalendarzowa(2027, 4, 30), Kalendarz.popraw(2027, 4, 31))
        assertEquals(DataKalendarzowa(2027, 6, 30), Kalendarz.popraw(2027, 6, 99))
        // Uczen wybral luty, wiec zostaje w lutym: nadmiar nie przechodzi na marzec.
        assertEquals(2, Kalendarz.popraw(2027, 2, 31).miesiac)
    }

    @Test
    fun `miesiac i dzien spoza zakresu wracaja do zakresu, a poprawna data zostaje bez zmian`() {
        assertEquals(DataKalendarzowa(2027, 12, 31), Kalendarz.popraw(2027, 13, 31))
        assertEquals(DataKalendarzowa(2027, 1, 15), Kalendarz.popraw(2027, 0, 15))
        assertEquals(DataKalendarzowa(2027, 1, 1), Kalendarz.popraw(2027, -3, 0))
        assertEquals(DataKalendarzowa(2027, 5, 1), Kalendarz.popraw(2027, 5, -7))

        var data = LocalDate.of(2026, 1, 1)
        while (data.year < 2029) {
            val trojka = DataKalendarzowa(data.year, data.monthValue, data.dayOfMonth)
            assertEquals(trojka, Kalendarz.popraw(trojka.rok, trojka.miesiac, trojka.dzien))
            data = data.plusDays(1)
        }
    }

    @Test
    fun `poprawiona data zawsze istnieje i daje sie zamienic na numer dnia i z powrotem`() {
        for (rok in listOf(2026, 2027, 2028)) {
            for (miesiac in 0..13) {
                for (dzien in 0..32) {
                    val data = Kalendarz.popraw(rok, miesiac, dzien)
                    val wJavie = LocalDate.of(data.rok, data.miesiac, data.dzien) // rzuca dla daty niemozliwej
                    assertEquals(wJavie.toEpochDay().toInt(), Kalendarz.dzienEpoki(data))
                    assertEquals(data, Kalendarz.data(Kalendarz.dzienEpoki(data)))
                }
            }
        }
    }

    // --- dzien lokalny ------------------------------------------------------------------------

    @Test
    fun `dzien lokalny lamie sie o lokalnej polnocy, zima i latem`() {
        // Zima, UTC+1: polnoc w Polsce to 23:00 UTC dnia poprzedniego.
        val zimowaPolnoc = wPolsce(2026, 11, 20, 0)
        assertEquals(Kalendarz.dzienEpoki(2026, 11, 20), dzienWPolsce(zimowaPolnoc))
        assertEquals(Kalendarz.dzienEpoki(2026, 11, 19), dzienWPolsce(zimowaPolnoc - 1))
        assertEquals(Kalendarz.dzienEpoki(2026, 11, 20), dzienWPolsce(wPolsce(2026, 11, 20, 23, 59)))

        // Lato, UTC+2: dzien egzaminu.
        val letniaPolnoc = wPolsce(2027, 5, 6, 0)
        assertEquals(20944, dzienWPolsce(letniaPolnoc))
        assertEquals(20943, dzienWPolsce(letniaPolnoc - 1))
        assertEquals(20944, dzienWPolsce(wPolsce(2027, 5, 6, 9)))
    }

    @Test
    fun `dzien zmiany czasu na letni, 28 marca 2027, ma 23 godziny i dalej jest jednym dniem`() {
        val poczatek = wPolsce(2027, 3, 28, 0)
        val koniec = wPolsce(2027, 3, 29, 0)
        assertEquals("o 2 w nocy zegar przeskakuje na 3", 23 * GODZINA, koniec - poczatek)

        val dzien = Kalendarz.dzienEpoki(2027, 3, 28)
        assertEquals(dzien - 1, dzienWPolsce(poczatek - 1))
        assertEquals(dzien, dzienWPolsce(poczatek))
        // Minuta przed zmiana (01:59 czasu zimowego) i chwila zmiany (03:00 czasu letniego).
        assertEquals(dzien, dzienWPolsce(wPolsce(2027, 3, 28, 1, 59)))
        assertEquals(dzien, dzienWPolsce(wPolsce(2027, 3, 28, 3)))
        assertEquals(dzien, dzienWPolsce(koniec - 1))
        assertEquals(dzien + 1, dzienWPolsce(koniec))

        // Odliczanie tego dnia nie gubi ani nie podwaja dnia.
        assertEquals(39, Kalendarz.dniDoMatury(20944, dzienWPolsce(poczatek)))
        assertEquals(39, Kalendarz.dniDoMatury(20944, dzienWPolsce(koniec - 1)))
        assertEquals(38, Kalendarz.dniDoMatury(20944, dzienWPolsce(koniec)))
    }

    @Test
    fun `dzien zmiany czasu na zimowy, 31 pazdziernika 2027, ma 25 godzin i dalej jest jednym dniem`() {
        val poczatek = wPolsce(2027, 10, 31, 0)
        val koniec = wPolsce(2027, 11, 1, 0)
        assertEquals("godzina miedzy 2 a 3 zdarza sie dwa razy", 25 * GODZINA, koniec - poczatek)

        val dzien = Kalendarz.dzienEpoki(2027, 10, 31)
        assertEquals(dzien - 1, dzienWPolsce(poczatek - 1))
        assertEquals(dzien, dzienWPolsce(poczatek))
        // Obie godziny "druga w nocy": pierwsza jeszcze w czasie letnim, druga juz w zimowym.
        assertEquals(dzien, dzienWPolsce(poczatek + 2 * GODZINA + GODZINA / 2))
        assertEquals(dzien, dzienWPolsce(poczatek + 3 * GODZINA + GODZINA / 2))
        assertEquals(dzien, dzienWPolsce(koniec - 1))
        assertEquals(dzien + 1, dzienWPolsce(koniec))
    }

    @Test
    fun `dzien lokalny zgadza sie z java time co kwadrans wokol obu zmian czasu`() {
        for (dzienZmiany in listOf(LocalDate.of(2027, 3, 28), LocalDate.of(2027, 10, 31))) {
            var ms = dzienZmiany.minusDays(2).atStartOfDay(WARSZAWA).toInstant().toEpochMilli()
            val koniec = dzienZmiany.plusDays(3).atStartOfDay(WARSZAWA).toInstant().toEpochMilli()
            while (ms < koniec) {
                val wJavie = Instant.ofEpochMilli(ms).atZone(WARSZAWA).toLocalDate().toEpochDay().toInt()
                assertEquals("chwila $ms", wJavie, dzienWPolsce(ms))
                ms += GODZINA / 4
            }
        }
    }

    @Test
    fun `dzien lokalny nie zalezy od strefy urzadzenia, tylko od podanego przesuniecia`() {
        val chwila = wPolsce(2027, 5, 5, 23, 30)
        val domyslna = TimeZone.getDefault()
        try {
            for (strefa in listOf("Pacific/Kiritimati", "Pacific/Niue", "UTC")) {
                TimeZone.setDefault(TimeZone.getTimeZone(strefa))
                assertEquals(strefa, 20943, Kalendarz.dzienLokalny(chwila, STREFA.getOffset(chwila)))
            }
        } finally {
            TimeZone.setDefault(domyslna)
        }
        // Ta sama chwila na Kiribati (UTC+14) to juz przedpoludnie 6 maja.
        val kiribati = TimeZone.getTimeZone("Pacific/Kiritimati")
        assertEquals(20944, Kalendarz.dzienLokalny(chwila, kiribati.getOffset(chwila)))
    }

    @Test
    fun `chwila sprzed epoki nie zlewa dwoch dni w jeden`() {
        // Zegar telefonu potrafi wrocic do 1970-01-01; w strefie na zachod od Greenwich to juz
        // 1969. Dzielenie z obcinaniem do zera dawaloby wtedy dzien 0 zamiast -1.
        assertEquals(0, Kalendarz.dzienLokalny(0L, 0))
        assertEquals(-1, Kalendarz.dzienLokalny(-1L, 0))
        assertEquals(-1, Kalendarz.dzienLokalny(0L, -GODZINA.toInt()))
        assertEquals(-1, Kalendarz.dzienLokalny(-Srs.MS_PER_DAY, 0))
        assertEquals(-2, Kalendarz.dzienLokalny(-Srs.MS_PER_DAY - 1, 0))
        assertEquals(0, Kalendarz.dzienLokalny(-1L, GODZINA.toInt()))
    }

    // --- dwa rozne dni: kalendarz i harmonogram -------------------------------------------

    @Test
    fun `odliczanie idzie za kalendarzem na scianie, a nie za doba harmonogramu`() {
        // 00:30 w Polsce, 5 maja 2027. Na scianie jest juz 5 maja i do matury zostal JEDEN dzien.
        // Harmonogram liczy dobe w UTC, wiec dla niego wciaz trwa 4 maja: gdyby odliczanie
        // bralo jego dzien, pokazywaloby "2 dni" i uczen widzialby blad w kalendarzu.
        val chwila = wPolsce(2027, 5, 5, 0, 30)

        val naScianie = dzienWPolsce(chwila)
        val wHarmonogramie = Srs.dayOf(chwila)

        assertEquals(Kalendarz.dzienEpoki(2027, 5, 5), naScianie)
        assertEquals(naScianie - 1, wHarmonogramie)
        assertEquals(1, Kalendarz.dniDoMatury(Options().maturaDzien, naScianie))
    }
}
