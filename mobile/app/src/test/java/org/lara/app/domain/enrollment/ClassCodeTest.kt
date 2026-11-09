package org.lara.app.domain.enrollment

import org.junit.Assert.*
import org.junit.Test

class ClassCodeTest {
    @Test fun displayHyphenNeverReachesTheRequest() {
        assertEquals("K7M4QX", ClassCode.canonicalOrNull("  k7m-4qx  "))
        assertEquals("K7M-4QX", ClassCode.display("K7M4QX"))
        assertEquals("K7M4QX", ClassCode.draft("k7m-4qx"))
    }
    @Test fun ambiguousCharactersAndMalformedCodesAreRejected() {
        listOf("", "K7M4Q", "K7M4QXX", "O7M4QX", "K0M4QX", "K7I4QX", "K7M1QX", "K7M 4QX", "K7M_4QX")
            .forEach { assertNull(it, ClassCode.canonicalOrNull(it)) }
    }
    @Test fun draftsRemainEditableUntilSixCharactersHaveBeenEntered() {
        assertEquals("K7", ClassCode.display(ClassCode.draft("k7")))
        assertEquals("K7M-4", ClassCode.display(ClassCode.draft("k7m4")))
        assertEquals("K7M4QX", ClassCode.draft("K7M-4QXEXTRA"))
    }
}
