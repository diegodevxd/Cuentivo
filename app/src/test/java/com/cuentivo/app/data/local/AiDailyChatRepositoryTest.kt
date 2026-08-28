package com.cuentivo.app.data.local

import com.cuentivo.app.ai.AiDailyChatModel
import com.cuentivo.app.ai.AiDailyMessageKind
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AiDailyChatRepositoryTest {

    private val fixedZone = ZoneId.of("America/Mexico_City")
    // 2026-08-24T18:00:00Z -> 2026-08-24T12:00:00 in Mexico City (UTC-6)
    private val fixedClock = Clock.fixed(Instant.parse("2026-08-24T18:00:00Z"), fixedZone)

    private class FakeAiDailyMessageDao : AiDailyMessageDao {
        val messages = mutableListOf<AiDailyMessageEntity>()
        private var nextId = 1L

        override suspend fun insert(message: AiDailyMessageEntity): Long {
            val assignedId = if (message.id == 0L) nextId++ else message.id
            val entity = message.copy(id = assignedId)
            messages.add(entity)
            return assignedId
        }

        override suspend fun messagesForDay(ownerUserId: String, dayIso: String): List<AiDailyMessageEntity> {
            return messages
                .filter { it.ownerUserId == ownerUserId && it.dayIso == dayIso }
                .sortedBy { it.id }
        }

        override suspend fun deleteDay(ownerUserId: String, dayIso: String) {
            messages.removeAll { it.ownerUserId == ownerUserId && it.dayIso == dayIso }
        }

        override suspend fun deleteExpiredDays(ownerUserId: String, cutoffDayIso: String) {
            messages.removeAll { it.ownerUserId == ownerUserId && it.dayIso < cutoffDayIso }
        }
    }

    private fun createRepository(
        dao: AiDailyMessageDao,
        ownerUserId: String = "owner_123",
        clock: Clock = fixedClock,
        zoneId: ZoneId = fixedZone,
    ) = AiDailyChatRepository(
        dao = dao,
        ownerUserId = ownerUserId,
        clock = clock,
        zoneId = zoneId,
    )

    @Test
    fun `open today prunes only owner rows older than the exact 30 day cutoff and inserts one greeting when empty`() {
        runBlocking {
            val dao = FakeAiDailyMessageDao()
            // Today is 2026-08-24 -> 30-day cutoff is 2026-07-26 (today - 29 days)
            // Insert:
            // - owner_123 older than cutoff (2026-07-25): must be pruned
            // - owner_123 exactly at cutoff (2026-07-26): must be kept
            // - owner_456 older than cutoff (2026-07-25): must remain untouched
            dao.insert(AiDailyMessageEntity(ownerUserId = "owner_123", dayIso = "2026-07-25", kind = "USER_TEXT", textContent = "expired", createdAtEpochMillis = 0L))
            dao.insert(AiDailyMessageEntity(ownerUserId = "owner_123", dayIso = "2026-07-26", kind = "USER_TEXT", textContent = "retained", createdAtEpochMillis = 0L))
            dao.insert(AiDailyMessageEntity(ownerUserId = "owner_456", dayIso = "2026-07-25", kind = "USER_TEXT", textContent = "other owner expired", createdAtEpochMillis = 0L))

            val repo = createRepository(dao, ownerUserId = "owner_123")
            val conversation = repo.openToday()

            assertEquals("2026-08-24", conversation.dayIso)
            assertEquals(
                listOf(AiDailyStoredMessage(AiDailyMessageKind.ASSISTANT_GREETING, null)),
                conversation.messages,
            )

            // Verify pruning
            val remainingForOwner123 = dao.messages.filter { it.ownerUserId == "owner_123" }
            assertEquals(2, remainingForOwner123.size)
            assertTrue(remainingForOwner123.any { it.dayIso == "2026-07-26" && it.textContent == "retained" })
            assertTrue(remainingForOwner123.any { it.dayIso == "2026-08-24" && it.kind == AiDailyMessageKind.ASSISTANT_GREETING.name })

            // Verify other owner is untouched
            val remainingForOwner456 = dao.messages.filter { it.ownerUserId == "owner_456" }
            assertEquals(1, remainingForOwner456.size)
            assertEquals("2026-07-25", remainingForOwner456.first().dayIso)
        }
    }

    @Test
    fun `repository never reads or deletes another owner`() {
        runBlocking {
            val dao = FakeAiDailyMessageDao()
            dao.insert(AiDailyMessageEntity(ownerUserId = "owner_456", dayIso = "2026-08-24", kind = "USER_TEXT", textContent = "mensaje de otro", createdAtEpochMillis = 0L))

            val repo = createRepository(dao, ownerUserId = "owner_123")
            val conversation = repo.openToday()

            // owner_123 was empty today, so it inserts greeting
            assertEquals(listOf(AiDailyStoredMessage(AiDailyMessageKind.ASSISTANT_GREETING, null)), conversation.messages)

            // Clear today for owner_123
            repo.clearToday()

            // Verify owner_456 message was untouched
            val owner456Messages = dao.messagesForDay("owner_456", "2026-08-24")
            assertEquals(1, owner456Messages.size)
            assertEquals("mensaje de otro", owner456Messages.first().textContent)
        }
    }

    @Test
    fun `user text is trimmed and assistant rows never store text`() {
        runBlocking {
            val dao = FakeAiDailyMessageDao()
            val repo = createRepository(dao, ownerUserId = "owner_123")

            repo.openToday() // inserts initial greeting
            repo.appendUserText("   María abonó 150   ")
            repo.appendAssistant(AiDailyMessageKind.ASSISTANT_OUT_OF_SCOPE)
            repo.appendAssistant(AiDailyMessageKind.ASSISTANT_UNAVAILABLE)

            val stored = dao.messagesForDay("owner_123", "2026-08-24")
            assertEquals(4, stored.size)
            assertEquals("ASSISTANT_GREETING", stored[0].kind)
            assertEquals(null, stored[0].textContent)
            assertEquals("USER_TEXT", stored[1].kind)
            assertEquals("María abonó 150", stored[1].textContent)
            assertEquals("ASSISTANT_OUT_OF_SCOPE", stored[2].kind)
            assertEquals(null, stored[2].textContent)
            assertEquals("ASSISTANT_UNAVAILABLE", stored[3].kind)
            assertEquals(null, stored[3].textContent)
        }
    }

    @Test
    fun `blank owner and blank user text fail before dao io`() {
        val dao = FakeAiDailyMessageDao()

        assertThrows(IllegalArgumentException::class.java) {
            createRepository(dao, ownerUserId = "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            createRepository(dao, ownerUserId = "   ")
        }

        val repo = createRepository(dao, ownerUserId = "owner_123")
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.appendUserText("") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.appendUserText("   \n\t  ") }
        }
        assertTrue(dao.messages.isEmpty())
    }

    @Test
    fun `append assistant rejects user text kind`() {
        val dao = FakeAiDailyMessageDao()
        val repo = createRepository(dao, ownerUserId = "owner_123")

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.appendAssistant(AiDailyMessageKind.USER_TEXT) }
        }
        assertTrue(dao.messages.isEmpty())
    }

    @Test
    fun `unknown kinds and invalid text shapes are filtered out fail closed without crashing`() {
        runBlocking {
            val dao = FakeAiDailyMessageDao()
            val repo = createRepository(dao, ownerUserId = "owner_123")

            // When all messages are malformed:
            // 1. Unknown kind in DB
            dao.insert(AiDailyMessageEntity(ownerUserId = "owner_123", dayIso = "2026-08-24", kind = "PROPOSAL_JSON", textContent = "{}", createdAtEpochMillis = 0L))
            // 2. USER_TEXT with null textContent
            dao.insert(AiDailyMessageEntity(ownerUserId = "owner_123", dayIso = "2026-08-24", kind = "USER_TEXT", textContent = null, createdAtEpochMillis = 0L))
            // 3. USER_TEXT with blank textContent
            dao.insert(AiDailyMessageEntity(ownerUserId = "owner_123", dayIso = "2026-08-24", kind = "USER_TEXT", textContent = "   ", createdAtEpochMillis = 0L))
            // 4. Assistant kind with non-null textContent
            dao.insert(AiDailyMessageEntity(ownerUserId = "owner_123", dayIso = "2026-08-24", kind = "ASSISTANT_GREETING", textContent = "fake text", createdAtEpochMillis = 0L))

            // Should filter out all 4 and insert a single clean greeting
            val conversation = repo.openToday()
            assertEquals("2026-08-24", conversation.dayIso)
            assertEquals(
                listOf(AiDailyStoredMessage(AiDailyMessageKind.ASSISTANT_GREETING, null)),
                conversation.messages,
            )

            // When there are valid messages alongside invalid ones:
            dao.messages.clear()
            dao.insert(AiDailyMessageEntity(ownerUserId = "owner_123", dayIso = "2026-08-24", kind = "UNKNOWN_CORRUPT", textContent = "bad", createdAtEpochMillis = 0L))
            dao.insert(AiDailyMessageEntity(ownerUserId = "owner_123", dayIso = "2026-08-24", kind = "USER_TEXT", textContent = "venta 200", createdAtEpochMillis = 1L))
            dao.insert(AiDailyMessageEntity(ownerUserId = "owner_123", dayIso = "2026-08-24", kind = "ASSISTANT_UNAVAILABLE", textContent = "has text which is invalid", createdAtEpochMillis = 2L))
            dao.insert(AiDailyMessageEntity(ownerUserId = "owner_123", dayIso = "2026-08-24", kind = "ASSISTANT_OUT_OF_SCOPE", textContent = null, createdAtEpochMillis = 3L))

            val mixedConversation = repo.openToday()
            assertEquals(
                listOf(
                    AiDailyStoredMessage(AiDailyMessageKind.USER_TEXT, "venta 200"),
                    AiDailyStoredMessage(AiDailyMessageKind.ASSISTANT_OUT_OF_SCOPE, null),
                ),
                mixedConversation.messages,
            )
        }
    }

    @Test
    fun `clear today removes only today and restores one deterministic greeting`() {
        runBlocking {
            val dao = FakeAiDailyMessageDao()
            val repo = createRepository(dao, ownerUserId = "owner_123")

            // Existing past day and today
            dao.insert(AiDailyMessageEntity(ownerUserId = "owner_123", dayIso = "2026-08-23", kind = "USER_TEXT", textContent = "ayer", createdAtEpochMillis = 0L))
            dao.insert(AiDailyMessageEntity(ownerUserId = "owner_123", dayIso = "2026-08-24", kind = "USER_TEXT", textContent = "hoy 1", createdAtEpochMillis = 0L))
            dao.insert(AiDailyMessageEntity(ownerUserId = "owner_123", dayIso = "2026-08-24", kind = "USER_TEXT", textContent = "hoy 2", createdAtEpochMillis = 0L))

            val conversation = repo.clearToday()

            assertEquals("2026-08-24", conversation.dayIso)
            assertEquals(listOf(AiDailyStoredMessage(AiDailyMessageKind.ASSISTANT_GREETING, null)), conversation.messages)

            // Yesterday remains
            val yesterday = dao.messagesForDay("owner_123", "2026-08-23")
            assertEquals(1, yesterday.size)
            assertEquals("ayer", yesterday.first().textContent)

            // Today has only the new single greeting
            val today = dao.messagesForDay("owner_123", "2026-08-24")
            assertEquals(1, today.size)
            assertEquals(AiDailyMessageKind.ASSISTANT_GREETING.name, today.first().kind)
            assertEquals(null, today.first().textContent)
        }
    }

    @Test
    fun `open today returns existing history without re-inserting greeting if non-empty`() {
        runBlocking {
            val dao = FakeAiDailyMessageDao()
            val repo = createRepository(dao, ownerUserId = "owner_123")

            dao.insert(AiDailyMessageEntity(ownerUserId = "owner_123", dayIso = "2026-08-24", kind = "USER_TEXT", textContent = "hola", createdAtEpochMillis = 0L))

            val conversation = repo.openToday()
            assertEquals(1, conversation.messages.size)
            assertEquals(AiDailyStoredMessage(AiDailyMessageKind.USER_TEXT, "hola"), conversation.messages.first())

            val stored = dao.messagesForDay("owner_123", "2026-08-24")
            assertEquals(1, stored.size)
        }
    }

    @Test
    fun `all assistant rejection kinds can be appended and decoded with null textContent`() {
        runBlocking {
            val dao = FakeAiDailyMessageDao()
            val repo = createRepository(dao, ownerUserId = "owner_123")

            repo.openToday()
            repo.appendAssistant(AiDailyMessageKind.ASSISTANT_QUOTA_EXHAUSTED)
            repo.appendAssistant(AiDailyMessageKind.ASSISTANT_RATE_LIMITED)
            repo.appendAssistant(AiDailyMessageKind.ASSISTANT_DISABLED)
            repo.appendAssistant(AiDailyMessageKind.ASSISTANT_ENTITLEMENT_REQUIRED)

            val conversation = repo.openToday()
            assertEquals(
                listOf(
                    AiDailyStoredMessage(AiDailyMessageKind.ASSISTANT_GREETING, null),
                    AiDailyStoredMessage(AiDailyMessageKind.ASSISTANT_QUOTA_EXHAUSTED, null),
                    AiDailyStoredMessage(AiDailyMessageKind.ASSISTANT_RATE_LIMITED, null),
                    AiDailyStoredMessage(AiDailyMessageKind.ASSISTANT_DISABLED, null),
                    AiDailyStoredMessage(AiDailyMessageKind.ASSISTANT_ENTITLEMENT_REQUIRED, null),
                ),
                conversation.messages,
            )
        }
    }

    @Test
    fun `unknown kind with null textContent falls back safely to ASSISTANT_UNAVAILABLE`() {
        runBlocking {
            val dao = FakeAiDailyMessageDao()
            val repo = createRepository(dao, ownerUserId = "owner_123")

            dao.insert(AiDailyMessageEntity(ownerUserId = "owner_123", dayIso = "2026-08-24", kind = "UNKNOWN_FUTURE_KIND", textContent = null, createdAtEpochMillis = 0L))

            val conversation = repo.openToday()
            assertEquals(
                listOf(AiDailyStoredMessage(AiDailyMessageKind.ASSISTANT_UNAVAILABLE, null)),
                conversation.messages,
            )
        }
    }
}
