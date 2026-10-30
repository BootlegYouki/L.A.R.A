package org.lara.app.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.lara.app.data.local.entities.AnnouncementCommentEntity
import org.lara.app.data.remote.dto.*
import org.lara.app.data.repository.toDomain

class SyncDtoTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun minimalPullPreservesSequenceAndEmptyCollections() {
        val dto = json.decodeFromString<SyncPullResponseDto>("""{
            "server_time":9000,"hub_id":"synthetic-hub","sync_epoch":2,
            "next_cursor":7,"has_more":false,"reset":false
        }""")
        assertEquals(7L, dto.nextCursor)
        assertEquals(9000L, dto.serverTime)
        assertTrue(dto.users.isEmpty())
        assertEquals(dto, json.decodeFromString<SyncPullResponseDto>(json.encodeToString(dto)))
    }

    @Test fun publicUserMappingKeepsNullableIdentityAndRejectsAdmin() {
        val dto = json.decodeFromString<PublicUserDto>("""{"id":"synthetic-user","full_name":"Learner","role":"STUDENT"}""")
        val entity = dto.toEntity(1, 2)
        assertNull(entity.lrnOrId)
        assertEquals("Learner", entity.toDomain().fullName)
        assertThrows(IllegalArgumentException::class.java) { dto.copy(role = "ADMIN").toEntity(1, 2) }
    }

    @Test fun announcementMappingUsesContractBooleanDefault() {
        val dto = AnnouncementDto("post", "class", "Lesson", "Read it", 1, 2)
        assertTrue(dto.toEntity().allowComments)
        assertFalse(dto.copy(allowComments = false).toEntity().allowComments)
    }

    @Test fun pushUsesWireFieldsAndOmitsLocalStateAndAuthor() {
        val row = AnnouncementCommentEntity("comment", "post", "learner", "Question", 1, 1, "QUEUED_FOR_SYNC")
        val payload = SyncPushRequestDto(comments = listOf(row.toPushDto()))
        val wire = json.parseToJsonElement(json.encodeToString(payload)).jsonObject
        val comment = wire.getValue("comments").jsonArray.single().jsonObject
        assertEquals(setOf("id", "announcement_id", "content", "created_at"), comment.keys)
        assertEquals(payload, json.decodeFromString<SyncPushRequestDto>(json.encodeToString(payload)))
        assertThrows(IllegalArgumentException::class.java) { row.copy(syncStatus = "SYNCED").toPushDto() }
    }

    @Test fun heldScoresAndRejectedReceiptsSurviveSerialization() {
        val grade = json.decodeFromString<GradeReceiptDto>("""{"attempt_id":"attempt","score":null,"total_points":5,"submitted_at":4}""")
        assertNull(grade.score)
        val response = SyncPushResponseDto(true, listOf(SyncReceiptDto("attempt", "REJECTED", "TIME_LIMIT_EXCEEDED")))
        assertEquals(response, json.decodeFromString<SyncPushResponseDto>(json.encodeToString(response)))
    }
}
