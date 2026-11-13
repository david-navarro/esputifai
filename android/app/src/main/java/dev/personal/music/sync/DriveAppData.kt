package dev.personal.music.sync

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The app's hidden folder in the user's Google Drive (`appDataFolder`), through the Drive REST API.
 *
 * Setup (once, by the owner): Google Cloud project "Esputifai" with the Drive API on, consent
 * screen published with only the `drive.appdata` scope, and one Android OAuth client per package
 * name (`dev.personal.music`, and `dev.personal.music.probe` for debug builds) with the signing
 * certificate's SHA-1. No client id or secret lives in the code: Google Play services recognizes
 * the app by package name + certificate. With that scope the app can't see any other Drive file.
 */
class DriveAppData(context: Context, private val http: OkHttpClient) {

    data class RemoteFile(val id: String, val name: String, val modifiedTime: String)

    private val auth = Identity.getAuthorizationClient(context)
    private val request = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    /** Asks for access. If Google needs the user (account choice, consent), the result has a resolution. */
    suspend fun authorize(): AuthorizationResult = auth.authorize(request).await()

    /** The result of the Google screen launched from [authorize]'s resolution. */
    fun resultFrom(data: Intent?): AuthorizationResult = auth.getAuthorizationResultFromIntent(data)

    /**
     * A valid access token, without showing anything (they last about an hour; asking again is
     * silent once consent was given). Null if Google needs the user again.
     */
    suspend fun token(): String? = authorize().let { if (it.hasResolution()) null else it.accessToken }

    // The calls below block: run them on Dispatchers.IO.

    fun list(token: String): List<RemoteFile> {
        val json = JSONObject(call(token, Request.Builder().url("$API/files?spaces=appDataFolder&pageSize=1000&fields=files(id,name,modifiedTime)").get()))
        val files = json.getJSONArray("files")
        return (0 until files.length()).map { i ->
            val f = files.getJSONObject(i)
            RemoteFile(f.getString("id"), f.getString("name"), f.getString("modifiedTime"))
        }
    }

    fun download(token: String, id: String): String = call(token, Request.Builder().url("$API/files/$id?alt=media").get())

    /** Creates a file in the hidden folder and returns its id. */
    fun create(token: String, name: String, content: String): String {
        val meta = JSONObject().put("name", name).put("parents", JSONArray(listOf("appDataFolder"))).put("mimeType", "application/json")
        val id = JSONObject(call(token, Request.Builder().url("$API/files?fields=id").post(meta.toString().toRequestBody(JSON)))).getString("id")
        update(token, id, content)
        return id
    }

    fun update(token: String, id: String, content: String) {
        call(token, Request.Builder().url("$UPLOAD/files/$id?uploadType=media").patch(content.toRequestBody(JSON)))
    }

    private fun call(token: String, builder: Request.Builder): String =
        http.newCall(builder.header("Authorization", "Bearer $token").build()).execute().use { r ->
            val text = r.body.string()
            if (!r.isSuccessful) throw DriveException(r.code, text.take(300))
            text
        }

    class DriveException(val code: Int, body: String) : Exception("Drive HTTP $code: $body")

    private companion object {
        const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"
        const val API = "https://www.googleapis.com/drive/v3"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/** Play services' Task as a suspend call. */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}
