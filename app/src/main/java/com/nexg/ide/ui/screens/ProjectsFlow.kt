package com.nexg.ide.ui.screens

/**
 * Which entry point opened the system SAF tree picker.
 *
 * Android's `OpenDocumentTree` contract returns a bare URI and no hint of the
 * caller's intent, so the intent has to be held across the activity-result round
 * trip. That is what this enum is: the only reason the two buttons can share one
 * picker launcher without one silently standing in for the other.
 */
enum class ProjectsPickerIntent {
    /** "Open Project": the picked tree *is* the project. */
    OpenExisting,

    /** "New Project": the picked tree is the *parent* a project is created in. */
    ChooseNewProjectParent,
}

/**
 * The follow-up the screen must perform after a state transition.
 *
 * Deliberately describes *what to do*, not *what to show*: these are the only
 * two manager calls the Projects screen can make, and naming them here is what
 * lets a JVM test prove that Create can never come out as [OpenExisting].
 */
sealed interface ProjectsEffect {
    data class OpenExisting(val treeUri: String) : ProjectsEffect

    data class CreateProject(val parentTreeUri: String, val name: String) : ProjectsEffect

    /** Nothing to do — a cancelled picker, or a result with no pending intent. */
    data object None : ProjectsEffect
}

/** A new [ProjectsFlow] plus the effect that the transition asked for. */
data class ProjectsTransition(val flow: ProjectsFlow, val effect: ProjectsEffect)

/**
 * The Projects screen's routing state, as pure Kotlin.
 *
 * This exists because the bug it fixes was invisible in the UI: both "Open
 * Project" and "New Project" launched the *same* SAF picker, and the intent was
 * only consulted after the picker returned. Tapping New Project therefore showed
 * the Open Project screen, and the distinction between the two actions had no
 * user-visible effect until it was too late to tell them apart.
 *
 * So the ordering is now part of the state machine rather than of the button
 * lambdas:
 *
 *  - Open Project asks for a folder immediately — the folder *is* the project.
 *  - New Project first asks for a name, and only then for a destination folder,
 *    which is the order the approved flow specifies. A project needs a name
 *    before it can be created, and the folder picker is only required because
 *    the approved SAF architecture cannot create a directory without a parent
 *    tree.
 *
 * No Android or Compose imports: the screen holds an instance in `remember`, but
 * every transition below is exercised on the JVM by `ProjectsFlowTest`, which is
 * how "New Project cannot invoke the Open Project action" is kept true.
 */
data class ProjectsFlow(
    /** True while the create-project name prompt is on screen. */
    val nameDialogOpen: Boolean = false,
    /**
     * The name the user submitted, held until the destination folder is known.
     * It survives a cancelled folder picker so the prompt can reopen with the
     * typed name still in it rather than discarding the user's input.
     */
    val pendingCreateName: String? = null,
    /** The button whose picker is currently in flight, or `null` if none is. */
    val pendingPicker: ProjectsPickerIntent? = null,
) {

    /**
     * "Open Project" was tapped.
     *
     * The caller launches the picker straight away; the folder picked *is* the
     * project. No name prompt is involved, by definition.
     */
    fun openProjectPressed(): ProjectsFlow = copy(
        nameDialogOpen = false,
        pendingCreateName = null,
        pendingPicker = ProjectsPickerIntent.OpenExisting,
    )

    /**
     * "New Project" was tapped.
     *
     * Asks for a name and deliberately does *not* arm the picker: the picker is
     * launched only once a name exists ([nameSubmitted]). This is the fix — the
     * create flow can no longer open the Open Project picker, because at this
     * point there is no picker to open.
     */
    fun createProjectPressed(): ProjectsFlow = copy(
        nameDialogOpen = true,
        pendingCreateName = null,
        pendingPicker = null,
    )

    /**
     * The user confirmed a name. Arms the parent-picker and hands [name] to the
     * caller, which launches the picker and later calls [pickerReturned].
     */
    fun nameSubmitted(name: String): ProjectsFlow = copy(
        nameDialogOpen = false,
        pendingCreateName = name,
        pendingPicker = ProjectsPickerIntent.ChooseNewProjectParent,
    )

    /** The name prompt was dismissed without confirming. */
    fun nameDialogDismissed(): ProjectsFlow = copy(
        nameDialogOpen = false,
        pendingCreateName = null,
        pendingPicker = null,
    )

    /**
     * The SAF picker returned [uri] (`null` when the user cancelled).
     *
     * Consumes the pending intent exactly once. The key invariant: an
     * [ProjectsPickerIntent.OpenExisting] result can only ever produce
     * [ProjectsEffect.OpenExisting], and a
     * [ProjectsPickerIntent.ChooseNewProjectParent] result can only ever produce
     * [ProjectsEffect.CreateProject]. There is no path that crosses them.
     *
     * A cancelled parent picker reopens the name prompt with the submitted name
     * intact instead of dropping the user back to a blank screen.
     */
    fun pickerReturned(uri: String?): ProjectsTransition {
        val intent = pendingPicker
        val createName = pendingCreateName

        // A picker result is one-shot: whatever it was, it is no longer pending.
        val consumed = copy(pendingPicker = null, pendingCreateName = null)

        return when (intent) {
            null -> ProjectsTransition(consumed, ProjectsEffect.None)

            ProjectsPickerIntent.OpenExisting ->
                if (uri == null) {
                    ProjectsTransition(consumed, ProjectsEffect.None)
                } else {
                    ProjectsTransition(consumed, ProjectsEffect.OpenExisting(uri))
                }

            ProjectsPickerIntent.ChooseNewProjectParent ->
                when {
                    uri == null -> ProjectsTransition(
                        flow = consumed.copy(
                            nameDialogOpen = true,
                            pendingCreateName = createName,
                        ),
                        effect = ProjectsEffect.None,
                    )

                    createName == null -> ProjectsTransition(consumed, ProjectsEffect.None)

                    else -> ProjectsTransition(
                        consumed,
                        ProjectsEffect.CreateProject(parentTreeUri = uri, name = createName),
                    )
                }
        }
    }
}
