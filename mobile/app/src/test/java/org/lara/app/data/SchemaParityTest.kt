package org.lara.app.data

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SchemaParityTest {
    private fun exported(): JsonObject {
        val file = File(System.getProperty("lara.schemaDir"), "org.lara.app.data.local.LaraDatabase/1.json")
        assertTrue("Room must export its compiled schema: $file", file.isFile)
        return Json.parseToJsonElement(file.readText()).jsonObject.getValue("database").jsonObject
    }

    private fun contract(): SQLiteDatabase {
        val sql = requireNotNull(javaClass.getResourceAsStream("/client_offline.sql"))
            .bufferedReader().use { it.readText() }
        return SQLiteDatabase.create(null).apply {
            sql.lineSequence().map { it.substringBefore("--") }.joinToString("\n")
                .split(';').filter { it.isNotBlank() }.forEach { execSQL(it) }
        }
    }

    private fun rows(db: SQLiteDatabase, sql: String): List<List<String?>> =
        db.rawQuery(sql, null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add((0 until cursor.columnCount).map {
                    if (cursor.isNull(it)) null else cursor.getString(it)
                })
            }
        }

    private fun columnSignatures(db: SQLiteDatabase, table: String) =
        rows(db, "PRAGMA table_info(`$table`)").associate { row ->
            row[1] to listOf(row[2], row[3], row[4], row[5])
        }

    private fun assertExportedFields(db: SQLiteDatabase, entity: JsonObject) {
        val table = entity.getValue("tableName").jsonPrimitive.content
        val primary = entity.getValue("primaryKey").jsonObject.getValue("columnNames").jsonArray
            .map { it.jsonPrimitive.content }
        val actual = entity.getValue("fields").jsonArray.associate { value ->
            val field = value.jsonObject
            val name = field.getValue("columnName").jsonPrimitive.content
            name to listOf(
                field.getValue("affinity").jsonPrimitive.content,
                if (field.getValue("notNull").jsonPrimitive.boolean) "1" else "0",
                field["defaultValue"]?.jsonPrimitive?.contentOrNull,
                (primary.indexOf(name) + 1).toString(),
            )
        }
        assertEquals("$table exported fields", columnSignatures(db, table), actual)
    }

    private fun indexSignatures(db: SQLiteDatabase, table: String): Set<List<String?>> {
        val primary = rows(db, "PRAGMA table_info(`$table`)")
            .filter { it[5] != "0" }.sortedBy { it[5]?.toInt() }.map { it[1] }
        return rows(db, "PRAGMA index_list(`$table`)").mapNotNull { row ->
            val columns = rows(db, "PRAGMA index_info(`${row[1]}`)").map { it[2] }
            if (row[2] == "1" && columns == primary) return@mapNotNull null
            val sql = rows(db, "SELECT sql FROM sqlite_master WHERE type = 'index' AND name = '${row[1]}'")
                .firstOrNull()?.firstOrNull().orEmpty().replace("`", "").replace("\"", "")
            val ordered = columns.map { name ->
                val descending = Regex("\\b" + Regex.escape(requireNotNull(name)) + "\\s+DESC\\b", RegexOption.IGNORE_CASE).containsMatchIn(sql)
                listOf(name, if (descending) "1" else "0")
            }
            listOf(if (row[2] == "1") "UNIQUE" else row[1], row[2], ordered.toString())
        }.toSet()
    }

    @Test fun allSixteenTablesMatchActualContractIncludingConstraints() {
        val schema = exported()
        assertEquals(1, schema.getValue("version").jsonPrimitive.int)
        val entities = schema.getValue("entities").jsonArray.map { it.jsonObject }
        contract().use { expected ->
            val tables = rows(expected, "SELECT name FROM sqlite_master WHERE type = 'table' AND name != 'android_metadata'").map { it[0] }.toSet()
            assertEquals(16, tables.size)
            assertEquals(tables, entities.map { it.getValue("tableName").jsonPrimitive.content }.toSet())
            SQLiteDatabase.create(null).use { actual ->
                entities.forEach { entity ->
                    val table = entity.getValue("tableName").jsonPrimitive.content
                    actual.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                    entity.getValue("indices").jsonArray.forEach { index ->
                        actual.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                    }
                    assertExportedFields(expected, entity)
                    assertEquals("$table columns", columnSignatures(expected, table), columnSignatures(actual, table))
                    assertEquals("$table indexes", indexSignatures(expected, table), indexSignatures(actual, table))
                    assertEquals("$table foreign keys", rows(expected, "PRAGMA foreign_key_list(`$table`)"), rows(actual, "PRAGMA foreign_key_list(`$table`)"))
                }
            }
            assertEquals("0", columnSignatures(expected, "users").getValue("lrn_or_id")[1])
            entities.forEach { entity ->
                val names = entity.getValue("fields").jsonArray.map { it.jsonObject.getValue("columnName").jsonPrimitive.content }
                assertFalse(names.contains("pin_hash"))
                assertFalse(names.contains("correct_answer"))
            }
        }
    }

    @Test fun parityAssertionDetectsRemovedColumn() {
        val entity = exported().getValue("entities").jsonArray.first().jsonObject
        val drifted = JsonObject(entity + ("fields" to JsonArray(entity.getValue("fields").jsonArray.drop(1))))
        contract().use { expected ->
            assertThrows(AssertionError::class.java) { assertExportedFields(expected, drifted) }
        }
    }
}
