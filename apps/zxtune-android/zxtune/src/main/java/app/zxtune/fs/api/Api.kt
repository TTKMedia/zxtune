package app.zxtune.fs.api

import android.content.Context
import android.util.Base64
import androidx.annotation.VisibleForTesting
import app.zxtune.BuildConfig
import app.zxtune.auth.Auth
import app.zxtune.net.Http
import java.io.IOException
import java.net.HttpURLConnection

private const val ENDPOINT = BuildConfig.API_ROOT + "/"
private const val REPLY = ""

object Api {
    @VisibleForTesting
    var authorization: String? = null

    @JvmStatic
    fun initialize(ctx: Context) = setAuthorization(Auth.getUserIdentifier(ctx), "")

    @VisibleForTesting
    fun setAuthorization(name: String, password: String) {
        val credentials = "$name:$password"
        authorization = "Basic " + Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)
    }

    @Throws(IOException::class)
    @JvmStatic
    fun postEvent(url: String) {
        val reply = doRequest("${ENDPOINT}events/$url", "POST")
        if (REPLY != reply) {
            throw IOException("Wrong reply: $reply")
        }
    }

    private fun doRequest(fullUrl: String, method: String): String {
        val connection = Http.createConnection(fullUrl)
        if (authorization != null) {
            connection.setRequestProperty("Authorization", authorization)
        }
        connection.requestMethod = method
        return try {
            connection.connect()
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IOException(connection.responseMessage)
            }
            connection.inputStream.use { input ->
                val realUrl = connection.url
                // Allow redirects to the same origin
                if (!realUrl.toString().startsWith(ENDPOINT)) {
                    throw IOException("Unexpected redirect: $fullUrl -> $realUrl")
                }
                input.bufferedReader().use { it.readText() }
            }
        } finally {
            connection.disconnect()
        }
    }
}
