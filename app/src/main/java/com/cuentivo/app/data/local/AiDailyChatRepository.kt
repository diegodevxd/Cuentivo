package com.cuentivo.app.data.local

import com.cuentivo.app.ai.AiDailyChatModel
import com.cuentivo.app.ai.AiDailyMessageKind
import java.time.Clock
import java.time.ZoneId

data class AiDailyStoredMessage(
    val kind: AiDailyMessageKind,
    val textContent: String?,
)

data class AiDailyConversation(
    val dayIso: String,
    val messages: List<AiDailyStoredMessage>,
)

class AiDailyChatRepository(
    private val dao: AiDailyMessageDao,
    private val ownerUserId: String,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    init {
        require(ownerUserId.isNotBlank()) { "ownerUserId must not be blank" }
    }

    private fun currentDayIso(): String =
        AiDailyChatModel.dayIsoOf(clock.millis(), zoneId)

    suspend fun openToday(): AiDailyConversation {
        val todayIso = currentDayIso()
        val cutoff = AiDailyChatModel.cutoffDayIso(todayIso, retainDays = 30)
        dao.deleteExpiredDays(ownerUserId, cutoff)

        val rawEntities = dao.messagesForDay(ownerUserId, todayIso)
        val decoded = rawEntities.mapNotNull { entity ->
            val kind = try {
                AiDailyMessageKind.valueOf(entity.kind)
            } catch (_: IllegalArgumentException) {
                AiDailyMessageKind.ASSISTANT_UNAVAILABLE
            }

            when (kind) {
                AiDailyMessageKind.USER_TEXT -> {
                    val text = entity.textContent?.trim()
                    if (text.isNullOrEmpty()) {
                        null
                    } else {
                        AiDailyStoredMessage(kind = kind, textContent = text)
                    }
                }
                AiDailyMessageKind.ASSISTANT_GREETING,
                AiDailyMessageKind.ASSISTANT_OUT_OF_SCOPE,
                AiDailyMessageKind.ASSISTANT_UNAVAILABLE,
                AiDailyMessageKind.ASSISTANT_QUOTA_EXHAUSTED,
                AiDailyMessageKind.ASSISTANT_RATE_LIMITED,
                AiDailyMessageKind.ASSISTANT_DISABLED,
                AiDailyMessageKind.ASSISTANT_ENTITLEMENT_REQUIRED -> {
                    if (entity.textContent != null) {
                        null
                    } else {
                        AiDailyStoredMessage(kind = kind, textContent = null)
                    }
                }
            }
        }

        if (decoded.isEmpty()) {
            dao.insert(
                AiDailyMessageEntity(
                    ownerUserId = ownerUserId,
                    dayIso = todayIso,
                    kind = AiDailyMessageKind.ASSISTANT_GREETING.name,
                    textContent = null,
                    createdAtEpochMillis = clock.millis(),
                ),
            )
            return AiDailyConversation(
                dayIso = todayIso,
                messages = listOf(
                    AiDailyStoredMessage(
                        kind = AiDailyMessageKind.ASSISTANT_GREETING,
                        textContent = null,
                    ),
                ),
            )
        }

        return AiDailyConversation(
            dayIso = todayIso,
            messages = decoded,
        )
    }

    suspend fun appendUserText(text: String) {
        val trimmed = text.trim()
        require(trimmed.isNotEmpty()) { "user text must not be blank" }
        dao.insert(
            AiDailyMessageEntity(
                ownerUserId = ownerUserId,
                dayIso = currentDayIso(),
                kind = AiDailyMessageKind.USER_TEXT.name,
                textContent = trimmed,
                createdAtEpochMillis = clock.millis(),
            ),
        )
    }

    suspend fun appendAssistant(kind: AiDailyMessageKind) {
        require(kind != AiDailyMessageKind.USER_TEXT) { "assistant kind cannot be USER_TEXT" }
        dao.insert(
            AiDailyMessageEntity(
                ownerUserId = ownerUserId,
                dayIso = currentDayIso(),
                kind = kind.name,
                textContent = null,
                createdAtEpochMillis = clock.millis(),
            ),
        )
    }

    suspend fun clearToday(): AiDailyConversation {
        val todayIso = currentDayIso()
        dao.deleteDay(ownerUserId, todayIso)
        dao.insert(
            AiDailyMessageEntity(
                ownerUserId = ownerUserId,
                dayIso = todayIso,
                kind = AiDailyMessageKind.ASSISTANT_GREETING.name,
                textContent = null,
                createdAtEpochMillis = clock.millis(),
            ),
        )
        return AiDailyConversation(
            dayIso = todayIso,
            messages = listOf(
                AiDailyStoredMessage(
                    kind = AiDailyMessageKind.ASSISTANT_GREETING,
                    textContent = null,
                ),
            ),
        )
    }
}
