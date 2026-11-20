package com.matura.app.learn

/** Data z kalendarza: rok, miesiac 1..12, dzien 1..31. Zwykle trzy liczby, bez strefy i godziny. */
data class DataKalendarzowa(val rok: Int, val miesiac: Int, val dzien: Int)

/**
 * Kalendarz do ODLICZANIA DNI DO MATURY: zamiana daty na numer dnia i z powrotem oraz roznica
 * dwoch dni. Czysta arytmetyka na liczbach calkowitych.
 *
 * DLACZEGO TU JEST INNY DZIEN NIZ W [Srs]. Harmonogram i ksiega dorobku licza dobe w UTC, bo
 * maja byc odporne na zmiane strefy i czasu. Odliczanie niczego nie liczy i niczego nie
 * zapisuje: jest wylacznie do pokazania i ma sie zgadzac z kalendarzem na scianie. O 00:30
 * czasu polskiego dzien harmonogramu jest jeszcze wczorajszy, a uczen patrzacy na "167 dni"
 * sprawdzi to na palcach wedlug daty w telefonie. Dlatego tutaj, i tylko tutaj, liczy sie
 * LOKALNY dzien kalendarzowy ([dzienLokalny]). Tych dwoch dni nie wolno mieszac: dzien
 * z tego pliku nie moze trafic ani do [Srs], ani do [Nagrody].
 *
 * DLACZEGO WLASNA ARYTMETYKA. minSdk to 24, a java.time jest w Androidzie od API 26; wciaganie
 * biblioteki zastepczej dla trzech dzialan na liczbach byloby przesada. Przesuniecie strefy
 * wchodzi parametrem, wiec plik nie zna ani zegara, ani strefy i testuje sie go zwyklym
 * JUnitem (testy porownuja wyniki z java.time, bo biegna na JVM).
 *
 * Kalendarz jest gregorianski "od zawsze" (proleptyczny), tak jak w java.time.LocalDate.
 */
object Kalendarz {

    /** Czy rok ma 29 lutego: podzielny przez 4, ale lata pelnych stuleci tylko co czwarte. */
    fun przestepny(rok: Int): Boolean = rok % 4 == 0 && (rok % 100 != 0 || rok % 400 == 0)

    /** Ile dni ma miesiac. Miesiac spoza 1..12 jest najpierw przycinany do tego zakresu. */
    fun dniWMiesiacu(rok: Int, miesiac: Int): Int = when (miesiac.coerceIn(1, 12)) {
        2 -> if (przestepny(rok)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    /**
     * Najblizsza istniejaca data: miesiac przyciety do 1..12, dzien do 1..ostatni dzien tego
     * miesiaca. 30 lutego staje sie 28 albo 29 lutego, 31 kwietnia 30 kwietnia.
     *
     * Po co: date matury uczen ustawia trzema osobnymi polami, wiec zmiana miesiaca albo roku
     * potrafi zostawic dzien, ktorego w nowym miesiacu nie ma. Przyciecie do konca miesiaca
     * jest tym, czego uczen sie spodziewa; przeniesienie nadmiaru na nastepny miesiac
     * (30 lutego -> 2 marca) zmienialoby mu miesiac, ktory wlasnie wybral.
     */
    fun popraw(rok: Int, miesiac: Int, dzien: Int): DataKalendarzowa {
        val m = miesiac.coerceIn(1, 12)
        return DataKalendarzowa(rok, m, dzien.coerceIn(1, dniWMiesiacu(rok, m)))
    }

    /**
     * Numer dnia: ile dni minelo od 1970-01-01 (ten dzien ma numer 0). Data ma byc istniejaca;
     * niepewna najpierw przez [popraw].
     *
     * Rachunek liczy lata od 1 marca, wtedy dzien przestepny wypada na samym koncu roku i nie
     * trzeba zadnej tabeli miesiecy. 146097 to liczba dni w 400 latach, czyli w pelnym cyklu
     * kalendarza gregorianskiego, a 719468 to liczba dni od 0000-03-01 do 1970-01-01.
     */
    fun dzienEpoki(rok: Int, miesiac: Int, dzien: Int): Int {
        val r = if (miesiac <= 2) rok - 1 else rok
        val cykl = Math.floorDiv(r, 400)
        val rokCyklu = r - cykl * 400                                   // 0..399
        val miesiacOdMarca = (miesiac + 9) % 12                         // marzec = 0, luty = 11
        val dzienRoku = (153 * miesiacOdMarca + 2) / 5 + dzien - 1      // 0..365
        val dzienCyklu = rokCyklu * 365 + rokCyklu / 4 - rokCyklu / 100 + dzienRoku
        return cykl * 146_097 + dzienCyklu - 719_468
    }

    fun dzienEpoki(data: DataKalendarzowa): Int = dzienEpoki(data.rok, data.miesiac, data.dzien)

    /** Odwrotnosc [dzienEpoki]: data dla numeru dnia. */
    fun data(dzienEpoki: Int): DataKalendarzowa {
        val z = dzienEpoki.toLong() + 719_468
        val cykl = Math.floorDiv(z, 146_097L)
        val dzienCyklu = (z - cykl * 146_097).toInt()                   // 0..146096
        val rokCyklu =
            (dzienCyklu - dzienCyklu / 1_460 + dzienCyklu / 36_524 - dzienCyklu / 146_096) / 365
        val dzienRoku = dzienCyklu - (365 * rokCyklu + rokCyklu / 4 - rokCyklu / 100)
        val miesiacOdMarca = (5 * dzienRoku + 2) / 153                  // 0..11
        val dzien = dzienRoku - (153 * miesiacOdMarca + 2) / 5 + 1
        val miesiac = if (miesiacOdMarca < 10) miesiacOdMarca + 3 else miesiacOdMarca - 9
        val rok = (rokCyklu + cykl * 400).toInt() + (if (miesiac <= 2) 1 else 0)
        return DataKalendarzowa(rok, miesiac, dzien)
    }

    /**
     * Lokalny dzien kalendarzowy jako numer dnia epoki.
     *
     * @param nowMs chwila w milisekundach od epoki UTC.
     * @param przesuniecieMs przesuniecie strefy wzgledem UTC W TEJ CHWILI, razem z czasem
     *   letnim, czyli dokladnie to, co oddaje java.util.TimeZone.getOffset(nowMs). Wolajacy
     *   pyta o nie przy kazdym odczycie: w Polsce to 3 600 000 zima i 7 200 000 latem.
     *
     * floorDiv, a nie zwykle dzielenie, z tego samego powodu co w [Srs.dayOf]: dla chwili
     * sprzed 1970 dzielenie obcina do zera i dwa rozne dni zlewaja sie w jeden.
     */
    fun dzienLokalny(nowMs: Long, przesuniecieMs: Int): Int =
        Math.floorDiv(nowMs + przesuniecieMs, Srs.MS_PER_DAY).toInt()

    /**
     * Ile dni zostalo do matury: zero w dniu egzaminu, liczba UJEMNA, gdy termin minal.
     *
     * Wynik celowo nie jest przycinany do zera. "0 dni" dzien po egzaminie byloby nieprawda,
     * a ekran i tak musi wtedy zrobic cos innego niz odliczanie (schowac licznik albo
     * zaproponowac nowy termin), wiec ma dostac informacje, ze termin jest za nami.
     *
     * @param maturaDzien dzien egzaminu jako numer dnia epoki (Options.maturaDzien).
     * @param dzienLokalny dzisiejszy LOKALNY dzien, patrz [dzienLokalny]; nie dzien harmonogramu.
     */
    fun dniDoMatury(maturaDzien: Int, dzienLokalny: Int): Int = maturaDzien - dzienLokalny
}
