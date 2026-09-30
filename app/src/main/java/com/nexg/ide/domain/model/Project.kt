package com.nexg.ide.domain.model

/**
 * Pure Kotlin. No Android imports anywhere in `domain/` (PLAN.MD Part 3).
 *
 * A project is identified by the URI of a directory tree the user granted
 * NexG persistent access to through SAF. The URI is the identity: the same
 * folder opened twice must not produce two rows, which is why
 * [ProjectRepository.findByRootUri] exists.
 */
data class Project(
    val id: String,
    val name: String,
    val rootUri: String,
    val createdAt: Long,
    val lastOpenedAt: Long,
    val layout: ProjectLayout = ProjectLayout.UNKNOWN,
)

/**
 * How closely a directory matches the standard Android + Gradle layout that
 * PLAN.MD 4.1 asks the Project Manager to recognise.
 *
 * Deliberately coarse. Deciding "is this actually a NexG-editable Android app"
 * is Phase 15 (validation/linting) work; Phase 2 only needs to tell the user
 * what it can see so far, so three buckets are enough and each one is testable.
 */
enum class ProjectLayout {
    /** Directory opened but nothing recognisable inside it. */
    UNKNOWN,

    /** `settings.gradle*` exists but no `app` module was found. */
    GRADLE_ONLY,

    /**
     * `settings.gradle*` + an `app` module directory. `gradle/wrapper/` is not
     * required here: a project without a committed wrapper is still a real
     * Android project, and refusing to recognise it would be wrong.
     */
    ANDROID_GRADLE,
}
