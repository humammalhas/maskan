package app.maskan.chat.data.remote

import android.util.Log
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * The one place the app's HTTP settings are written down: the JSON configuration every request
 * body is encoded with, the client every provider shares, and how a Retrofit service is built
 * on them.
 *
 * Moved out of MaskanApplication in 2.7 (plan item 5) so the golden-request tests build their
 * providers on exactly these settings rather than on a copy that could drift. A change here
 * changes what goes on the wire for every provider at once - which is what those tests catch.
 */
object ApiClients {

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }

    fun okHttpClient(debugLogging: Boolean): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("Content-Type", "application/json")
                .build()
            chain.proceed(request)
        }
        .apply {
            if (debugLogging) {
                val loggingInterceptor = HttpLoggingInterceptor { message ->
                    Log.d("OkHttp", message.replace(Regex("key=[^&\\s]+"), "key=REDACTED"))
                }.apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                }
                addInterceptor(loggingInterceptor)
            }
        }
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun retrofit(baseUrl: String, client: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
}
