package de.ricci.buildcontrol

import org.junit.Test
import org.junit.Assert.*
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import org.json.JSONObject
import java.util.Base64

class RunnerPolicyTest {
    private fun runner(name:String="RICCI",os:String="windows",status:String="online",labels:Set<String> = RunnerPolicy.labels)=Runner(name,os,status,true,labels)
    @Test fun busyRicciCanFinishWhileSwitching(){RunnerPolicy.validatePc(listOf(runner()))}
    @Test fun offlineIsRejected(){assertThrows(IllegalArgumentException::class.java){RunnerPolicy.validatePc(listOf(runner(status="offline")))}}
    @Test fun wrongOsIsRejected(){assertThrows(IllegalArgumentException::class.java){RunnerPolicy.validatePc(listOf(runner(os="linux")))}}
    @Test fun missingLabelsAreRejected(){assertThrows(IllegalArgumentException::class.java){RunnerPolicy.validatePc(listOf(runner(labels=setOf("RICCI"))))}}
    @Test fun ambiguousRunnerIsRejected(){assertThrows(IllegalArgumentException::class.java){RunnerPolicy.validatePc(listOf(runner(),runner(name="OTHER")))}}
    @Test fun invalidModeIsRejected(){assertThrows(IllegalArgumentException::class.java){RunnerPolicy.mode("{\"mode\":\"unknown\"}")}}
    @Test fun extraSettingsSurvive(){val o=JSONObject(RunnerPolicy.updated("{\"mode\":\"pc\",\"schema\":1}","github"));assertEquals(1,o.getInt("schema"));assertEquals("github",o.getString("mode"))}
    @Test fun quotedAndUnquotedYamlEventsAreRead(){listOf("on", "'on'").forEach{key->val input=WorkflowReader.inputs("$key:\n  workflow_dispatch:\n    inputs:\n      mode:\n        type: choice\n        options: [build, promote]\n        default: build\n")!!;assertEquals("build",input["mode"]!!.default);assertEquals(listOf("build","promote"),input["mode"]!!.options)}}
    @Test fun workflowsWithoutDispatchAreNotStartable(){assertNull(WorkflowReader.inputs("on: [push]\njobs: {}"))}
    @Test fun noArbitraryWorkflowRewrite(){assertFalse(WorkflowReader.wired(listOf("on: push\njobs:\n  build:\n    runs-on: windows-2022\n")))}
    private val workflow="""
        on: [push]
        jobs:
          select-runner:
            runs-on: ubuntu-latest
            steps:
              - run: cat .github/mtx-runner.json
          android:
            needs: select-runner
            runs-on: ${'$'}{{ fromJSON(needs.select-runner.outputs.labels) }}
    """.trimIndent()
    private fun file(text:String,sha:String) = JSONObject().put("encoding","base64").put("sha",sha).put("content",Base64.getEncoder().encodeToString(text.toByteArray())).toString()
    private fun setup(server:MockWebServer, mode:String="pc") {
        server.enqueue(MockResponse().setBody("[{\"path\":\".github/workflows/build.yml\"}]"))
        server.enqueue(MockResponse().setBody(file(workflow,"workflow-sha")))
        server.enqueue(MockResponse().setBody(file("{\"mode\":\"$mode\"}","original-sha")))
    }
    @Test fun cloudSwitchUsesExistingShaAndVerifiesWithoutTouchingRuns(){
        MockWebServer().use{s->setup(s);s.enqueue(MockResponse().setBody("{\"commit\":{\"sha\":\"new-commit\"}}"));s.enqueue(MockResponse().setBody(file("{\"mode\":\"github\"}","new-sha")))
            val result=GitHub("test",s.url("/").toString()).switch(Project("owner/repo","feature/core"),"github")
            assertTrue(result.contains("new-commit"));repeat(3){s.takeRequest()};val put=s.takeRequest();assertEquals("PUT",put.method);val body=JSONObject(put.body.readUtf8());assertEquals("original-sha",body.getString("sha"));assertEquals("feature/core",body.getString("branch"));assertEquals("github",RunnerPolicy.mode(String(Base64.getDecoder().decode(body.getString("content")))));assertEquals("GET",s.takeRequest().method);assertEquals(5,s.requestCount)
        }
    }
    @Test fun concurrentUpdateFailsWithoutRetryOrOverwrite(){MockWebServer().use{s->setup(s);s.enqueue(MockResponse().setResponseCode(409).setBody("{}"));assertThrows(ApiException::class.java){GitHub("test",s.url("/").toString()).switch(Project("owner/repo","main"),"github")};assertEquals(4,s.requestCount)}}
    @Test fun sameModeDoesNotCommit(){MockWebServer().use{s->setup(s,"github");GitHub("test",s.url("/").toString()).switch(Project("owner/repo","main"),"github");assertEquals(3,s.requestCount)}}
    @Test fun allRunnerPagesAreLoaded(){MockWebServer().use{s->val hundred=(1..100).joinToString(","){"{\"name\":\"R$it\",\"os\":\"linux\",\"status\":\"offline\",\"busy\":false,\"labels\":[]}"};s.enqueue(MockResponse().setBody("{\"runners\":[$hundred]}"));s.enqueue(MockResponse().setBody("{\"runners\":[]}"));assertEquals(100,GitHub("test",s.url("/").toString()).runners(Project("o/r","main")).size);s.takeRequest();assertTrue(s.takeRequest().path!!.contains("page=2"))}}

    @Test fun zipPathsNeverBecomeFilesystemPaths() {
        val bytes=java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bytes).use { z -> z.putNextEntry(java.util.zip.ZipEntry("../../escape.apk")); z.write(byteArrayOf(1,2,3)); z.closeEntry() }
        val folder=java.nio.file.Files.createTempDirectory("mtx-apks").toFile()
        try { MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes.toByteArray())))
            val files=GitHub("test",server.url("/").toString()).download(PackageAsset("test","repos/o/r/actions/artifacts/1/zip",true,"1"),folder)
            assertEquals(1,files.size);assertEquals(folder.canonicalFile,files.single().canonicalFile.parentFile);assertFalse(files.single().name.contains("escape"));assertArrayEquals(byteArrayOf(1,2,3),files.single().readBytes())
        } } finally { folder.deleteRecursively() }
    }
    @Test fun artifactWithoutApkIsReported() {
        val bytes=java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bytes).use { z -> z.putNextEntry(java.util.zip.ZipEntry("log.txt"));z.write("test".toByteArray());z.closeEntry() }
        val folder=java.nio.file.Files.createTempDirectory("mtx-no-apks").toFile()
        try { MockWebServer().use { server -> server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes.toByteArray())));assertThrows(IllegalArgumentException::class.java){GitHub("test",server.url("/").toString()).download(PackageAsset("test","repos/o/r/actions/artifacts/1/zip",true,"1"),folder)};assertEquals(0,folder.listFiles()!!.size) } } finally { folder.deleteRecursively() }
    }
}
