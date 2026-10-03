package com.example.furryreply

enum class ConversationState {
    NEW_CONTACT,
    CASUAL_CHAT,
    BUILDING_RAPPORT,
    ART_INTEREST,
    COMMISSION_INTEREST,
    PRICE_DISCUSSION,
    OBJECTION,
    FOLLOW_UP,
    EXISTING_CLIENT,
    COMPLETED_COMMISSION;

    fun displayLabel(): String = name.replace('_', ' ')

    companion object {
        val DEFAULT = CASUAL_CHAT

        fun fromName(raw: String?): ConversationState =
            entries.firstOrNull { it.name == raw } ?: DEFAULT
    }
}

data class ClientConversationState(
    val clientId: Long,
    val automaticMode: Boolean = true,
    val manualState: String = ConversationState.DEFAULT.name,
    val detectedState: String = ConversationState.DEFAULT.name,
    val confidence: Float? = null,
    val updatedAt: Long = 0L,
) {
    fun effectiveState(): ConversationState =
        if (automaticMode) ConversationState.fromName(detectedState)
        else ConversationState.fromName(manualState)

    fun bracketLabel(): String = "[ ${effectiveState().name} ]"
}

fun FurryReplyDatabase.conversationStateFor(clientId: Long): ClientConversationState =
    clientConversationStateDao().getForClient(clientId)?.toModel()
        ?: ClientConversationState(clientId = clientId)

fun FurryReplyDatabase.saveConversationState(state: ClientConversationState) {
    clientConversationStateDao().upsert(
        ClientConversationStateEntity.fromModel(state.copy(updatedAt = System.currentTimeMillis()))
    )
}
