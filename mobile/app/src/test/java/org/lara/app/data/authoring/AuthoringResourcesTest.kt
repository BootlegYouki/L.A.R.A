package org.lara.app.data.authoring

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class AuthoringResourcesTest {
    @Test fun everyAuthoringKeyHasEnglishAndFilipinoText() {
        val app = File(requireNotNull(System.getProperty("lara.schemaDir"))).parentFile
        fun strings(locale: String): Map<String, String> {
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(File(app, "src/main/res/$locale/strings.xml"))
            val nodes = document.getElementsByTagName("string")
            return (0 until nodes.length).associate { index ->
                val node = nodes.item(index)
                node.attributes.getNamedItem("name").nodeValue to node.textContent
            }
        }
        val english = strings("values")
        val filipino = strings("values-fil")
        val keys = english.keys.filter { it.startsWith("author_") } + listOf("reading_no_topic", "classwork_due",
            "classwork_posted", "reading_work_empty_title", "reading_work_empty", "enrollment_cancel_action")
        assertTrue(keys.size > 30)
        keys.forEach { key ->
            assertTrue("English $key missing", !english[key].isNullOrBlank())
            assertTrue("Filipino $key missing", !filipino[key].isNullOrBlank())
            val placeholders = Regex("%[0-9]+\\$[ds]")
            assertEquals("Formatting differs for $key", placeholders.findAll(english.getValue(key)).map { it.value }.toList(),
                placeholders.findAll(filipino.getValue(key)).map { it.value }.toList())
        }
    }
}
