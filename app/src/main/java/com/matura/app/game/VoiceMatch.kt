package com.matura.app.game

/**
 * Dopasowanie wypowiedzianej odpowiedzi do tego, co stoi na planszy.
 *
 * Rozpoznawanie mowy prawie nigdy nie zwraca dokladnie tego slowa, ktore padlo:
 * dokleja rodzajnik ("the dog"), rozbija wyraz ("samo chod"), zapisuje fonetycznie
 * ("ksionszka"). Pytanie "czy transkrypt rowna sie odpowiedzi" jest wiec zle
 * postawione.
 *
 * Kluczowa obserwacja: slownik na planszy jest ZAMKNIETY - najwyzej kilkanascie
 * lancuchow (potwory + skrzynie). Wlasciwe pytanie brzmi "do ktorej z tych
 * kilkunastu odpowiedzi jest najblizej". Liczymy to trzema torami rownolegle:
 * po zapisie, po uproszczeniu fonetycznym i po szkielecie spolgloskowym.
 *
 * Zasada bezpieczenstwa: falszywy strzal zabija niewlasciwy cel i utrwala zle
 * skojarzenie, a pudlo kosztuje tylko powtorzenie. Dlatego bramka jest surowa
 * i wymaga przewagi nad drugim kandydatem.
 *
 * Logika jest wspolna z wersja przegladarkowa (matura.uk/demo) - progi zostaly
 * dobrane pomiarem na zestawie 55 przypadkow, patrz VoiceMatchTest.
 */
object VoiceMatch {

    data class Hit(val score: Double, val answer: String?)

    data class Result(val best: Hit, val second: Hit) {
        val accepted: Boolean get() = accepts(best, second)
    }

    // ---- odleglosc edycyjna ----
    fun lev(a: String, b: String): Int {
        val n = a.length
        val m = b.length
        if (n == 0) return m
        if (m == 0) return n
        var prev = IntArray(m + 1) { it }
        var cur = IntArray(m + 1)
        for (i in 1..n) {
            cur[0] = i
            val ca = a[i - 1]
            for (j in 1..m) {
                val cost = if (ca == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[m]
    }

    fun sim(a: String, b: String): Double =
        if (a.isEmpty() || b.isEmpty()) 0.0
        else 1.0 - lev(a, b).toDouble() / maxOf(a.length, b.length)

    // ---- uproszczenia fonetyczne ----
    // Uwaga: normalize() juz zdjelo znaki diakrytyczne, wiec pracujemy na [a-z0-9 ].
    private val NASAL_O = Regex("o[nm](?=[pbtdkgcsz])")
    private val NASAL_E = Regex("e[nm](?=[pbtdkgcsz])")
    private val DOUBLED = Regex("(.)\\1+")

    fun phon(s: String, polish: Boolean): String {
        var t = s
        if (polish) {
            // nosowki: ASR zapisuje ą/ę jako on/om/en/em ("ksionszka" = ksiazka)
            t = NASAL_O.replace(t, "a")
            t = NASAL_E.replace(t, "e")
            // pary brzmiace tak samo: rz/z, ch/h, oraz szeregi sz/s, cz/c, dz/c
            t = t.replace("rz", "z").replace("ch", "h")
                .replace("dz", "c").replace("sz", "s").replace("cz", "c")
            t = t.replace("u", "o")                       // u / ó
            // ubezdzwiecznienie w wyglosie
            t = Regex("b\\b").replace(t, "p")
            t = Regex("d\\b").replace(t, "t")
            t = Regex("g\\b").replace(t, "k")
            t = Regex("w\\b").replace(t, "f")
            t = Regex("z\\b").replace(t, "s")
        } else {
            t = t.replace("ph", "f").replace("gh", "").replace("ck", "k")
                .replace("qu", "kw").replace("x", "ks")
            t = Regex("\\bkn").replace(t, "n")
            t = Regex("\\bwr").replace(t, "r")
            t = t.replace("c", "k")
            t = Regex("e\\b").replace(t, "")
            t = t.replace("w", "u")                       // "hows" = house
        }
        return DOUBLED.replace(t, "$1").trim()
    }

    /** Szkielet spolgloskowy - ratuje trafienia, gdy ASR pomyli same samogloski. */
    fun skel(s: String): String =
        DOUBLED.replace(s.replace(Regex("[aeiouy]"), ""), "$1").trim()

    fun score(hyp: String, cand: String, polish: Boolean): Double {
        if (hyp == cand) return 1.0
        val direct = sim(hyp, cand)
        // Urwana wypowiedz ("ko" zamiast "kot") jest krotsza od celu. Przy duzej
        // roznicy dlugosci ufamy WYLACZNIE zapisowi - luzniejsze scorery fonetyczne
        // potrafia zrownac prefiks z calym slowem.
        if (minOf(hyp.length, cand.length).toDouble() / maxOf(hyp.length, cand.length) < 0.6) return direct
        val a = skel(hyp)
        val b = skel(cand)
        val skeletal = if (a.length >= 3 && b.length >= 3) sim(a, b) * 0.88 else 0.0
        return maxOf(direct, sim(phon(hyp, polish), phon(cand, polish)) * 0.96, skeletal)
    }

    /**
     * Z jednego transkryptu robimy kilka hipotez: calosc, pojedyncze slowa, pary
     * i sklejke. ASR lubi dokleic rodzajnik albo rozbic slowo na dwa.
     */
    fun hypotheses(raw: String): List<String> {
        val t = Matching.normalize(raw)
        if (t.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        out.add(t)
        val w = t.split(' ').filter { it.isNotEmpty() }
        if (w.size > 1) {
            out.addAll(w)
            for (i in 0 until w.size - 1) out.add(w[i] + " " + w[i + 1])
            out.add(w.joinToString(""))
        }
        return out.toList()
    }

    fun match(transcripts: List<String>, candidates: List<String>, polish: Boolean): Result {
        var best = Hit(0.0, null)
        var second = Hit(0.0, null)
        for (raw in transcripts) {
            for (hyp in hypotheses(raw)) {
                for (cand in candidates) {
                    val s = score(hyp, cand, polish)
                    if (s > best.score) {
                        if (best.answer != null && best.answer != cand) second = best
                        best = Hit(s, cand)
                    } else if (s > second.score && cand != best.answer) {
                        second = Hit(s, cand)
                    }
                }
            }
        }
        return Result(best, second)
    }

    fun accepts(best: Hit, second: Hit): Boolean {
        val answer = best.answer ?: return false
        val len = answer.replace(" ", "").length
        // Krotkie slowa sa z natury do siebie podobne, wiec zamiast zabraniac ich
        // w ogole, wymagamy od nich WIEKSZEJ przewagi nad drugim kandydatem.
        val need = when {
            len <= 3 -> 0.90
            len <= 4 -> 0.78
            len <= 5 -> 0.76
            else -> 0.68
        }
        val margin = if (len <= 4) 0.15 else if (len <= 5) 0.10 else 0.06
        if (best.score < need) return false
        if (best.score < 0.999 && (best.score - second.score) < margin) return false
        return true
    }
}
