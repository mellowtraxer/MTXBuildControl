package de.ricci.buildcontrol

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

class Store(context: Context) {
    private val prefs = context.getSharedPreferences("mtx",Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        (store.getKey("mtx-token",null) as? SecretKey)?.let{return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply{
            init(KeyGenParameterSpec.Builder("mtx-token",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun token(): String {
        val raw=prefs.getString("token",null) ?: return ""
        val parts=raw.split(":")
        return Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.getDecoder().decode(parts[0]))); String(doFinal(Base64.getDecoder().decode(parts[1])),Charsets.UTF_8) }
    }
    fun saveToken(token: String) {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.ENCRYPT_MODE,key())}
        val encrypted=cipher.doFinal(token.toByteArray())
        check(prefs.edit().putString("token",Base64.getEncoder().encodeToString(cipher.iv)+":"+Base64.getEncoder().encodeToString(encrypted)).commit())
    }
    fun logout() { prefs.edit().remove("token").apply() }
    fun projects(): List<Project> {
        val a=JSONArray(prefs.getString("projects",null) ?: "[]")
        return if(a.length()==0) listOf(Project("mellowtraxer/RiccisTidalPlaylistCreator","feature/mtx-studio-core","MTX Studio"),Project("mellowtraxer/GarminSleepTasker","sleepsync-compose-v2","SleepSync")) else (0 until a.length()).map { a.getJSONObject(it).let{Project(it.getString("repo"),it.getString("branch"),it.getString("name"))} }
    }
    fun saveProjects(projects: List<Project>) { val a=JSONArray(); projects.forEach{a.put(JSONObject().put("repo",it.repo).put("branch",it.branch).put("name",it.name))};prefs.edit().putString("projects",a.toString()).apply() }
    var selected: String
        get()=prefs.getString("selected","").orEmpty()
        set(value){prefs.edit().putString("selected",value).apply()}
}
