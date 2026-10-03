package com.example.furryreply

import okhttp3.OkHttpClient
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

data class ConversationMessage(val senderType: String, val content: String, val timestamp: Long)
data class MemoryContext(val key: String, val value: String, val importance: Int, val category: String)
data class ReplyRequest(
    val message: String,
    val recentMessages: List<ConversationMessage>,
    val memories: List<MemoryContext>,
    val conversationSummary: String,
    val ownerStyle: String,
    val manualContext: String,
    val conversationState: String = ConversationState.DEFAULT.name,
    val replyIntent: String = "AUTO",
    val replyModifier: String = "NONE",
    val userMeaning: String? = null,
    val autoPreference: String? = null,
    val commission: CommissionProfileDto? = null
)

data class ConversationStateClassificationRequest(
    val message: String,
    val recentMessages: List<ConversationMessage>,
    val memories: List<MemoryContext>,
    val conversationSummary: String,
    val clientDisplayName: String = "",
    val clientPlatform: String = "",
    val clientUsername: String = "",
)

data class CommissionUpdateDto(
    val status: String? = null,
    val commissionType: String? = null,
    val quotedPrice: String? = null,
    val currency: String? = null,
    val clientBudget: String? = null,
    val subjectDescription: String? = null,
    val referenceNotes: String? = null,
    val deadline: String? = null,
    val paymentStatus: String? = null,
    val revisionStatus: String? = null
)

data class CommissionProfileDto(
    val status: String,
    val commissionType: String?,
    val quotedPrice: String?,
    val currency: String?,
    val clientBudget: String?,
    val subjectDescription: String?,
    val referenceNotes: String?,
    val deadline: String?,
    val paymentStatus: String,
    val revisionStatus: String,
    val notes: String?
)

data class ConversationStateClassificationResponse(
    val state: String,
    val confidence: Double?,
)
data class ReplyResponse(val replies: List<String?>?)
data class MemoryExtractionRequest(val messages: List<ConversationMessage>, val existingMemories: List<MemoryContext>)
data class ExtractedMemory(val key: String, val value: String, val importance: Int, val category: String, val confidence: Float)
data class UpdatedMemory(val originalKey: String, val key: String, val value: String, val importance: Int, val category: String, val confidence: Float)
data class MemoryExtractionResponse(
    val addedMemories: List<ExtractedMemory>? = null,
    val updatedMemories: List<UpdatedMemory>? = null,
    val deletedMemoryKeys: List<String>? = null,
    val commissionUpdate: CommissionUpdateDto? = null
)
data class ConversationSummaryRequest(val previousSummary: String, val messages: List<ConversationMessage>)
data class ConversationSummaryResponse(val summary: String?)

data class StarterHistoryDto(val text: String, val topic: String)
data class StarterRequest(
    val memories: List<MemoryContext>,
    val recentMessages: List<ConversationMessage>,
    val conversationSummary: String,
    val ownerStyle: String,
    val replyIntent: String,
    val recentStarters: List<StarterHistoryDto>,
)
data class StarterResponse(val starters: List<String?>?)

interface ReplyApi {
    @POST("generate-replies")
    fun generateReplies(@Body request: ReplyRequest): Call<ReplyResponse>

    @POST("extract-memories")
    fun extractMemories(@Body request: MemoryExtractionRequest): Call<MemoryExtractionResponse>

    @POST("summarize-conversation")
    fun summarizeConversation(@Body request: ConversationSummaryRequest): Call<ConversationSummaryResponse>

    @POST("generate-starters")
    fun generateStarters(@Body request: StarterRequest): Call<StarterResponse>

    @POST("classify-conversation-state")
    fun classifyConversationState(@Body request: ConversationStateClassificationRequest): Call<ConversationStateClassificationResponse>
}

object ReplyClient {
    // Backend URL is configured in BackendConfig.kt.
    // Provider credentials remain on the backend.
    val api: ReplyApi = Retrofit.Builder()
        .baseUrl(BackendConfig.BASE_URL)
        .client(OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(100, TimeUnit.SECONDS)
            .callTimeout(105, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build())
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(ReplyApi::class.java)
}
