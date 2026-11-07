package de.ricci.buildcontrol

import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue

/** Opt-in integration test; reads the CI repository through the real GitHub API. */
class LiveGitHubTest {
    @Test fun readsRealWorkflowsAndCurrentBuild() {
        assumeTrue("Live read runs only in GitHub Actions", System.getenv("MTX_LIVE_READ") == "true")
        val repo = requireNotNull(System.getenv("GITHUB_REPOSITORY"))
        val token = requireNotNull(System.getenv("GH_READ_TOKEN"))
        val current = requireNotNull(System.getenv("GITHUB_RUN_ID")).toLong()
        val api = GitHub(token)
        val snapshot = api.snapshot(Project(repo, "main"))
        assertTrue(snapshot.workflows.any { it.path == ".github/workflows/android.yml" })
        assertTrue(api.dispatchable.contains(".github/workflows/android.yml"))
        assertTrue(snapshot.builds.any { it.id == current })
        assertNull("Build Control's own CI is always hosted", snapshot.mode)
    }
}
