package de.ricci.buildcontrol

import java.net.URLEncoder

/** GitHub's documented fine-grained-token template; repository access stays a user choice. */
object GitHubSetup {
    fun authorizationUrl(project: Project): String {
        val parameters = linkedMapOf(
            "name" to "MTX Build Control",
            "description" to "MTX Build Control: Projekte und Build-Runner vom Handy steuern",
            "target_name" to project.repo.substringBefore('/'),
            "expires_in" to "90",
            "contents" to "write",
            "actions" to "write",
            "administration" to "read"
        )
        return "https://github.com/settings/personal-access-tokens/new?" + parameters.entries.joinToString("&") { (key,value) ->
            key + "=" + URLEncoder.encode(value,"UTF-8")
        }
    }
    fun repositories(project: Project, projects: List<Project>): List<Pair<String,String>> {
        val owner=project.repo.substringBefore('/')
        val selectedFirst=listOf(project)+projects.filter { it.repo != project.repo && it.repo.substringBefore('/')==owner }
        val result=selectedFirst.map{it.name to it.repo.substringAfter('/')}.toMutableList()
        if(owner=="mellowtraxer" && result.none{it.second=="MTXBuildControl"}) result += "App-Updates" to "MTXBuildControl"
        return result.distinctBy{it.second}
    }
    fun token(raw: String): String? {
        val value=raw.trim()
        if(value.length !in 20..512) return null
        return value.takeIf { Regex("(?:github_pat_|ghp_|gho_|ghu_)[A-Za-z0-9_]+").matches(it) }
    }
}
