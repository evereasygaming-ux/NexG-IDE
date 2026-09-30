package com.nexg.ide.domain.model

/**
 * Validation for names that will be handed to a document provider.
 *
 * This lives in `domain/` on purpose. Project creation and file creation both
 * need identical rules, and a rule that only exists inside a ViewModel cannot be
 * unit-tested on the JVM or reused by a future CLI/terminal command. PLAN.MD 4.2
 * wants safety decisions testable rather than living in a ViewModel; this is the
 * smallest piece of that idea that Phase 2 actually needs.
 *
 * `delete` is not here and no delete path exists yet — destructive operations
 * belong to Phase 7 together with `OperationClassifier` and the confirmation
 * flow. Adding a guarded delete before that layer exists would mean shipping an
 * operation the app cannot yet protect.
 */
object NameValidator {

    /**
     * DocumentsProvider implementations cap display names, and the widely used
     * ones use 255 bytes. Checking length in code points rather than UTF-16
     * units avoids rejecting a legitimate emoji name as "too long".
     */
    const val MAX_NAME_LENGTH = 255

    /** Reason a name was rejected, so callers can show something specific. */
    enum class Rejection {
        EMPTY,
        TOO_LONG,
        PATH_SEPARATOR,
        RELATIVE_PATH,
        RESERVED_SEGMENT,
        CONTROL_CHARACTER,
    }

    sealed interface Check {
        data object Valid : Check
        data class Invalid(val reason: Rejection) : Check
    }

    fun check(raw: String): Check {
        if (raw.isEmpty()) return Check.Invalid(Rejection.EMPTY)
        if (raw.codePointCount(0, raw.length) > MAX_NAME_LENGTH) {
            return Check.Invalid(Rejection.TOO_LONG)
        }
        if (raw.contains('/') || raw.contains('\\')) {
            return Check.Invalid(Rejection.PATH_SEPARATOR)
        }
        // "." and ".." are the two segments a document provider must never be
        // asked to create, because they mean "here" and "parent" rather than a
        // name. Rejecting them here is cheaper than a provider-specific failure.
        if (raw == "." || raw == "..") return Check.Invalid(Rejection.RELATIVE_PATH)
        // Newline/control characters break logcat lines and most editors' UIs.
        if (raw.any { it.isISOControl() }) {
            return Check.Invalid(Rejection.CONTROL_CHARACTER)
        }
        return Check.Valid
    }

    fun isValid(raw: String): Boolean = check(raw) is Check.Valid

    /**
     * Trims surrounding whitespace because every "New project" dialog in the
     * world receives at least one accidental space, and a directory literally
     * named " demo " is never what the user meant.
     */
    fun normalise(raw: String): String = raw.trim()
}
