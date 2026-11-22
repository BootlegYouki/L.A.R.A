package org.lara.experiment.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorTest {
    private val lesson = Lesson.parse(javaClass.classLoader!!.getResourceAsStream("fixture_lesson.json")!!.bufferedReader().readText())
    private val retriever = Retriever(lesson.chunks)
    private val template = "SYS {lesson_chunks}"
    // plain stand-in for the model's template and tokenizer: 1 token per 4 characters
    private val backend = object : ChatBackend {
        override fun render(roles: List<String>, contents: List<String>) = roles.zip(contents).joinToString("\n") { "${it.first}: ${it.second}" } + "\nassistant: "
        override fun count(text: String) = text.length / 4
    }

    @Test fun uncoveredQuestionIsBlockedAndGetsNoMatchText() {
        val b = Tutor.build("how does a tornado form?", emptyList(), retriever, template, 2048, backend)
        assertTrue(b.guardBlocked); assertTrue(b.sources.isEmpty()); assertEquals("", b.opener)
        assertTrue(b.promptText.contains(Tutor.NO_MATCH))
    }

    @Test fun coveredQuestionSendsAtMostThreeChunksWithPageOpener() {
        val b = Tutor.build("What lies inside the testa of a small buttercup or poppy seed?", emptyList(), retriever, template, 2048, backend)
        assertFalse(b.guardBlocked)
        assertTrue(b.sources.size in 1..3)
        assertTrue(b.opener.contains("page ${b.grounded!!.page}"))
        assertTrue(b.promptText.endsWith(b.opener))
    }

    @Test fun filipinoMessageGetsTheExactEnglishOnlySentence() {
        val b = Tutor.build("ano ang sagot sa tanong na ito?", emptyList(), retriever, template, 2048, backend)
        assertEquals("I can only help in English. Please ask your question again in English.", b.forcedReply)
        assertNull(Tutor.build("What is a seed?", emptyList(), retriever, template, 2048, backend).forcedReply)
    }

    @Test fun declineSentenceIsExactlyTheBriefs() {
        assertEquals("This lesson does not cover that question. Please return to your lesson or ask your teacher.", Tutor.DECLINE)
        val prompt = javaClass.classLoader!!.getResourceAsStream("prompt_v7.txt")?.bufferedReader()?.readText()
        // prompt_v7.txt is an app asset, not a test resource: the file must hold the sentence in quotes
        val asset = java.io.File("src/main/assets/prompt_v7.txt").readText()
        assertTrue(prompt == null && asset.contains("\"${Tutor.DECLINE}\""))
    }

    @Test fun outputGuardDropsPageNeverSentAndLessonNumbers() {
        val red = Redactor(listOf("The seed holds 128 grams of store material in the testa."), listOf("Seeds"), "what is in the seed?", setOf(41))
        val shown = red.feed("Check page 99 for more. The store is 128 units. What do you think the seed holds?", final = true)
        assertEquals(2, red.dropped)
        assertEquals("What do you think the seed holds? ", shown)
    }

    @Test fun leakFlagNeedsEveryTokenAndPointsNeedsThePage() {
        val k = KeyItem("Q03", "direct", "q", "x", listOf("albumen", "endosperm"), setOf(41))
        val c = lesson.chunks
        assertTrue(Flags.flags(k, "It is albumen, also called endosperm.", setOf(41), c).leak)
        assertFalse(Flags.flags(k, "It is called albumen.", setOf(41), c).leak)
        assertTrue(Flags.flags(k, "Look at page 41. What is inside?", setOf(41), c).points)
        assertTrue(Flags.flags(k, "Look at page 7.", setOf(41), c).badCite)
    }
}
