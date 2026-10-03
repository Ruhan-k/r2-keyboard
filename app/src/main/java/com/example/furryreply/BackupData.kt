package com.example.furryreply

import androidx.annotation.Keep

@Keep
data class BackupData(
    val backupVersion: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val data: FurryReplyData
)

@Keep
data class FurryReplyData(
    val clients: List<Client>,
    val messages: List<Message>,
    val memories: List<ClientMemory>,
    val summaries: List<ConversationSummary>,
    val feedback: List<ReplyFeedback>,
    val styleProfiles: List<StyleProfile>,
    val usage: List<AppClientUsage>,
    val states: List<ClientConversationStateEntity>,
    val commissions: List<CommissionProfile>
)
