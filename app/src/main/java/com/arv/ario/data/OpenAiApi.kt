package com.arv.ario.data

import com.arv.ario.utils.DebugLogger
import com.google.gson.GsonBuilder
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

// Data Models
data class ChatRequest(
    val model: String = "meta-llama/llama-3.3-70b-instruct:free",
    val messages: List<List_Message>,
    val max_tokens: Int = 150
)

data class List_Message(
    val role: String,
    val content: String
)

data class ChatResponse(
    val choices: List<Choice>
)

data class Choice(
    val message: List_Message
)

// API Interface
interface OpenAiApi {
    @Headers("Content-Type: application/json")
    @POST("chat/completions")
    suspend fun generateResponse(@Body request: ChatRequest): ChatResponse
}

// Singleton for Retrofit
object RetrofitClient {
    private const val BASE_URL = "https://openrouter.ai/api/v1/" 

    // REPLACE WITH YOUR ACTUAL API KEY
    private const val API_KEY = "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" 

    val api: OpenAiApi by lazy {
        val logging = HttpLoggingInterceptor { message ->
            // Log to our custom Debug Console as well as Logcat
            DebugLogger.log("API: $message")
        }.apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .addInterceptor(MarkdownStripInterceptor()) // Strip markdown code blocks
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer $API_KEY")
                    .build()
                chain.proceed(request)
            }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        val gson = GsonBuilder().setLenient().create()

        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(OpenAiApi::class.java)
    }
}

class MarkdownStripInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val bodyString = response.body?.string() ?: return response
        
        // Strip markdown code blocks if present (e.g. ```json ... ```)
        val cleanBody = bodyString.trim()
            .replace(Regex("^```json\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^```\\s*"), "")
            .replace(Regex("\\s*```$"), "")
            .trim()
            
        val newBody = cleanBody.toResponseBody(response.body?.contentType())
        return response.newBuilder().body(newBody).build()
    }
}
