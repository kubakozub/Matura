package com.matura.app

import com.matura.app.data.BuiltInSets
import com.matura.app.game.Matching
import com.matura.app.game.VoiceMatch
import com.matura.app.game.VoiceMatch.Decision
import com.matura.app.game.VoiceMatch.Reason
import com.matura.app.game.VoiceMatch.Target
import com.matura.app.model.WordSet
import com.matura.app.ui.zaliczycWypowiedz
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Reguly VoiceMatch.decide: slownikiem jest caly zestaw, a strzal pada tylko w to, co stoi
 * na planszy.
 *
 * Przyklady z nazwy pochodza z wbudowanych zestawow i z przegladu 0.9.18, w ktorym stary
 * dopasowywacz (sama plansza w roli slownika) dawal sie wchlonac sasiadowi: po polsku 186
 * hasel przez podobienstwo i 61 przez jedno ze slow wypowiedzi, po angielsku 139 i 62.
 * Na koncu przejscia po calym katalogu: dokladna wypowiedz kazdego hasla, wypowiedz z jedna
 * przekrecona litera, wypowiedz z wtraconym slowem, pisownia brytyjska i losowe wypowiedzi.
 */
class VoiceDecyzjaTest {

    private val catalogue: List<WordSet> = BuiltInSets.all()

    /** Cele dla calego zestawu po jednej stronie. Id celu to numer wiersza. */
    private fun rows(set: WordSet, polish: Boolean): List<Target> = set.entries.mapIndexed { i, e ->
        Target(i.toLong(), Matching.acceptedAnswers(if (polish) e.definition else e.term))
    }

    private class Scene(val board: List<Target>, val others: List<Target>, val polish: Boolean) {
        fun hear(vararg said: String, isFinal: Boolean = true): Decision =
            VoiceMatch.decide(said.toList(), board, others, polish, isFinal)
    }

    /** Plansza z wierszy o podanych haslach; reszta wbudowanego zestawu lezy poza plansza. */
    private fun scene(setId: String, polish: Boolean, vararg onBoard: String): Scene {
        val set = catalogue.first { it.id == setId }
        val all = rows(set, polish)
        val board = onBoard.map { term ->
            val nr = set.entries.indexOfFirst { it.term == term }
            require(nr >= 0) { "w $setId nie ma hasla $term" }
            all[nr]
        }
        return Scene(board, all.filter { it !in board }, polish)
    }

    /** Plansza i reszta zestawu podane wprost, komorka po komorce. */
    private fun custom(polish: Boolean, board: List<String>, others: List<String> = emptyList()): Scene {
        val all = (board + others).mapIndexed { i, cell -> Target(i.toLong(), Matching.acceptedAnswers(cell)) }
        return Scene(all.take(board.size), all.drop(board.size), polish)
    }

    private fun assertShot(answer: String, d: Decision) {
        assertEquals(d.toString(), answer, d.answer)
        assertTrue(d.toString(), d.accepted && d.targetId != null)
        assertTrue(d.toString(), d.reason == Reason.EXACT || d.reason == Reason.NEAREST)
    }

    private fun assertNoShot(reason: Reason, d: Decision) {
        assertNull(d.toString(), d.answer)
        assertNull(d.toString(), d.targetId)
        assertFalse(d.toString(), d.accepted)
        assertEquals(d.toString(), reason, d.reason)
    }

    // =====================================================================
    // 1. Cala wypowiedz, ktora jest odpowiedzia z zestawu, rozstrzyga sama.
    // =====================================================================

    /** Pary, w ktorych do 0.9.18 slowo spoza planszy zabijalo sasiada stojacego na planszy. */
    private data class Neighbour(val set: String, val polish: Boolean, val onBoard: String, val said: String)

    private val neighbours = listOf(
        Neighbour("v000", false, "immature", "mature"),     // 0.88: ten sam szkielet "mtr"
        Neighbour("v010", false, "skirt", "shirt"),         // 0.80
        Neighbour("v001", false, "impatient", "patient"),   // 0.78
        Neighbour("v002", false, "unfriendly", "friendly"), // 0.80
        Neighbour("v000", true, "immature", "dojrzały"),    // 0.73
        Neighbour("v001", true, "impatient", "cierpliwy"),  // 0.75
        Neighbour("v005", true, "feet", "stopa"),           // 0.88
    )

    @Test fun wordOfTheSetThatIsOffTheBoardNeverHitsItsNeighbour() {
        for (p in neighbours) {
            val d = scene(p.set, p.polish, p.onBoard).hear(p.said)
            assertNoShot(Reason.OTHER_EXACT, d)
            assertEquals(Matching.normalize(p.said), d.nearest)
        }
    }

    @Test fun sameWordHitsOnceItsOwnRowIsOnTheBoard() {
        val set = catalogue.first { it.id == "v000" }
        assertShot("mature", scene("v000", false, "immature", "mature").hear("mature"))
        assertShot("dojrzaly", scene("v000", true, "immature", "mature").hear("dojrzały"))
        assertEquals(
            set.entries.indexOfFirst { it.term == "mature" }.toLong(),
            scene("v000", false, "immature", "mature").hear("mature").targetId,
        )
    }

    @Test fun longerAnswerOffTheBoardNeverHitsOneOfItsWords() {
        // do 0.9.18 trafialo dokladnie, przez hipoteze z jednego slowa wypowiedzi
        assertNoShot(Reason.OTHER_EXACT, scene("v009", false, "shorts").hear("boxer shorts"))
        assertNoShot(Reason.OTHER_EXACT, scene("v015", true, "like").hear("nie lubić"))
        assertNoShot(Reason.OTHER_EXACT, scene("v010", true, "hoodie").hear("bluza sportowa"))
        // a gdy stoja oba, wypowiedz trafia w ten cel, ktory nazywa w calosci
        assertShot("boxer shorts", scene("v009", false, "shorts", "boxer shorts").hear("boxer shorts"))
        assertShot("nie lubic", scene("v015", true, "like", "dislike").hear("nie lubić"))
    }

    @Test fun wordSplitByTheRecogniserStillCountsAsTheWholeUtterance() {
        assertShot("dojrzaly", scene("v000", true, "mature").hear("doj rzały"))
        assertNoShot(Reason.OTHER_EXACT, scene("v000", true, "immature").hear("doj rzały"))
    }

    @Test fun firstAlternativeThatIsAWordOfTheSetDecides() {
        val board = scene("v000", false, "immature")
        assertNoShot(Reason.OTHER_EXACT, board.hear("mature", "immature"))
        assertShot("immature", board.hear("immature", "mature"))
        // alternatywa, ktora nie jest zadnym slowem zestawu, niczego nie rozstrzyga
        assertShot("immature", board.hear("imma chur", "immature"))
    }

    @Test fun answerSharedWithARowOnTheBoardIsAccepted() {
        // "can't stand" i "detest" maja w v015 to samo tlumaczenie
        val d = scene("v015", true, "can't stand").hear("nie znosić")
        assertShot("nie znosic", d)
        assertEquals(Reason.EXACT, d.reason)
    }

    // =====================================================================
    // 2. Bez dokladnej wypowiedzi: najblizsza odpowiedz w calym zestawie.
    // =====================================================================

    /** Wypowiedzi, ktore do 0.9.18 przegrywaly z bezspacjowym blizniakiem wlasnego celu. */
    private data class NearMiss(val set: String, val polish: Boolean, val term: String, val said: String, val answer: String)

    private val nearMisses = listOf(
        NearMiss("v000", true, "self-confident", "pewni siebie", "pewny siebie"),
        NearMiss("v000", true, "reliable", "godne zaufania", "godny zaufania"),
        NearMiss("v060", true, "dry cleaner's", "pralnia chemiczne", "pralnia chemiczna"),
        NearMiss("v000", false, "open-minded", "open mindet", "open minded"),
        NearMiss("v035", false, "boarding school", "boarding skool", "boarding school"),
    )

    @Test fun nearMissOfAnAnswerWithASpaceIsAccepted() {
        for (n in nearMisses) {
            val d = scene(n.set, n.polish, n.term).hear(n.said)
            assertShot(n.answer, d)
            assertEquals(Reason.NEAREST, d.reason)
            assertTrue(d.toString(), d.score in 0.85..0.99)
        }
    }

    @Test fun nearMissOfASingleWordIsAcceptedAsBefore() {
        // ten sam stopien pomylki w jednym slowie trafial zawsze, takze z "nieodpowiedzialny"
        // w tym samym zestawie
        val d = scene("v000", true, "responsible").hear("odpowiedzialne")
        assertShot("odpowiedzialny", d)
        assertTrue(d.toString(), d.score > 0.9 && d.rival.score < 0.6)
    }

    @Test fun boardFromTheReviewAcceptsPewniSiebie() {
        val d = scene("v000", true, "self-confident", "mature", "modest").hear("pewni siebie")
        assertShot("pewny siebie", d)
        // rywalem jest inny cel, a nie "pewnysiebie" tego samego
        assertTrue(d.toString(), d.rival.answer != "pewnysiebie" && d.rival.score < 0.6)
    }

    @Test fun apostropheVariantsOfOneTargetAreNotRivals() {
        // "can't stand" daje cztery zapisy jednego celu
        val d = scene("v015", false, "can't stand").hear("cant stant")
        assertShot("cant stand", d)
    }

    @Test fun neighbourOffTheBoardIsARival() {
        // "stope" jest rownie blisko "stopa" i "stopy"
        assertNoShot(Reason.AMBIGUOUS, scene("v005", true, "feet").hear("stope"))
        // gdy slownikiem byla sama plansza, to samo trafialo w "stopy"
        assertShot("stopy", custom(true, board = listOf("stopy")).hear("stope"))
    }

    @Test fun nearestWordOffTheBoardStopsTheShot() {
        val d = scene("v000", true, "immature").hear("dojrzali")
        assertNoShot(Reason.OTHER_NEAREST, d)
        assertEquals("dojrzaly", d.nearest)
    }

    @Test fun targetsThatAcceptTheSameAnswerAreNotRivals() {
        // v036: "staff room" i "staffroom" to dwa wiersze z tym samym tlumaczeniem
        assertShot("staff room", scene("v036", false, "staff room").hear("stbff room"))
        assertShot("staff room", scene("v036", false, "staff room", "staffroom").hear("stbff room"))
        // trzy potwory z ta sama odpowiedzia na planszy
        val three = custom(true, board = listOf("nie znosić", "nie znosić", "nie znosić"), others = listOf("lubić"))
        assertShot("nie znosic", three.hear("nie znosi"))
    }

    @Test fun answerThatOnlySomeTakersAcceptIsARival() {
        // "kotk" jest rownie blisko "kotek" i "kotka". Strzal slowem "kotek" trafi w ktorys
        // z dwoch celow, a drugi z nich nie przyjmuje "kotka".
        assertNoShot(Reason.AMBIGUOUS, custom(true, board = listOf("kotek, kotka", "kotek")).hear("kotk"))
        // jeden cel przyjmuje oba slowa: ktorekolwiek padlo, strzal jest dobry
        assertShot("kotek", custom(true, board = listOf("kotek, kotka")).hear("kotk"))
    }

    @Test fun rowListedBothOnTheBoardAndInTheSetChangesNothing() {
        val set = catalogue.first { it.id == "v000" }
        val all = rows(set, true)
        val board = all.filter { it.answers.first() in setOf("pewny siebie", "dojrzaly", "skromny") }
        for (said in listOf("pewni siebie", "dojrzały", "niedojrzały", "skromna", "pewny", "telefon")) {
            val exact = VoiceMatch.decide(listOf(said), board, all.filter { it !in board }, true, true)
            val whole = VoiceMatch.decide(listOf(said), board, all, true, true)
            assertEquals(said, exact, whole)
        }
    }

    @Test fun wholeUtteranceOutvotesOneOfItsWords() {
        // w "bluza sportowe" pada dokladne "bluza", ale powiedziano "bluza sportowa"
        assertShot("bluza sportowa", scene("v010", true, "hoodie", "sweatshirt").hear("bluza sportowe"))
        assertShot("bluza sportowa", scene("v010", true, "sweatshirt").hear("bluza sportowe"))
        val d = scene("v010", true, "hoodie").hear("bluza sportowe")
        assertNoShot(Reason.OTHER_NEAREST, d)
        assertEquals("bluza sportowa", d.nearest)
    }

    @Test fun fillerNextToTheAnswerDoesNotMakeItALongerAnswer() {
        // "the school" jest prawie tak blisko "law school" jak samo "school" z rodzajnikiem
        assertShot("school", custom(false, board = listOf("school"), others = listOf("law school")).hear("the school"))
        assertShot("school", custom(false, board = listOf("school", "law school")).hear("the school"))
        assertNoShot(Reason.OTHER_NEAREST, custom(false, board = listOf("law school"), others = listOf("school")).hear("the school"))
        assertShot("kuchnia", custom(true, board = listOf("kuchnia"), others = listOf("mała kuchnia")).hear("ta kuchnia"))
        // to samo w zestawach wbudowanych: "tower block", "high ceiling", "christmas eve", "nie lubic"
        assertShot("block", scene("v024", false, "block", "tower block").hear("the block"))
        assertShot("ceiling", scene("v018", false, "ceiling", "high ceiling").hear("it is ceiling"))
        assertShot("christmas", scene("v049", false, "Christmas", "Christmas Eve").hear("christmas the"))
        assertShot("lubic", scene("v015", true, "like", "dislike").hear("no lubić"))
        // wtracenie sklejone z odpowiedzia przypomina inne haslo: "kelner ta" to nie "kelnerka"
        assertShot("kelner", custom(true, board = listOf("kelner", "kelnerka")).hear("kelner ta"))
        assertShot("friend", custom(false, board = listOf("friend", "friendship")).hear("friend it is"))
        // przekrecone slowo dluzszej odpowiedzi to co innego niz wtracenie
        assertNoShot(Reason.OTHER_NEAREST, custom(false, board = listOf("school"), others = listOf("law school")).hear("low school"))
        assertShot("law school", custom(false, board = listOf("school", "law school")).hear("low school"))
    }

    @Test fun threeWordAnswerSaidWithAnArticleIsReadInFull() {
        // hipotezy konczyly sie na parach: "in law" trafialo w "in-laws", a "detached house"
        // przebijalo "semi-detached house"
        val family = scene("v043", false, "mother-in-law", "in-laws")
        assertShot("mother in law", family.hear("the mother in law"))
        assertShot("in laws", family.hear("the in laws"))
        val houses = scene("v024", false, "semi-detached house", "detached house")
        assertShot("semi detached house", houses.hear("a semi detached house"))
        assertShot("detached house", houses.hear("a detached house"))
        assertNoShot(Reason.OTHER_NEAREST, scene("v024", false, "detached house").hear("a semi detached house"))
    }

    @Test fun wordsOfTheUtteranceAreNotRivalsOfItsWholeReading() {
        // v040 ma tez wiersz "zwolnienie", prawie dokladnie obecny w wypowiedzi
        val d = scene("v040", true, "sick leave").hear("zwolnjenie lekarskie")
        assertShot("zwolnienie lekarskie", d)
        assertTrue(d.toString(), d.rival.score < 0.7)
    }

    @Test fun addedWordsStillHit() {
        assertShot("shirt", scene("v010", false, "shirt").hear("a shirt please"))
        assertShot("bluza", scene("v010", true, "hoodie").hear("bluza no"))
        assertShot("koszula", scene("v010", true, "shirt").hear("to jest koszula"))
    }

    @Test fun wordOutsideTheSetIsTooFar() {
        assertNoShot(Reason.TOO_FAR, scene("v000", true, "mature").hear("telefon"))
        assertNoShot(Reason.TOO_FAR, scene("v000", false, "mature").hear("elephant"))
    }

    @Test fun nothingHeardOrNothingOnTheBoard() {
        assertNoShot(Reason.NOTHING, scene("v000", true, "mature").hear())
        assertNoShot(Reason.NOTHING, scene("v000", true, "mature").hear("", " . "))
        assertNoShot(Reason.NOTHING, scene("v000", true).hear("dojrzały"))
    }

    // =====================================================================
    // 3. Zaprzeczenie trafia tylko dokladnie.
    // =====================================================================

    private val opposites = listOf(
        Triple("mature", "immature", false), Triple("patient", "impatient", false),
        Triple("friendly", "unfriendly", false), Triple("like", "dislike", false),
        Triple("responsible", "irresponsible", false), Triple("legal", "illegal", false),
        Triple("correct", "incorrect", false), Triple("smoker", "non-smoker", false),
        Triple("social", "anti-social", false),
        Triple("dojrzały", "niedojrzały", true), Triple("cierpliwy", "niecierpliwy", true),
        Triple("lubić", "nie lubić", true), Triple("znosić", "nie znosić", true),
        Triple("ma", "nie ma", true),
    )

    @Test fun oppositesNeverMatchWhenOnlyOneIsInTheSet() {
        // Bez drugiego slowa w zestawie regula 1 nie pomoze: zostaje sam zakaz.
        for ((plain, negated, polish) in opposites) {
            val saidPlain = custom(polish, board = listOf(negated)).hear(plain)
            assertFalse("$plain -> $negated: $saidPlain", saidPlain.accepted)
            val saidNegated = custom(polish, board = listOf(plain)).hear(negated)
            assertFalse("$negated -> $plain: $saidNegated", saidNegated.accepted)
            val a = Matching.normalize(plain)
            val b = Matching.normalize(negated)
            assertEquals("$plain / $negated", 0.0, VoiceMatch.score(a, b, polish), 0.0)
            assertEquals("$negated / $plain", 0.0, VoiceMatch.score(b, a, polish), 0.0)
            assertFalse(VoiceMatch.match(listOf(plain), listOf(b), polish).accepted)
        }
    }

    @Test fun inflectedOrGarbledOppositeNeverMatchesEither() {
        // do 0.9.18 kazda z tych par trafiala: przedrostek to dwie albo trzy litery dlugiego slowa
        val pairs = listOf(
            Triple("nieodpowiedzialna", "odpowiedzialny", true),
            Triple("odpowiedzialna", "nieodpowiedzialny", true),
            Triple("dojrzali", "niedojrzały", true),
            Triple("nie zgadzać siem", "zgadzać się", true),
            Triple("matura", "immature", false),
            Triple("unfriendli", "friendly", false),
            Triple("friendli", "unfriendly", false),
        )
        for ((said, onBoard, polish) in pairs) {
            val d = custom(polish, board = listOf(onBoard)).hear(said)
            assertFalse("$said -> $onBoard: $d", d.accepted)
        }
        // dopoki przedrostek jest po obu stronach, to zwykla pomylka w slowie
        assertShot("nieodpowiedzialny", custom(true, board = listOf("nieodpowiedzialny")).hear("nieodpowiedzialna"))
        assertShot("interesting", custom(false, board = listOf("interesting")).hear("interestin"))
        assertShot("understand", custom(false, board = listOf("understand")).hear("understant"))
        // pomylka w pierwszej sylabie to nie zaprzeczenie: bez "im" slowo pasuje gorzej
        assertShot("invitation", custom(false, board = listOf("invitation")).hear("imitation"))
        // slowo, ktore samo zaczyna sie jak zaprzeczenie, tez mozna zaprzeczyc
        assertFalse(custom(true, board = listOf("niemiecki")).hear("nie niemiecki").accepted)
        assertShot("niemiecki", custom(true, board = listOf("niemiecki")).hear("niemiecka"))
    }

    @Test fun negatedUtteranceNeverHitsThePlainWordThroughOneOfItsWords() {
        // w "nie lubic" pada dokladne "lubic", ale wypowiedz znaczy cos odwrotnego
        assertFalse(custom(true, board = listOf("lubić")).hear("nie lubić").accepted)
        assertFalse(custom(true, board = listOf("lubić")).hear("no nie lubić").accepted)
        assertFalse(custom(false, board = listOf("smoker")).hear("non smoker").accepted)
        assertFalse(custom(false, board = listOf("happy")).hear("un happy").accepted)
        // takze wtedy, gdy silnik przekreci slowo po zaprzeczeniu
        assertFalse(custom(true, board = listOf("lubić")).hear("nie lubik").accepted)
        assertFalse(custom(true, board = listOf("zgadzać się")).hear("nie zgadzać siem").accepted)
        assertFalse(custom(false, board = listOf("happy")).hear("un hapy").accepted)
        // slowo sprzed zaprzeczenia zostaje soba
        assertShot("lubic", custom(true, board = listOf("lubić")).hear("lubić nie"))
        // zwykle dopowiedzenie zaprzeczeniem nie jest
        assertShot("lubic", custom(true, board = listOf("lubić")).hear("no lubić"))
        assertShot("happy", custom(false, board = listOf("happy")).hear("so happy"))
    }

    @Test fun negatedWordIsStillHitExactlyAndNearly() {
        assertShot("immature", custom(false, board = listOf("immature"), others = listOf("mature")).hear("immature"))
        assertShot("nie lubic", custom(true, board = listOf("nie lubić"), others = listOf("lubić")).hear("nie lubić"))
        // przekrecona koncowka nie robi z zaprzeczenia innego slowa
        assertShot("niedojrzaly", custom(true, board = listOf("niedojrzały"), others = listOf("dojrzały")).hear("niedojrzali"))
        assertShot("unfriendly", custom(false, board = listOf("unfriendly"), others = listOf("friendly")).hear("unfriendli"))
    }

    // =====================================================================
    // 4. Dokladny remis idzie do dluzszej odpowiedzi.
    // =====================================================================

    @Test fun exactTieGoesToTheLongestAnswer() {
        assertShot("ogrod", custom(true, board = listOf("dom", "ogród")).hear("dom ogród"))
        assertShot("ogrod", custom(true, board = listOf("ogród", "dom")).hear("ogród dom"))
        assertShot("boxer shorts", scene("v009", false, "shorts", "boxer shorts").hear("the boxer shorts"))
        // dluzsza lezy poza plansza: krotsza nie strzela
        assertNoShot(Reason.OTHER_NEAREST, scene("v009", false, "shorts").hear("the boxer shorts"))
    }

    // =====================================================================
    // 5. Wynik czesciowy.
    // =====================================================================

    @Test fun partialWaitsWhileTheAnswerMayStillGrow() {
        // "bluza" to poczatek "bluza sportowa" z innego wiersza
        val hoodie = scene("v010", true, "hoodie")
        assertNoShot(Reason.WAIT, hoodie.hear("bluza", isFinal = false))
        assertShot("bluza", hoodie.hear("bluza"))
        val both = scene("v010", true, "hoodie", "sweatshirt")
        assertNoShot(Reason.WAIT, both.hear("bluza", isFinal = false))
        assertShot("bluza sportowa", both.hear("bluza sportowa", isFinal = false))
        // "shorts" to ostatnie slowo "boxer shorts"
        assertNoShot(Reason.WAIT, scene("v009", false, "shorts").hear("shorts", isFinal = false))
        assertShot("shorts", scene("v009", false, "shorts").hear("shorts"))
        // silnik pokazuje czasem pol slowa: "kot" moze byc poczatkiem "kotek"
        val cats = custom(true, board = listOf("kot"), others = listOf("kotek"))
        assertNoShot(Reason.WAIT, cats.hear("kot", isFinal = false))
        assertShot("kot", cats.hear("kot"))
    }

    @Test fun partialFiresWhenNothingLongerStartsWithIt() {
        assertShot("dojrzaly", scene("v000", true, "mature").hear("dojrzały", isFinal = false))
        assertShot("skirt", scene("v010", false, "skirt").hear("skirt", isFinal = false))
    }

    @Test fun partialNeedsNinetyPercent() {
        val board = scene("v000", true, "self-confident")
        val sure = board.hear("pewni siebie", isFinal = false)
        assertShot("pewny siebie", sure)
        assertTrue(sure.toString(), sure.score >= VoiceMatch.PARTIAL_SCORE)
        val unsure = board.hear("pewni sibie", isFinal = false)
        assertNoShot(Reason.WAIT, unsure)
        assertTrue(unsure.toString(), unsure.score < VoiceMatch.PARTIAL_SCORE)
        assertShot("pewny siebie", board.hear("pewni sibie"))
    }

    @Test fun longerVariantOfTheSameTargetDoesNotDelay() {
        // komorka z dopowiedzeniem: "syndykat" i "syndykat zwiazek przedsiebiorstw" to jeden cel
        val gloss = custom(true, board = listOf("syndykat (związek przedsiębiorstw)"), others = listOf("spółka"))
        assertShot("syndykat", gloss.hear("syndykat", isFinal = false))
    }

    @Test fun partialThatIsNotAShotIsAlwaysWait() {
        val board = scene("v000", false, "immature")
        assertNoShot(Reason.WAIT, board.hear("mature", isFinal = false))
        assertNoShot(Reason.WAIT, board.hear("elephant", isFinal = false))
        assertNoShot(Reason.OTHER_EXACT, board.hear("mature"))
    }

    /** Scenariusze z przegladu: wynik czesciowy i koncowy jednej wypowiedzi strzelaly osobno. */
    @Test fun oneUtteranceShootsOnce() {
        // "bluza sportowa": czesciowe "bluza" czeka, wiec strzela dopiero calosc i tylko raz
        val clothes = scene("v010", true, "hoodie", "sweatshirt")
        assertNoShot(Reason.WAIT, clothes.hear("bluza", isFinal = false))
        assertShot("bluza sportowa", clothes.hear("bluza sportowa"))

        // "dojrzaly": czesciowy wynik strzela, a koncowy trafia juz na plansze bez tego potwora
        val before = scene("v000", true, "mature", "immature")
        val partial = before.hear("dojrzały", isFinal = false)
        assertShot("dojrzaly", partial)
        assertTrue(zaliczycWypowiedz("dojrzaly", "", 7, 0))
        val after = scene("v000", true, "immature")
        assertNoShot(Reason.OTHER_EXACT, after.hear("dojrzały"))
        // i nawet gdyby dopasowanie cos przyjelo, ta wypowiedz juz swoje zaliczyla
        assertFalse(zaliczycWypowiedz("niedojrzaly", "dojrzaly", 7, 7))
    }

    // =====================================================================
    // 6. Pisownia brytyjska w zestawie, amerykanska z silnika.
    // =====================================================================

    private val spellings = listOf(
        "centre" to "center", "metre" to "meter", "litre" to "liter", "grey" to "gray",
        "cosy" to "cozy", "tyre" to "tire", "mum" to "mom",
        "colour" to "color", "neighbourhood" to "neighborhood", "favourite" to "favorite",
        "theatre" to "theater", "kilometres" to "kilometers", "fibre" to "fiber",
        "memorise" to "memorize", "organised" to "organized", "organisation" to "organization",
        "analyse" to "analyze", "dialogue" to "dialog", "catalogue" to "catalog",
        "travelling" to "traveling", "cancelled" to "canceled", "jeweller" to "jeweler",
        "licence" to "license", "defence" to "defense", "offence" to "offense",
        "pyjamas" to "pajamas", "programme" to "program", "cheque" to "check",
        "kerb" to "curb", "plough" to "plow", "moustache" to "mustache",
        "yoghurt" to "yogurt", "doughnut" to "donut", "maths" to "math",
        "aluminium" to "aluminum", "jewellery" to "jewelry", "storey" to "story",
        "practise" to "practice", "tyres" to "tires",
        "shopping centre" to "shopping center", "grey hair" to "gray hair",
    )

    @Test fun britishAnswerIsHeardInAmericanSpelling() {
        for ((british, americanSpelling) in spellings) {
            val board = custom(false, board = listOf(british), others = listOf("window"))
            for (isFinal in listOf(true, false)) {
                val d = board.hear(americanSpelling, isFinal = isFinal)
                // do gry idzie zapis z zestawu, nie ten z silnika
                assertShot(british, d)
                assertEquals("$americanSpelling: $d", Reason.EXACT, d.reason)
            }
            assertEquals(british, 1.0, VoiceMatch.score(americanSpelling, british, false), 0.0)
            assertEquals(british, americanSpelling, VoiceMatch.american(british))
            assertEquals(americanSpelling, americanSpelling, VoiceMatch.american(americanSpelling))
        }
    }

    @Test fun shortBritishWordsWereOutOfReachBefore() {
        // jedna litera roznicy w slowie na trzy albo cztery litery nie przechodzi progu
        for ((british, americanSpelling) in listOf("grey" to "gray", "cosy" to "cozy", "tyre" to "tire", "mum" to "mom")) {
            assertTrue(british, VoiceMatch.sim(americanSpelling, british) < 0.78)
            assertTrue(VoiceMatch.match(listOf(americanSpelling), listOf(british, "window"), false).accepted)
        }
    }

    @Test fun spellingFoldLeavesOrdinaryWordsAlone() {
        val same = listOf(
            // "our", ktore nie jest brytyjska koncowka
            "our", "hour", "four", "your", "tour", "sour", "pour", "flour", "detour", "contour",
            "court", "course", "journey", "tourist", "rushhour",
            // "re" po samoglosce albo po "c"
            "fire", "culture", "furniture", "store", "acre", "massacre", "genre", "timbre", "macabre",
            // "ise", ktore nigdy nie ma "z"
            "exercise", "promise", "surprise", "surprised", "advertise", "advise", "wise", "rise",
            "sunrise", "payrise", "otherwise", "noise", "cruise", "bruise", "precise", "size", "prize",
            // podwojne "l" takze w Ameryce
            "bestseller", "thriller", "called", "filled", "filling", "smaller", "gallery",
            "controlled", "cancellation",
            "rogue", "vogue", "tongue", "colleague", "science", "sentence", "fence", "audience",
            // zapis amerykanski zostaje soba
            "center", "color", "gray", "check", "story", "program", "math", "traveling",
        )
        for (word in same) assertEquals(word, word, VoiceMatch.american(word))
    }

    @Test fun polishIsNeverFolded() {
        assertTrue(VoiceMatch.score("center", "centre", true) < 1.0)
        assertTrue(VoiceMatch.score("gray", "grey", true) < 0.78)
        assertFalse(custom(true, board = listOf("grey")).hear("gray").accepted)
    }

    @Test fun spellingFromTheSetWinsOverTheFoldedOne() {
        // "check" i "cheque" brzmia tak samo; silnik zawsze odda "check"
        val both = custom(false, board = listOf("cheque", "check"))
        assertShot("check", both.hear("check"))
        assertEquals(1L, both.hear("check").targetId)
        assertShot("cheque", both.hear("cheque"))
        // sam "cheque" na planszy: inaczej nie daloby sie go powiedziec wcale
        assertShot("cheque", custom(false, board = listOf("cheque"), others = listOf("check")).hear("check"))
        assertNoShot(Reason.OTHER_EXACT, custom(false, board = listOf("window"), others = listOf("cheque")).hear("check"))
    }

    @Test fun foldNeverMergesTwoTermsOfOneBuiltInSet() {
        val merged = ArrayList<String>()
        for (set in catalogue) {
            // klucz -> wiersze, ktore maja odpowiedz o tym kluczu, z ich zapisem
            val byKey = HashMap<String, MutableMap<Int, String>>()
            for ((nr, e) in set.entries.withIndex()) {
                for (answer in Matching.acceptedAnswers(e.term)) {
                    byKey.getOrPut(VoiceMatch.american(answer)) { HashMap() }.putIfAbsent(nr, answer)
                }
            }
            for ((key, owners) in byKey) {
                if (owners.values.toSet().size > 1) merged.add("${set.id}: ${owners.values.toSet()} -> $key")
            }
        }
        assertTrue("rozne hasla jednego zestawu z tym samym zapisem amerykanskim: $merged", merged.isEmpty())
    }

    @Test fun builtInTermsThatTheFoldChanges() {
        val changed = ArrayList<String>()
        for (set in catalogue) {
            for (e in set.entries) {
                val term = Matching.normalize(e.term)
                val folded = VoiceMatch.american(term)
                if (folded != term) changed.add("${set.id} $term -> $folded")
            }
        }
        println("pisownia brytyjska w katalogu (${changed.size}): $changed")
        assertEquals(
            "Hasla katalogu z innym zapisem amerykanskim. Po zmianie slownictwa przeczytaj liste " +
                "i popraw liczbe: $changed",
            18, changed.size,
        )
    }

    // =====================================================================
    // 7. Caly katalog: dokladna wypowiedz kazdego hasla.
    // =====================================================================

    @Test fun catalogueSweep_exactTranscriptHitsOwnRowAndNoOther() {
        for (polish in listOf(true, false)) {
            var said = 0
            val falseRejects = ArrayList<String>()
            val falseAccepts = ArrayList<String>()
            for (set in catalogue) {
                val all = rows(set, polish)
                for (own in all) {
                    val rest = all.filter { it !== own }
                    for (answer in own.answers) {
                        said++
                        // sam na planszy, reszta zestawu poza nia
                        val alone = VoiceMatch.decide(listOf(answer), listOf(own), rest, polish, true)
                        if (alone.targetId != own.id || alone.answer !in own.answers) {
                            falseRejects.add("${set.id} '$answer' sam na planszy: $alone")
                        }
                        // caly zestaw na planszy
                        val full = VoiceMatch.decide(listOf(answer), all, emptyList(), polish, true)
                        if (full.answer == null || full.answer !in own.answers) {
                            falseRejects.add("${set.id} '$answer' caly zestaw na planszy: $full")
                        }
                        // wlasnego wiersza NIE ma na planszy: cala reszta naraz i po piec
                        for (board in listOf(rest) + rest.chunked(5)) {
                            val d = VoiceMatch.decide(listOf(answer), board, all.filter { it !in board }, polish, true)
                            val hit = d.targetId?.let { id -> all[id.toInt()] } ?: continue
                            if (answer !in hit.answers) falseAccepts.add("${set.id} '$answer' -> $d")
                        }
                    }
                }
            }
            println("katalog, polski=$polish: wypowiedzi=$said falszywe pudla=${falseRejects.size} falszywe strzaly=${falseAccepts.size}")
            assertTrue("falszywe pudla (polski=$polish): ${falseRejects.take(20)}", falseRejects.isEmpty())
            assertTrue("falszywe strzaly (polski=$polish): ${falseAccepts.take(20)}", falseAccepts.isEmpty())
            assertTrue(said > 2084)
        }
    }

    // =====================================================================
    // 8. Caly katalog: przekrecona litera w odpowiedzi z kilku slow i wtracone slowo.
    // =====================================================================

    /** Odpowiedz z jedna litera zmieniona w srodku najdluzszego slowa ("pewny" -> "pexny"). */
    private fun oneLetterOff(answer: String): String {
        val words = answer.split(' ').toMutableList()
        val nr = words.indices.maxByOrNull { words[it].length }!!
        val word = words[nr]
        val at = word.length / 2
        val other = when (val ch = word[at]) { 'z' -> 'a'; '9' -> '0'; else -> ch + 1 }
        words[nr] = word.substring(0, at) + other + word.substring(at + 1)
        return words.joinToString(" ")
    }

    @Test fun noisySweep_oneLetterOffStillHitsOwnRowAndNoOther() {
        // Zmierzone: po polsku 327 z 327, po angielsku 275 z 277 ("mowe in" i "mowe out"
        // sa blizej wiersza "mow"). W 0.9.18 trafialo 8 z 327 i 0 z 277, a gdy wlasnego
        // wiersza nie bylo na planszy, 74 i 66 takich wypowiedzi strzelaly w inny.
        val allowedMisses = mapOf(true to 3, false to 5)
        for (polish in listOf(true, false)) {
            var tried = 0
            val misses = ArrayList<String>()
            val wrong = ArrayList<String>()
            for (set in catalogue) {
                val all = rows(set, polish)
                for (own in all) {
                    val primary = own.answers.first()
                    if (' ' !in primary) continue
                    tried++
                    val said = oneLetterOff(primary)
                    val rest = all.filter { it !== own }
                    val alone = VoiceMatch.decide(listOf(said), listOf(own), rest, polish, true)
                    if (!alone.accepted) misses.add("${set.id} '$said': ${alone.reason} ${alone.nearest}")
                    // wlasnego wiersza nie ma na planszy, potem caly zestaw na planszy:
                    // strzal wolno oddac tylko odpowiedzia, ktora ten wiersz przyjmuje
                    for (board in listOf(rest, all)) {
                        val d = VoiceMatch.decide(listOf(said), board, all.filter { it !in board }, polish, true)
                        val answer = d.answer ?: continue
                        if (answer !in own.answers) wrong.add("${set.id} '$said' -> $d")
                    }
                }
            }
            val hit = tried - misses.size
            println("przekrecona litera, polski=$polish: trafione $hit z $tried, pudla: $misses")
            assertTrue("strzal w inny wiersz (polski=$polish): ${wrong.take(20)}", wrong.isEmpty())
            assertTrue("polski=$polish: trafione $hit z $tried", hit >= tried * 0.9)
            assertTrue(
                "polski=$polish: wiecej pudel niz zmierzono: $misses",
                misses.size <= allowedMisses.getValue(polish),
            )
            assertTrue(tried > 250)
        }
    }

    @Test fun fillerSweep_wordAddedBeforeOrAfterStillHitsOwnRowAndNoOther() {
        // Zmierzone: kazda z 2084 odpowiedzi trafia z kazdym z tych wtracen, przed i po.
        // W 0.9.18 przy calym zestawie na planszy w inny wiersz strzelalo 488 takich
        // wypowiedzi po polsku i 498 po angielsku (z 16672).
        val fillers = mapOf(true to listOf("to", "no", "ta", "jest"), false to listOf("the", "a", "an", "to"))
        for (polish in listOf(true, false)) {
            var tried = 0
            val misses = ArrayList<String>()
            val wrong = ArrayList<String>()
            for (set in catalogue) {
                val all = rows(set, polish)
                for (own in all) {
                    val rest = all.filter { it !== own }
                    for (filler in fillers.getValue(polish)) {
                        for (said in listOf(filler + " " + own.answers.first(), own.answers.first() + " " + filler)) {
                            tried++
                            val alone = VoiceMatch.decide(listOf(said), listOf(own), rest, polish, true)
                            if (!alone.accepted) misses.add("${set.id} '$said': ${alone.reason} ${alone.nearest}")
                            val full = VoiceMatch.decide(listOf(said), all, emptyList(), polish, true)
                            val answer = full.answer ?: continue
                            if (answer !in own.answers) wrong.add("${set.id} '$said' -> $full")
                        }
                    }
                }
            }
            println("wtracenia, polski=$polish: trafione ${tried - misses.size} z $tried, pudla: ${misses.take(20)}")
            assertTrue("strzal w inny wiersz (polski=$polish): ${wrong.take(20)}", wrong.isEmpty())
            assertTrue("polski=$polish: wiecej pudel niz zmierzono: ${misses.take(20)}", misses.size <= 4)
        }
    }

    // =====================================================================
    // 9. Wlasnosci, ktore maja zachodzic dla kazdej wypowiedzi.
    // =====================================================================

    /** Losowa odpowiedz z zestawu, czasem przekrecona, czasem z dopowiedzianym slowem. */
    private fun utterance(all: List<Target>, random: Random): List<String> {
        val source = all[random.nextInt(all.size)].answers.let { it[random.nextInt(it.size)] }
        var said = source
        repeat(random.nextInt(3)) {
            if (said.length > 1) {
                val at = random.nextInt(said.length)
                val letter = 'a' + random.nextInt(26)
                said = when (random.nextInt(3)) {
                    0 -> said.substring(0, at) + letter + said.substring(at + 1)
                    1 -> said.substring(0, at) + said.substring(at + 1)
                    else -> said.substring(0, at) + letter + said.substring(at)
                }
            }
        }
        val fillers = listOf("the", "a", "to", "nie", "no", "in", "un", "yyy", "please", "to jest")
        if (random.nextInt(4) == 0) said = fillers[random.nextInt(fillers.size)] + " " + said
        if (random.nextInt(8) == 0) said = said + " " + fillers[random.nextInt(fillers.size)]
        return if (random.nextInt(3) == 0) listOf(said, source) else listOf(said)
    }

    @Test fun randomUtterances_restOfTheSetOnlyEverTakesShotsAway() {
        val random = Random(20261120)
        var cases = 0
        var shots = 0
        val broken = ArrayList<String>()
        for (polish in listOf(true, false)) {
            for (set in catalogue) {
                val all = rows(set, polish)
                repeat(60) {
                    val board = all.shuffled(random).take(1 + random.nextInt(6))
                    val others = all.filter { it !in board }
                    val said = utterance(all, random)
                    val what = "${set.id} $said plansza=${board.map { it.answers.first() }}"
                    val final = VoiceMatch.decide(said, board, others, polish, true)
                    val boardOnly = VoiceMatch.decide(said, board, emptyList(), polish, true)
                    val partial = VoiceMatch.decide(said, board, others, polish, false)
                    cases++
                    // wynik czesciowy strzela tylko tym, czym strzelilby koncowy
                    if (partial.accepted && partial.answer != final.answer) broken.add("czesciowy: $what $partial")
                    if (!partial.accepted && partial.reason != Reason.WAIT) broken.add("powod: $what $partial")
                    if (!final.accepted) {
                        if (final.targetId != null || final.reason == Reason.EXACT || final.reason == Reason.NEAREST) {
                            broken.add("pola: $what $final")
                        }
                        return@repeat
                    }
                    shots++
                    // strzal idzie w cel z planszy, ktory te odpowiedz przyjmuje
                    val target = board.firstOrNull { it.id == final.targetId }
                    if (target == null || final.answer !in target.answers) broken.add("cel: $what $final")
                    // wiersze spoza planszy nie tworza strzalu, ktorego bez nich by nie bylo,
                    // ani nie przenosza go na inny cel
                    val alone = boardOnly.answer
                    if (alone == null) {
                        broken.add("nowy strzal: $what $final")
                    } else if (board.none { final.answer in it.answers && alone in it.answers }) {
                        broken.add("inny cel: $what $final, sama plansza: $boardOnly")
                    }
                }
            }
        }
        println("losowe wypowiedzi: $cases, strzalow: $shots")
        assertTrue("zlamane wlasnosci: ${broken.take(20)}", broken.isEmpty())
        assertTrue("za malo strzalow, zeby test cos sprawdzal: $shots z $cases", shots > cases / 10)
    }
}
