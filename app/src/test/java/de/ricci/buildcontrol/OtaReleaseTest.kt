package de.ricci.buildcontrol

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OtaReleaseTest {
    private fun release(code: Int, digest: String = "sha256:" + "a".repeat(64), preview: Boolean = false): JSONObject =
        JSONObject().put("tag_name", "build-$code").put("prerelease", preview).put("body", "Changelog")
            .put("assets", JSONArray().put(JSONObject().put("id", code).put("name", "MTXBuildControl.apk")
                .put("digest", digest).put("size", 120).put("state", "uploaded")))

    @Test fun latestCompatibleReleaseRequiresStableChannelNewerVersionAndHash() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(JSONArray()
                .put(release(24, preview = true)).put(release(23, digest = ""))
                .put(release(18)).put(release(21)).put(release(19)).toString()))
            val update = GitHub("", server.url("/").toString()).ownUpdate(18)
            assertNotNull(update)
            assertEquals(21, update!!.versionCode)
            assertEquals("Changelog", update.notes)
            assertNull(server.takeRequest().getHeader("Authorization"))
        }
    }

    @Test fun equalVersionDoesNotTriggerUpdate() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(JSONArray().put(release(18)).toString()))
            assertNull(GitHub("", server.url("/").toString()).ownUpdate(18))
        }
    }
}
