package org.lara.experiment.rag

/** Porter (1980) stemmer, as SQLite FTS5's `porter` tokenizer applies it; tokens under 3 chars pass through. */
object Porter {
    fun stem(word: String): String {
        if (word.length < 3 || word.length > 64) return word
        return Stemmer(word).run()
    }

    private class Stemmer(w: String) {
        private val b = StringBuilder(w)
        private var j = 0

        private fun cons(i: Int): Boolean = when (b[i]) {
            'a', 'e', 'i', 'o', 'u' -> false
            'y' -> if (i == 0) true else !cons(i - 1)
            else -> true
        }

        /** Number of VC sequences in b[0..j]. */
        private fun m(): Int {
            var n = 0
            var i = 0
            while (true) { if (i > j) return n; if (!cons(i)) break; i++ }
            i++
            while (true) {
                while (true) { if (i > j) return n; if (cons(i)) break; i++ }
                i++; n++
                while (true) { if (i > j) return n; if (!cons(i)) break; i++ }
                i++
            }
        }

        private fun vowelInStem(): Boolean { for (i in 0..j) if (!cons(i)) return true; return false }
        private fun doubleC(i: Int) = i >= 1 && b[i] == b[i - 1] && cons(i)
        private fun cvc(i: Int): Boolean {
            if (i < 2 || !cons(i) || cons(i - 1) || !cons(i - 2)) return false
            return b[i] != 'w' && b[i] != 'x' && b[i] != 'y'
        }

        private fun ends(s: String): Boolean {
            if (s.length > b.length || !b.endsWith(s)) return false
            j = b.length - s.length - 1
            return true
        }

        private fun setTo(s: String) { b.setLength(j + 1); b.append(s) }
        private fun r(s: String) { if (m() > 0) setTo(s) }

        fun run(): String {
            step1ab(); if (b.length > 1) { step1c(); step2(); step3(); step4(); step5() }
            return b.toString()
        }

        private fun step1ab() {
            if (b.last() == 's') {
                // SQLite's own rule: "sses" and "ies" lose two letters only when longer than 4 / 3 chars ("ies" -> "ie")
                val n = b.length
                if (b[n - 2] == 'e') {
                    if ((n > 4 && b[n - 4] == 's' && b[n - 3] == 's') || (n > 3 && b[n - 3] == 'i')) b.setLength(n - 2) else b.setLength(n - 1)
                } else if (b[n - 2] != 's') b.setLength(n - 1)
            }
            if (ends("eed")) { if (m() > 0) b.setLength(b.length - 1) }
            else if ((ends("ed") || ends("ing")) && vowelInStem()) {
                b.setLength(j + 1)
                if (ends("at")) setTo("ate") else if (ends("bl")) setTo("ble") else if (ends("iz")) setTo("ize")
                else if (doubleC(b.length - 1)) {
                    val c = b.last()
                    if (c != 'l' && c != 's' && c != 'z') b.setLength(b.length - 1)
                } else { j = b.length - 1; if (m() == 1 && cvc(b.length - 1)) b.append('e') }
            }
        }

        private fun step1c() { if (ends("y") && vowelInStem()) b[b.length - 1] = 'i' }

        private fun step2() {
            if (b.length < 3) return
            when (b[b.length - 2]) {
                'a' -> { if (ends("ational")) r("ate") else if (ends("tional")) r("tion") }
                'c' -> { if (ends("enci")) r("ence") else if (ends("anci")) r("ance") }
                'e' -> { if (ends("izer")) r("ize") }
                'l' -> { if (ends("bli")) r("ble") else if (ends("alli")) r("al") else if (ends("entli")) r("ent")
                    else if (ends("eli")) r("e") else if (ends("ousli")) r("ous") }
                'o' -> { if (ends("ization")) r("ize") else if (ends("ation")) r("ate") else if (ends("ator")) r("ate") }
                's' -> { if (ends("alism")) r("al") else if (ends("iveness")) r("ive") else if (ends("fulness")) r("ful")
                    else if (ends("ousness")) r("ous") }
                't' -> { if (ends("aliti")) r("al") else if (ends("iviti")) r("ive") else if (ends("biliti")) r("ble") }
                'g' -> { if (ends("logi")) r("log") }
            }
        }

        private fun step3() {
            when (b.last()) {
                'e' -> { if (ends("icate")) r("ic") else if (ends("ative")) r("") else if (ends("alize")) r("al") }
                'i' -> { if (ends("iciti")) r("ic") }
                'l' -> { if (ends("ical")) r("ic") else if (ends("ful")) r("") }
                's' -> { if (ends("ness")) r("") }
            }
        }

        private fun step4() {
            if (b.length < 3) return
            val hit = when (b[b.length - 2]) {
                'a' -> ends("al")
                'c' -> ends("ance") || ends("ence")
                'e' -> ends("er")
                'i' -> ends("ic")
                'l' -> ends("able") || ends("ible")
                'n' -> ends("ant") || ends("ement") || ends("ment") || ends("ent")
                'o' -> (ends("ion") && j >= 0 && (b[j] == 's' || b[j] == 't')) || ends("ou")
                's' -> ends("ism")
                't' -> ends("ate") || ends("iti")
                'u' -> ends("ous")
                'v' -> ends("ive")
                'z' -> ends("ize")
                else -> false
            }
            if (hit && m() > 1) b.setLength(j + 1)
        }

        private fun step5() {
            j = b.length - 1
            if (b.last() == 'e') {
                val a = m()
                if (a > 1 || (a == 1 && !cvc(b.length - 2))) b.setLength(b.length - 1)
            }
            j = b.length - 1
            if (b.last() == 'l' && doubleC(b.length - 1) && m() > 1) b.setLength(b.length - 1)
        }
    }
}
