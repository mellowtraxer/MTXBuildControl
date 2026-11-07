package de.ricci.buildcontrol

import org.junit.Test
import org.junit.Assert.*
import java.net.URI
import java.net.URLDecoder

class GitHubSetupTest {
    @Test fun templatePreparesOnlyNecessaryPermissionsAndExpires() {
        val url=URI(GitHubSetup.authorizationUrl(Project("mellowtraxer/GarminSleepTasker","sleepsync-compose-v2","SleepSync")))
        assertEquals("https",url.scheme); assertEquals("github.com",url.host)
        val query=url.rawQuery.split('&').associate { val pair=it.split('=',limit=2); pair[0] to URLDecoder.decode(pair[1],"UTF-8") }
        assertEquals("MTX Build Control",query["name"])
        assertEquals("mellowtraxer",query["target_name"])
        assertEquals("90",query["expires_in"])
        assertEquals("write",query["contents"]);assertEquals("write",query["actions"]);assertEquals("read",query["administration"])
        assertEquals(setOf("name","description","target_name","expires_in","contents","actions","administration"),query.keys)
    }
    @Test fun selectedProjectComesFirstAndOtherOwnersAreExcluded() {
        val sleep=Project("mellowtraxer/GarminSleepTasker","sleepsync-compose-v2","SleepSync")
        val result=GitHubSetup.repositories(sleep,listOf(Project("mellowtraxer/RiccisTidalPlaylistCreator","feature/mtx-studio-core","MTX Studio"),sleep,Project("another/project","main")))
        assertEquals("SleepSync" to "GarminSleepTasker",result.first())
        assertEquals(listOf("GarminSleepTasker","RiccisTidalPlaylistCreator","MTXBuildControl"),result.map{it.second})
    }
    @Test fun updateProjectIsNotDuplicated() {
        val p=Project("mellowtraxer/MTXBuildControl","main")
        assertEquals(1,GitHubSetup.repositories(p,listOf(p)).size)
    }
    @Test fun copiedFineGrainedTokenIsTrimmed() {
        assertEquals("github_pat_test_ABCDEFGHIJKLMNOP",GitHubSetup.token(" github_pat_test_ABCDEFGHIJKLMNOP\n"))
    }
    @Test fun passwordsRepoNamesAndLinksAreNotSentAsTokens() {
        listOf("", "GarminSleepTasker", "mellowtraxer", "a-long-but-wrong-password", "https://github.com/settings", "github_pat_x").forEach{assertNull(GitHubSetup.token(it))}
    }
    @Test fun existingSupportedTokenFormatsRemainUsable() {
        listOf("ghp_", "gho_", "ghu_").forEach{prefix->assertNotNull(GitHubSetup.token(prefix+"ABCDEFGHIJKLMNOPQRSTUV"))}
    }
}
