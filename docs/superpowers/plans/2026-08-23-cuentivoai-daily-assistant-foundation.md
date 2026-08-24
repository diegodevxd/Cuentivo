# CuentivoAI Daily Business Assistant — Foundation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `executing-plans` task-by-task. Codex audits every commit before the next task.

**Goal:** Establish deterministic, business-only conversation policy before adding local history, Room persistence, or voice.

**Architecture:** A pure `AiBusinessScopePolicy` classifies only a tiny allow-list of clearly personal/social prompts as out-of-scope. Everything ambiguous remains eligible for the typed gateway to classify later, avoiding false rejections of business language. It has no I/O and is not wired into the chat in this task.

## Global constraints

- No Room, schema/migration, Firebase, Functions, Gemini, network, permissions, Billing, financial data, UI wiring or resources.
- No classifier calculates balances, inspects customers, or creates/confirms operations.
- Deterministic and fail-open for ambiguity; typed server `OUT_OF_SCOPE` remains future authority.
- Preserve debug/release boundary; never touch `docs/superpowers/handoffs/`.

### Task 1: Add a pure conservative business-scope policy

**Files:** Create `app/src/main/java/com/cuentivo/app/ai/AiBusinessScopePolicy.kt` and `app/src/test/java/com/cuentivo/app/ai/AiBusinessScopePolicyTest.kt`.

**Produces:** a sealed decision with `ContinueToInterpretation` / `OutOfScope`, and:

```kotlin
object AiBusinessScopePolicy {
    const val OUT_OF_SCOPE_REPLY = "Soy CuentivoAI, un asistente para tu negocio. No puedo conversar sobre temas personales, pero sí ayudarte con ventas, abonos, clientes, productos, deudas y cobros."
    fun decide(text: String): AiBusinessScopeDecision
}
```

- [ ] Write RED tests: social `"¿Cómo estás?"` is out of scope; `"María abonó 150"` and `"hola María"` continue; the reply promises neither registration nor balance.
- [ ] Run `./gradlew.bat testDebugUnitTest --tests com.cuentivo.app.ai.AiBusinessScopePolicyTest --no-daemon --no-configuration-cache --no-watch-fs`; expect compilation failure.
- [ ] Implement: normalize via `trim().lowercase(Locale.ROOT)` and return `OutOfScope` only for whole-input exact values: `hola`, `buenos dias`, `buenas tardes`, `buenas noches`, `como estas`, `como estas?`, `¿como estas?`, `cuentame un chiste`, `dime un chiste`, `quien eres`, `que haces`. Every other input returns `ContinueToInterpretation`. No fuzzy matching, LLM call, personal response or transaction word.
- [ ] Run GREEN, then `./gradlew.bat testDebugUnitTest assembleDebug lintDebug --no-daemon --no-configuration-cache --no-watch-fs` and `git diff --check`.
- [ ] Commit only the two files as `feat(ai): define conservative business scope policy`.

## Later tasks — intentionally outside this plan

1. Wire the pure policy and typed server `OUT_OF_SCOPE` into chat UI.
2. Add 30-day local history: dedicated Room table/DAO, additive v5→v6 migration, backup/restore check and migration tests. This requires a high-risk task packet.
3. Render the daily history selector and manual delete.
4. Add Android speech-to-text with permission/cancellation/lifecycle tests and a physical-device gate.
