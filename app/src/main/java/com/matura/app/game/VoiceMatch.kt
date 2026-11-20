package com.matura.app.game

/**
 * Dopasowanie wypowiedzianej odpowiedzi do tego, co stoi na planszy.
 *
 * Rozpoznawanie mowy prawie nigdy nie zwraca dokladnie tego slowa, ktore padlo:
 * dokleja rodzajnik ("the dog"), rozbija wyraz ("samo chod"), zapisuje fonetycznie
 * ("ksionszka"). Pytanie "czy transkrypt rowna sie odpowiedzi" jest wiec zle
 * postawione.
 *
 * Kluczowa obserwacja: slownik jest ZAMKNIETY. Uczen mowi slowa z zestawu, w ktory
 * wlasnie gra, wiec wlasciwe pytanie brzmi "do ktorej odpowiedzi z zestawu jest
 * najblizej", a strzal pada tylko wtedy, gdy ta odpowiedz stoi akurat na planszy.
 * Do 0.9.18 slownikiem byla sama plansza i slowo spoza niej trafialo w najblizszego
 * sasiada: "mature" zabijalo "immature", "stopa" zaliczala "stopy". Podobienstwo liczymy
 * trzema torami rownolegle: po zapisie, po uproszczeniu fonetycznym i po szkielecie
 * spolgloskowym.
 *
 * Zasada bezpieczenstwa: falszywy strzal zabija niewlasciwy cel i utrwala zle
 * skojarzenie, a pudlo kosztuje tylko powtorzenie. Dlatego bramka jest surowa
 * i wymaga przewagi nad drugim kandydatem.
 *
 * Wejsciem dla gry jest [decide]. [match] to starsze wejscie z plaska lista odpowiedzi:
 * nie zna ani celow, ani reszty zestawu i zostaje jako punkt odniesienia dla 55 przypadkow.
 *
 * Scorery sa wspolne z wersja przegladarkowa (matura.uk/demo) - progi zostaly dobrane
 * pomiarem na zestawie 55 przypadkow, patrz VoiceMatchTest. Regul [decide] pilnuje
 * VoiceDecyzjaTest, ktory przechodzi po wszystkich wbudowanych zestawach.
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
    // Wyrazenia stoja tu jako stale, bo przy calym zestawie w roli slownika phon() i skel()
    // ida po kilkuset odpowiedziach na jedna wypowiedz.
    private val NASAL_O = Regex("o[nm](?=[pbtdkgcsz])")
    private val NASAL_E = Regex("e[nm](?=[pbtdkgcsz])")
    private val DOUBLED = Regex("(.)\\1+")
    private val FINAL_B = Regex("b\\b")
    private val FINAL_D = Regex("d\\b")
    private val FINAL_G = Regex("g\\b")
    private val FINAL_W = Regex("w\\b")
    private val FINAL_Z = Regex("z\\b")
    private val FINAL_E = Regex("e\\b")
    private val INITIAL_KN = Regex("\\bkn")
    private val INITIAL_WR = Regex("\\bwr")
    private val VOWELS = Regex("[aeiouy]")

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
            t = FINAL_B.replace(t, "p")
            t = FINAL_D.replace(t, "t")
            t = FINAL_G.replace(t, "k")
            t = FINAL_W.replace(t, "f")
            t = FINAL_Z.replace(t, "s")
        } else {
            t = t.replace("ph", "f").replace("gh", "").replace("ck", "k")
                .replace("qu", "kw").replace("x", "ks")
            t = INITIAL_KN.replace(t, "n")
            t = INITIAL_WR.replace(t, "r")
            t = t.replace("c", "k")
            t = FINAL_E.replace(t, "")
            t = t.replace("w", "u")                       // "hows" = house
        }
        return DOUBLED.replace(t, "$1").trim()
    }

    /** Szkielet spolgloskowy - ratuje trafienia, gdy ASR pomyli same samogloski. */
    fun skel(s: String): String =
        DOUBLED.replace(VOWELS.replace(s, ""), "$1").trim()

    // ---- pisownia brytyjska i amerykanska ----
    //
    // Zestawy sa pisane po brytyjsku, a silnik slucha jako en-US i oddaje "center", "gray",
    // "mom". W slowie na trzy albo cztery litery jedna litera roznicy spada juz ponizej
    // progu, wiec "grey", "cosy" i "mum" nie daly sie w ogole powiedziec. Obie strony
    // sprowadzamy do zapisu amerykanskiego, a rowne po tym zabiegu liczymy jak rowne.
    //
    // Tam, gdzie sam wzorzec lapalby tez zwykle slowa ("flour", "detour", "bestseller",
    // "filled"), regula bierze sie z listy tematow. Slowa spoza list i tak trafiaja
    // przez scorery, tylko juz nie jako dokladne.

    /** Pary bez reguly. Liczba mnoga na "-s" idzie tym samym wpisem. */
    private val AMERICAN_WORDS: Map<String, String> = mapOf(
        "grey" to "gray", "cosy" to "cozy", "tyre" to "tire", "mum" to "mom",
        "pyjama" to "pajama", "programme" to "program", "cheque" to "check",
        "kerb" to "curb", "plough" to "plow", "moustache" to "mustache",
        "yoghurt" to "yogurt", "doughnut" to "donut", "maths" to "math",
        "aluminium" to "aluminum", "jewellery" to "jewelry",
        "storey" to "story", "storeys" to "stories",
        // "-ence" tylko w tych czterech: "science", "sentence" i "fence" zostaja
        "licence" to "license", "defence" to "defense", "offence" to "offense",
        "pretence" to "pretense",
        // czasownik; Amerykanin pisze go tak jak rzeczownik
        "practise" to "practice", "practised" to "practiced", "practising" to "practicing",
    )

    private const val OUR_ENDINGS = "s|ed|ing|ful|less|able|ably|ite|ites|hood|hoods|ly|er|ers|y|al"
    private val OUR = Regex("^(\\w+)our($OUR_ENDINGS)?$")
    private val OUR_STEMS = listOf(
        "col", "fav", "flav", "hon", "hum", "lab", "neighb", "rum", "tum", "behavi", "harb",
        "od", "sav", "savi", "vap", "arm", "vig", "rig", "val", "parl", "endeav", "glam",
        "splend", "cand", "clam", "ferv", "ranc",
    )

    /** "-re" po spolglosce. Po "c" zostaje takze w Ameryce ("acre", "massacre"). */
    private val RE = Regex("^(\\w{2,}[tbgh])re(s?)$")
    private val RE_KEPT = setOf("macabre", "timbre")

    private val ISE = Regex("^(\\w{2,}[^aeiou\\W])is(e|es|ed|ing|er|ers|ation|ations)$")
    /** Slowa na "-ise", ktore nie maja postaci z "z" po zadnej stronie oceanu. */
    private val ISE_KEPT = Regex(
        "(wise|vise|[^aeiou]rise|[^i]cise|advertise|chastise|chemise|compromise|demise|" +
            "despise|expertise|franchise|merchandise|paradise|premise|promise|surmise|treatise)$"
    )
    private val YSE = Regex("^(\\w{3,})ys(e|es|ed|ing|er|ers)$")
    private val OGUE = Regex("^(\\w{2,}[lg])ogue(s?)$")

    private val LL = Regex("^(\\w+l)l(ed|ing|er|ers|or|ors|ous|ist|ists)$")
    private val LL_STEMS = listOf(
        "travel", "cancel", "model", "label", "level", "fuel", "signal", "quarrel",
        "counsel", "marvel", "jewel", "dial", "equal", "total", "tunnel", "channel", "pedal",
        "panel", "towel", "shovel", "cruel", "rival", "medal", "pencil", "council",
        "snorkel", "initial", "duel", "parcel", "barrel", "libel", "funnel", "chisel",
        "marshal", "ravel", "grovel", "shrivel", "swivel", "revel",
    )

    private fun americanWord(w: String): String {
        AMERICAN_WORDS[w]?.let { return it }
        if (w.endsWith("s")) AMERICAN_WORDS[w.dropLast(1)]?.let { return it + "s" }
        if (w.length < 5) return w
        var t = w
        if ("our" in t) OUR.matchEntire(t)?.let { m ->
            val stem = m.groupValues[1]
            if (OUR_STEMS.any { stem.endsWith(it) }) t = stem + "or" + m.groupValues[2]
        }
        if (t.endsWith("re") || t.endsWith("res")) RE.matchEntire(t)?.let { m ->
            if (t.removeSuffix("s") !in RE_KEPT) t = m.groupValues[1] + "er" + m.groupValues[2]
        }
        if ("is" in t) ISE.matchEntire(t)?.let { m ->
            val stem = m.groupValues[1]
            if (!ISE_KEPT.containsMatchIn(stem + "ise")) t = stem + "iz" + m.groupValues[2]
        }
        if ("ys" in t) YSE.matchEntire(t)?.let { m -> t = m.groupValues[1] + "yz" + m.groupValues[2] }
        if ("ogue" in t) OGUE.matchEntire(t)?.let { m -> t = m.groupValues[1] + "og" + m.groupValues[2] }
        if ("ll" in t) LL.matchEntire(t)?.let { m ->
            val stem = m.groupValues[1]
            if (LL_STEMS.any { stem.endsWith(it) }) t = stem + m.groupValues[2]
        }
        return t
    }

    /**
     * Zapis amerykanski znormalizowanego tekstu, slowo po slowie: "shopping centre" ->
     * "shopping center", "grey" -> "gray". Tekst bez brytyjskich form wraca bez zmian.
     * Sluzy WYLACZNIE porownaniu: do gry trafia zawsze zapis z zestawu.
     */
    fun american(s: String): String =
        if (' ' in s) s.split(' ').joinToString(" ") { americanWord(it) } else americanWord(s)

    // ---- zaprzeczenia ----
    //
    // "mature" i "immature" roznia sie dwiema literami na osiem i dziela caly szkielet
    // spolgloskowy, wiec scorery widza w nich prawie to samo slowo, a znacza cos odwrotnego.
    // Slowo z przedrostkiem z tej listy nie trafia w slowo bez niego ani odwrotnie. Lista
    // jest jedna dla obu jezykow, bo zestaw wlasny moze miec dowolny, a poza prawdziwymi
    // zaprzeczeniami regula dotyka tylko par, ktore i tak sa roznymi slowami ("side"
    // i "inside").
    private val NEGATIONS = listOf("nie", "un", "in", "im", "ir", "il", "dis", "non", "anti")

    /**
     * Czy [negated] to zaprzeczenie [plain]: zaczyna sie przedrostkiem, bez ktorego jest do
     * [plain] blizej niz z nim. Reszta nie musi byc identyczna: uczen mowi
     * "nieodpowiedzialna", silnik oddaje "dojrzali", i taka koncowka nie moze przenosic
     * slowa na druga strone zaprzeczenia.
     *
     * "imitation" uslyszane zamiast "invitation" to pomylka w pierwszej sylabie,
     * a "niedojrzali" przy "niedojrzaly" to to samo slowo: bez przedrostka pasuja gorzej,
     * wiec zaprzeczeniami nie sa.
     */
    private fun negates(negated: Form, plain: Form): Boolean {
        val rest = negated.withoutNegation ?: return false
        return rest.key == plain.key || sim(rest.text, plain.text) > sim(negated.text, plain.text)
    }

    /** Najdluzszy kawalek wypowiedzi czytany osobno: odpowiedzi z zestawu sa krotsze. */
    private const val LONGEST_PART = 4

    /**
     * Hipotezy dla [decide]: calosc, sklejka i kazdy kawalek wypowiedzi do [LONGEST_PART]
     * slow. [hypotheses] konczy na parach, wiec "mother in law" powiedziane z rodzajnikiem
     * przegrywalo z para "in law" i trafialo w "in-laws".
     *
     * Kawalek, ktory w wypowiedzi stoi zaraz po zaprzeczeniu, nie jest osobna hipoteza:
     * w "nie lubic" pada tez samo "lubic" i trafialoby dokladnie, chociaz wypowiedz znaczy
     * cos odwrotnego.
     */
    private fun readings(whole: String): List<String> {
        val words = whole.split(' ')
        if (words.size == 1) return listOf(whole)
        val out = LinkedHashSet<String>()
        out.add(whole)
        for (from in words.indices) {
            if (from > 0 && words[from - 1] in NEGATIONS) continue
            var part = words[from]
            out.add(part)
            for (to in from + 1 until minOf(words.size, from + LONGEST_PART)) {
                part += " " + words[to]
                out.add(part)
            }
        }
        out.add(words.joinToString(""))
        return out.toList()
    }

    /**
     * Napis razem z postaciami, na ktorych pracuja scorery. Liczone raz na napis, a nie
     * raz na pare: jedna wypowiedz to kilka hipotez razy wszystkie odpowiedzi zestawu.
     */
    internal class Form(val text: String, val polish: Boolean) {
        /** Po tym kluczu dwa napisy sa TYM SAMYM slowem: po angielsku zapis amerykanski. */
        val key: String = if (polish) text else american(text)
        val phon: String by lazy(LazyThreadSafetyMode.PUBLICATION) { phon(text, polish) }
        val skel: String by lazy(LazyThreadSafetyMode.PUBLICATION) { skel(text) }

        /** Przedrostek zaprzeczenia, od ktorego napis sie zaczyna, pisany lacznie albo osobno. */
        val negation: String = NEGATIONS.firstOrNull { key.length > it.length && key.startsWith(it) } ?: ""

        /** Napis bez tego przedrostka; null, gdy przedrostka nie ma. */
        val withoutNegation: Form? by lazy(LazyThreadSafetyMode.PUBLICATION) {
            val rest = key.substring(negation.length).trimStart()
            if (negation.isEmpty() || rest.isEmpty()) null else Form(rest, polish)
        }
    }

    fun score(hyp: String, cand: String, polish: Boolean): Double =
        score(Form(hyp, polish), Form(cand, polish))

    private fun score(hyp: Form, cand: Form): Double {
        if (hyp.key == cand.key) return 1.0
        if (negates(hyp, cand) || negates(cand, hyp)) return 0.0
        return likeness(hyp, cand)
    }

    /** Trzy scorery: zapis, uproszczenie fonetyczne i szkielet spolgloskowy. */
    private fun likeness(hyp: Form, cand: Form): Double {
        val direct = sim(hyp.text, cand.text)
        // Urwana wypowiedz ("ko" zamiast "kot") jest krotsza od celu. Przy duzej
        // roznicy dlugosci ufamy WYLACZNIE zapisowi - luzniejsze scorery fonetyczne
        // potrafia zrownac prefiks z calym slowem.
        val shorter = minOf(hyp.text.length, cand.text.length)
        if (shorter.toDouble() / maxOf(hyp.text.length, cand.text.length) < 0.6) return direct
        val a = hyp.skel
        val b = cand.skel
        val skeletal = if (a.length >= 3 && b.length >= 3) sim(a, b) * 0.88 else 0.0
        return maxOf(direct, sim(hyp.phon, cand.phon) * 0.96, skeletal)
    }

    /**
     * [score] dla [decide]: wynik, ktory nie moze zmienic zadnej decyzji, wraca jako zero
     * bez liczenia. Przy dlugosciach rozniacych sie bardziej niz 6:10 zostaje sam zapis,
     * a ten nie przekroczy wtedy 0.6. Strzal wymaga co najmniej 0.68, a rywal ponizej 0.62
     * nie odbierze przewagi nawet najkrotszemu slowu. Slownikiem jest caly zestaw, wiec
     * takich par jest wiekszosc.
     */
    private fun scoreThatMatters(hyp: Form, cand: Form): Double {
        if (hyp.key == cand.key) return 1.0
        val shorter = minOf(hyp.text.length, cand.text.length)
        if (shorter.toDouble() / maxOf(hyp.text.length, cand.text.length) < 0.6) return 0.0
        return score(hyp, cand)
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
        val forms = candidates.map { Form(it, polish) }
        var best = Hit(0.0, null)
        var second = Hit(0.0, null)
        for (raw in transcripts) {
            for (hyp in hypotheses(raw)) {
                val heard = Form(hyp, polish)
                for (form in forms) {
                    val cand = form.text
                    val s = score(heard, form)
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

    private fun letters(answer: String): Int = answer.count { it != ' ' }

    /** Od jakiego wyniku odpowiedz o tylu literach w ogole wchodzi w gre. */
    private fun need(letters: Int): Double = when {
        letters <= 3 -> 0.90
        letters <= 4 -> 0.78
        letters <= 5 -> 0.76
        else -> 0.68
    }

    fun accepts(best: Hit, second: Hit): Boolean {
        val answer = best.answer ?: return false
        val len = letters(answer)
        // Krotkie slowa sa z natury do siebie podobne, wiec zamiast zabraniac ich
        // w ogole, wymagamy od nich WIEKSZEJ przewagi nad drugim kandydatem.
        val margin = if (len <= 4) 0.15 else if (len <= 5) 0.10 else 0.06
        if (best.score < need(len)) return false
        if (best.score < 0.999 && (best.score - second.score) < margin) return false
        return true
    }

    // ---- decyzja dla gry ----

    /**
     * Jeden cel: potwor albo skrzynia z planszy, albo wiersz zestawu, ktorego na planszy
     * nie ma. [answers] to WSZYSTKIE warianty, ktore cel przyjmuje, w postaci z
     * [Matching.acceptedAnswers]. Dopiero podzial na cele pozwala odroznic rywala od
     * drugiego zapisu tej samej odpowiedzi ("pewny siebie" i "pewnysiebie").
     */
    data class Target(val id: Long, val answers: List<String>) {
        // Postacie odpowiedzi dla scorerow, osobno na kazdy jezyk nasluchu. Zostaja przy
        // celu, bo liste wierszy zestawu ekran buduje raz na runde: przy kolejnych
        // wypowiedziach nie ma juz czego przeliczac.
        private val polishForms: List<Form> by lazy { answers.map { Form(it, true) } }
        private val englishForms: List<Form> by lazy { answers.map { Form(it, false) } }

        internal fun forms(polish: Boolean): List<Form> = if (polish) polishForms else englishForms
    }

    enum class Reason {
        /** STRZAL: cala wypowiedz jest odpowiedzia celu z planszy. */
        EXACT,
        /** STRZAL: najblizsza odpowiedz stoi na planszy i przeszla bramke. */
        NEAREST,
        /** Cala wypowiedz jest odpowiedzia innego wiersza zestawu, a nie czegos z planszy. */
        OTHER_EXACT,
        /** Najblizej jest odpowiedz wiersza, ktorego na planszy nie ma. */
        OTHER_NEAREST,
        /** Najblizsza odpowiedz jest za daleko. */
        TOO_FAR,
        /** Najblizsza odpowiedz ma za mala przewage nad rywalem. */
        AMBIGUOUS,
        /** Pusta wypowiedz albo pusta plansza. */
        NOTHING,
        /** Wynik czesciowy, ktory jeszcze nie wystarcza: decyduje wynik koncowy. */
        WAIT,
    }

    /**
     * @param answer odpowiedz do wpisania w gre, w zapisie z zestawu; niepusta tylko wtedy,
     *        gdy strzelamy
     * @param targetId cel z planszy, ktory przyjmuje [answer]. Jesli przyjmuje ja kilka
     *        celow, pierwszy z listy; ktory z nich dostanie strzal, rozstrzyga i tak gra.
     * @param score wynik najblizszej odpowiedzi, takze gdy strzalu nie ma. Wynikow, ktore
     *        nie moga zmienic decyzji (ponizej 0.6), nie liczymy: wracaja jako zero.
     * @param nearest najblizsza odpowiedz, takze spoza planszy (diagnostyka)
     * @param rival najlepsza odpowiedz INNEGO celu, od ktorej liczy sie przewaga
     */
    data class Decision(
        val reason: Reason,
        val answer: String? = null,
        val targetId: Long? = null,
        val score: Double = 0.0,
        val nearest: String? = null,
        val rival: Hit = Hit(0.0, null),
    ) {
        val accepted: Boolean get() = answer != null
    }

    /** Wynik czesciowy strzela dopiero od tej pewnosci, zeby nie trafiac w polowie slowa. */
    const val PARTIAL_SCORE = 0.9

    /**
     * Czy wypowiedz ma strzelic i czym.
     *
     * 1. Cala wypowiedz (albo jej sklejka bez spacji), ktora JEST odpowiedzia z zestawu,
     *    rozstrzyga sama. Stoi na planszy: strzal. Nalezy tylko do wiersza spoza planszy:
     *    nie ma strzalu, chocby na planszy stalo cos bardzo podobnego albo jedno ze slow
     *    wypowiedzi ("boxer shorts" przy samym "shorts"). Alternatywy silnika ida po
     *    kolei, bo pierwsza jest najbardziej prawdopodobna.
     * 2. Bez takiej wypowiedzi szukamy najblizszej odpowiedzi w CALYM zestawie. Strzal
     *    pada tylko wtedy, gdy stoi ona na planszy i ma przewage nad rywalem: najlepsza
     *    odpowiedzia, ktorej trafiony cel nie przyjmuje, z planszy albo spoza niej. Drugi
     *    zapis tej samej odpowiedzi rywalem nie jest. Wypowiedz z kilku slow czytamy
     *    najpierw w calosci, a dopiero potem jako jej kawalek z dopowiedzeniem.
     * 3. Wynik czesciowy strzela tylko przy pewnosci [PARTIAL_SCORE] i tylko wtedy, gdy
     *    odpowiedz nie moze byc poczatkiem ani kawalkiem dluzszej odpowiedzi innego celu
     *    ("bluza" czeka, gdy w zestawie jest "bluza sportowa"). Kazdy inny wynik
     *    czesciowy to [Reason.WAIT].
     *
     * Do tego dwie reguly samych scorerow, opisane wyzej przy swoich listach: zaprzeczenie
     * trafia tylko dokladnie, a zapis brytyjski i amerykanski to to samo slowo.
     *
     * @param transcripts alternatywy silnika, od najbardziej prawdopodobnej
     * @param board cele na planszy: kazdy potwor i kazda skrzynia osobno
     * @param others wiersze zestawu po stronie odpowiedzi w jezyku nasluchu. Wystarczy
     *        podac wszystkie: wiersz, ktory akurat stoi na planszy, niczego nie zmienia.
     *        Ich [Target.id] nie jest czytane. Te liste warto zbudowac raz na runde
     *        i podawac te sama, bo cele pamietaja swoje postacie dla scorerow.
     * @param polish jezyk, w ktorym slucha silnik
     * @param isFinal false dla wyniku czesciowego
     */
    fun decide(
        transcripts: List<String>,
        board: List<Target>,
        others: List<Target>,
        polish: Boolean,
        isFinal: Boolean,
    ): Decision {
        val heard = transcripts.map { Matching.normalize(it) }.filter { it.isNotEmpty() }
        if (heard.isEmpty() || board.isEmpty()) return Decision(Reason.NOTHING)
        val vocabulary = Vocabulary(board, others, polish)
        val verdict = vocabulary.said(heard) ?: vocabulary.nearest(heard)
        if (isFinal) return verdict
        val answer = verdict.answer
        val fires = answer != null && verdict.score >= PARTIAL_SCORE && !vocabulary.mayGrow(answer)
        return if (fires) verdict else verdict.copy(reason = Reason.WAIT, answer = null, targetId = null)
    }

    /** Jedna odpowiedz ze slownika i jej najlepsze wyniki dla biezacej wypowiedzi. */
    private class Candidate(val form: Form) {
        val text: String get() = form.text
        val key: String get() = form.key
        /** Cel z planszy, ktory ma dokladnie ten napis wsrod odpowiedzi. */
        var boardId: Long? = null
        /** Najlepszy wynik po wszystkich hipotezach, hipoteza, ktora go dala, i jej wypowiedz. */
        var all = 0.0
        var heardAs = ""
        var utterance = ""
        /** Najlepszy wynik po samej calej wypowiedzi i jej sklejce. */
        var whole = 0.0
    }

    /** Czy [short] jest poczatkiem albo calym slowem dluzszej odpowiedzi [long]. */
    private fun partOf(short: String, long: String): Boolean =
        long.length > short.length && (long.startsWith(short) || " $long ".contains(" $short "))

    /** [phrase] bez slow [part]; null, gdy [part] nie stoi w nim jako cale slowa obok innych. */
    private fun without(phrase: String, part: String): String? {
        val padded = " $phrase "
        val at = padded.indexOf(" $part ")
        if (at < 0 || part.length >= phrase.length) return null
        return (padded.substring(0, at) + padded.substring(at + part.length + 1)).trim()
    }

    /**
     * Od tego podobienstwa dopowiedziane slowo uchodzi za przekrecone slowo dluzszej
     * odpowiedzi: "low" przy "law school" tak, "the" przy "law school" nie.
     */
    private const val SAME_REST = 0.6

    /** Zamkniety slownik jednej decyzji: odpowiedzi planszy i reszty zestawu. */
    private class Vocabulary(board: List<Target>, others: List<Target>, val polish: Boolean) {
        /** W kolejnosci: plansza, potem reszta zestawu. Kazdy napis raz. */
        private val candidates = ArrayList<Candidate>()
        private val byText = HashMap<String, Candidate>()
        /** Pierwsza odpowiedz z planszy o danym kluczu. */
        private val boardByKey = HashMap<String, Candidate>()
        private val firstByKey = HashMap<String, Candidate>()
        /** Klucze odpowiedzi kazdego celu: najpierw cele z planszy, potem reszta zestawu. */
        private val accepted = ArrayList<Set<String>>()
        private val boardSize = board.size

        init {
            for ((nr, target) in (board + others).withIndex()) {
                val keys = HashSet<String>()
                for (form in target.forms(polish)) {
                    if (form.text.isEmpty()) continue
                    val c = byText.getOrPut(form.text) { Candidate(form).also { candidates.add(it) } }
                    keys.add(c.key)
                    firstByKey.putIfAbsent(c.key, c)
                    if (nr < boardSize) {
                        if (c.boardId == null) c.boardId = target.id
                        boardByKey.putIfAbsent(c.key, c)
                    }
                }
                accepted.add(keys)
            }
        }

        private fun keyOf(text: String): String = if (polish) text else american(text)

        /**
         * Cele, ktore przyjmuja odpowiedz o tym kluczu, czyli te, w ktore moze pojsc strzal:
         * z planszy, a gdy na planszy nie przyjmuje jej nikt, to wiersze spoza planszy.
         */
        private fun takers(key: String): List<Set<String>> {
            val out = ArrayList<Set<String>>(2)
            for (nr in 0 until boardSize) if (key in accepted[nr]) out.add(accepted[nr])
            if (out.isEmpty()) {
                for (nr in boardSize until accepted.size) if (key in accepted[nr]) out.add(accepted[nr])
            }
            return out
        }

        /**
         * Czy [c] jest rywalem odpowiedzi, ktora przyjmuja [takers]. Rywalem jest kazda
         * odpowiedz, ktorej choc jeden z tych celow NIE przyjmuje: gdyby uczen powiedzial
         * wlasnie ja, strzal moglby trafic w cel, dla ktorego jest bledna. Drugi zapis tej
         * samej odpowiedzi rywalem wiec nie jest, a odpowiedz sasiada z zestawu jest.
         */
        private fun isRival(c: Candidate, takers: List<Set<String>>): Boolean {
            for (keys in takers) if (c.key !in keys) return true
            return false
        }

        /** Regula 1: cala wypowiedz jest odpowiedzia z zestawu. */
        fun said(heard: List<String>): Decision? {
            for (whole in heard) {
                for (form in linkedSetOf(whole, whole.replace(" ", ""))) {
                    val key = keyOf(form)
                    // Zapis z zestawu ma pierwszenstwo przed samym kluczem: gdy na planszy
                    // stoja "check" i "cheque", uslyszane "check" jest tym pierwszym.
                    val shot = byText[form]?.takeIf { it.boardId != null } ?: boardByKey[key]
                    if (shot != null) {
                        return Decision(Reason.EXACT, shot.text, shot.boardId, 1.0, shot.text)
                    }
                    val other = firstByKey[key]
                    if (other != null) return Decision(Reason.OTHER_EXACT, score = 1.0, nearest = other.text)
                }
            }
            return null
        }

        /** Regula 2: najblizsza odpowiedz w calym zestawie. */
        fun nearest(heard: List<String>): Decision {
            // Alternatywy silnika roznia sie zwykle jednym slowem, wiec wiekszosc hipotez sie
            // powtarza: kazda liczymy przeciw slownikowi raz.
            val rows = HashMap<String, DoubleArray>()
            for (whole in heard) {
                val joined = whole.replace(" ", "")
                for (text in readings(whole)) {
                    val row = rows.getOrPut(text) {
                        val h = Form(text, polish)
                        DoubleArray(candidates.size) { scoreThatMatters(h, candidates[it].form) }
                    }
                    val isWhole = text == whole || text == joined
                    for ((i, c) in candidates.withIndex()) {
                        val s = row[i]
                        if (s > c.all) {
                            c.all = s
                            c.heardAs = text
                            c.utterance = whole
                        }
                        if (isWhole && s > c.whole) c.whole = s
                    }
                }
            }
            val byAll = best { it.all } ?: return Decision(Reason.TOO_FAR)
            // Wypowiedz z kilku slow da sie czytac na dwa sposoby: w calosci albo jako jej
            // kawalek z dopowiedzeniem ("the dog", "pies to"). Calosc ma pierwszenstwo.
            //
            // Najlepsza odpowiedz pasuje do CALEJ wypowiedzi: kawalki wypowiedzi nie sa wtedy
            // nawet rywalami. "zwolnjenie lekarskie" to "zwolnienie lekarskie", choc pada
            // w nim tez prawie dokladne "zwolnienie" z innego wiersza.
            if (byAll.whole >= byAll.all) return judge(byAll) { it.whole }
            // Najlepsza pasuje tylko do jednego ze slow, ale to slowo jest czescia dluzszej
            // odpowiedzi innego celu: w "bluza sportowe" pada dokladne "bluza", a powiedziano
            // "bluza sportowa". Dluzsza przejmuje wypowiedz, gdy reszta wypowiedzi przypomina
            // reszte tej odpowiedzi. Rodzajnik ani wtracenie tego nie robia: "the school"
            // to "school", a nie "law school".
            val takers = takers(byAll.key)
            val longer = best { c -> if (without(c.text, byAll.text) != null && isRival(c, takers)) c.whole else 0.0 }
            if (longer != null && restMatches(longer, byAll)) return judge(longer) { it.whole }
            return judge(byAll) { it.all }
        }

        /** Czy to, co w wypowiedzi stoi obok [word], przypomina to, co stoi obok niego w [longer]. */
        private fun restMatches(longer: Candidate, word: Candidate): Boolean {
            val expected = without(longer.text, word.text) ?: return false
            val heard = without(word.utterance, word.heardAs) ?: return false
            return likeness(Form(heard, polish), Form(expected, polish)) >= SAME_REST
        }

        /**
         * Najlepszy kandydat. Dokladny remis idzie do dluzszej odpowiedzi, a przy rownej
         * dlugosci do wczesniejszej: plansza stoi w slowniku przed reszta zestawu.
         */
        private inline fun best(level: (Candidate) -> Double): Candidate? {
            var top: Candidate? = null
            var topScore = 0.0
            for (c in candidates) {
                val s = level(c)
                if (s <= 0.0) continue
                val t = top
                if (t == null || s > topScore || (s == topScore && c.text.length > t.text.length)) {
                    top = c
                    topScore = s
                }
            }
            return top
        }

        private inline fun judge(winner: Candidate, level: (Candidate) -> Double): Decision {
            val top = Hit(level(winner), winner.text)
            val takers = takers(winner.key)
            var rival = Hit(0.0, null)
            for (c in candidates) {
                val s = level(c)
                if (s > rival.score && isRival(c, takers)) rival = Hit(s, c.text)
            }
            val shot = if (winner.boardId != null) winner else boardByKey[winner.key]
            val reason = when {
                top.score < need(letters(winner.text)) -> Reason.TOO_FAR
                shot == null -> Reason.OTHER_NEAREST
                !accepts(top, rival) -> Reason.AMBIGUOUS
                else -> Reason.NEAREST
            }
            return if (reason == Reason.NEAREST && shot != null) {
                Decision(reason, shot.text, shot.boardId, top.score, winner.text, rival)
            } else {
                Decision(reason, score = top.score, nearest = winner.text, rival = rival)
            }
        }

        /**
         * Regula 3: czy [answer] moze byc dopiero poczatkiem albo kawalkiem dluzszej
         * odpowiedzi innego celu. Drugi wariant tego samego celu sie nie liczy: strzal
         * w "syndykat" jest dobry takze wtedy, gdy uczen dopowie reszte komorki.
         */
        fun mayGrow(answer: String): Boolean {
            val takers = takers(keyOf(answer))
            return candidates.any { partOf(answer, it.text) && isRival(it, takers) }
        }
    }
}
