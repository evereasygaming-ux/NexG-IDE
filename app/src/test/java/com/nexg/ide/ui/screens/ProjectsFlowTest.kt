package com.nexg.ide.ui.screens

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Routing/state regression tests for the Projects screen.
 *
 * These exist because of a real device bug: tapping "New Project" opened the
 * same SAF picker as "Open Project", so the create flow was indistinguishable
 * from the open flow on screen. The cause was ordering, not a missing branch —
 * both buttons launched the picker immediately and the intent was only read
 * after the picker came back, so nothing about the create tap looked different.
 *
 * The invariant locked here is the one that broke:
 *
 *  - "New Project" must NOT arm the tree picker until a name has been entered.
 *  - A picker result can only produce the effect its own intent asked for, so
 *    the create flow can never come out as [ProjectsEffect.OpenExisting].
 *
 * Pure Kotlin, no Android or Compose, so this runs on the JVM with no
 * emulator and no Robolectric.
 */
class ProjectsFlowTest {

    private val parentTree = "content://com.android.providers.media.documents/tree/primary%3ADocuments"

    // ---- the regression: Create must not open the Open Project picker ----

    @Test
    fun `createProjectPressed does not arm the tree picker`() {
        val flow = ProjectsFlow().createProjectPressed()

        assertThat(flow.pendingPicker).isNull()
    }

    @Test
    fun `createProjectPressed opens the name prompt instead`() {
        val flow = ProjectsFlow().createProjectPressed()

        assertThat(flow.nameDialogOpen).isTrue()
        assertThat(flow.pendingCreateName).isNull()
    }

    @Test
    fun `openProjectPressed arms the picker without asking for a name`() {
        val flow = ProjectsFlow().openProjectPressed()

        assertThat(flow.pendingPicker).isEqualTo(ProjectsPickerIntent.OpenExisting)
        assertThat(flow.nameDialogOpen).isFalse()
    }

    // ---- the create flow, end to end through the state machine ----

    @Test
    fun `create flow arms the parent picker only after a name is submitted`() {
        val flow = ProjectsFlow()
            .createProjectPressed()
            .nameSubmitted("Aurora")

        assertThat(flow.pendingPicker).isEqualTo(ProjectsPickerIntent.ChooseNewProjectParent)
        assertThat(flow.pendingCreateName).isEqualTo("Aurora")
        assertThat(flow.nameDialogOpen).isFalse()
    }

    @Test
    fun `create flow produces CreateProject carrying the submitted name`() {
        val effect = ProjectsFlow()
            .createProjectPressed()
            .nameSubmitted("Aurora")
            .pickerReturned(parentTree)
            .effect

        assertThat(effect).isEqualTo(ProjectsEffect.CreateProject(parentTree, "Aurora"))
    }

    @Test
    fun `create flow never produces OpenExisting even with a valid tree uri`() {
        val effect = ProjectsFlow()
            .createProjectPressed()
            .nameSubmitted("Aurora")
            .pickerReturned(parentTree)
            .effect

        assertThat(effect).isNotInstanceOf(ProjectsEffect.OpenExisting::class.java)
    }

    // ---- the open flow, and the separation between the two ----

    @Test
    fun `open flow produces OpenExisting and never CreateProject`() {
        val effect = ProjectsFlow()
            .openProjectPressed()
            .pickerReturned(parentTree)
            .effect

        assertThat(effect).isEqualTo(ProjectsEffect.OpenExisting(parentTree))
        assertThat(effect).isNotInstanceOf(ProjectsEffect.CreateProject::class.java)
    }

    /**
     * The cross-contamination case, stated directly.
     *
     * Even if a stale `ChooseNewProjectParent` intent somehow survived from an
     * abandoned attempt, an `OpenExisting` intent must not reach a create.
     * The state machine has no branch that could do that, and this pins it.
     */
    @Test
    fun `an OpenExisting intent can never yield CreateProject`() {
        val effect = ProjectsFlow()
            .openProjectPressed()
            .pickerReturned(parentTree)
            .effect

        assertThat(effect).isNotInstanceOf(ProjectsEffect.CreateProject::class.java)
    }

    // ---- cancelling ----

    @Test
    fun `cancelling the parent picker reopens the name prompt with the typed name`() {
        val transition = ProjectsFlow()
            .createProjectPressed()
            .nameSubmitted("Aurora")
            .pickerReturned(null)

        assertThat(transition.effect).isEqualTo(ProjectsEffect.None)
        assertThat(transition.flow.nameDialogOpen).isTrue()
        assertThat(transition.flow.pendingCreateName).isEqualTo("Aurora")
        assertThat(transition.flow.pendingPicker).isNull()
    }

    @Test
    fun `cancelling the open picker does nothing and leaves no intent armed`() {
        val transition = ProjectsFlow()
            .openProjectPressed()
            .pickerReturned(null)

        assertThat(transition.effect).isEqualTo(ProjectsEffect.None)
        assertThat(transition.flow.pendingPicker).isNull()
        assertThat(transition.flow.nameDialogOpen).isFalse()
    }

    @Test
    fun `dismissing the name prompt clears the create attempt entirely`() {
        val flow = ProjectsFlow()
            .createProjectPressed()
            .nameDialogDismissed()

        assertThat(flow.nameDialogOpen).isFalse()
        assertThat(flow.pendingCreateName).isNull()
        assertThat(flow.pendingPicker).isNull()
    }

    // ---- intent is consumed exactly once ----

    @Test
    fun `a picker result is consumed once and cannot be replayed`() {
        val afterFirst = ProjectsFlow()
            .createProjectPressed()
            .nameSubmitted("Aurora")
            .pickerReturned(parentTree)

        val replay = afterFirst.flow.pickerReturned(parentTree)

        assertThat(replay.effect).isEqualTo(ProjectsEffect.None)
    }

    @Test
    fun `a picker result with no pending intent is ignored`() {
        val transition = ProjectsFlow().pickerReturned(parentTree)

        assertThat(transition.effect).isEqualTo(ProjectsEffect.None)
    }

    @Test
    fun `a parent picker result with no submitted name does not create anything`() {
        // Reachable if the name is cleared between confirming and the picker
        // returning. The result must be dropped rather than create an unnamed
        // project, because the manager has no name to validate.
        val effect = ProjectsFlow()
            .copy(pendingPicker = ProjectsPickerIntent.ChooseNewProjectParent)
            .pickerReturned(parentTree)
            .effect

        assertThat(effect).isEqualTo(ProjectsEffect.None)
    }

    @Test
    fun `starting a create attempt clears a previous open intent`() {
        val flow = ProjectsFlow()
            .openProjectPressed()
            .createProjectPressed()

        assertThat(flow.pendingPicker).isNull()
        assertThat(flow.nameDialogOpen).isTrue()
    }
}
