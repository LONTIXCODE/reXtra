package com.github.andreyasadchy.xtra.ui.chat

import android.content.Context
import androidx.core.content.edit
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.chat.Prediction
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.chat.ChatMediaNetwork
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

internal data class AutoPredictionRule(val percent: Boolean, val amount: Int, val cap: Int) {
    init {
        require(amount > 0 && cap > 0 && (!percent || amount <= 100))
    }

    fun points(balance: Int): Int {
        val requested = if (percent) balance.toLong() * amount / 100 else amount.toLong()
        return minOf(requested, cap.toLong(), balance.toLong()).coerceAtLeast(0).toInt()
    }
}

internal fun Prediction.closesAt(): Long? = createdAt?.let { started ->
    predictionWindowSeconds?.takeIf { it > 0 }?.let { started + it * 1_000L }
}

internal fun Prediction.popularOutcome(): Prediction.PredictionOutcome? {
    val choices = outcomes.orEmpty().filter { !it.id.isNullOrBlank() }
    val highest = choices.maxOfOrNull { it.totalPoints ?: 0 } ?: return null
    return choices.singleOrNull { highest > 0 && it.totalPoints == highest }
}

internal class ChatParticipation(
    private val context: Context,
    private val repository: GraphQLRepository,
    private val network: ChatMediaNetwork,
) {
    data class Entry(val optionId: String, val stake: Int? = null)
    class Failure(val string: Int) : Exception()

    fun rule(account: String, channel: String): AutoPredictionRule? {
        val stored = context.prefs().getString(ruleKey(account, channel), null) ?: return null
        val fields = stored.split(':')
        val percent = fields.getOrNull(0)?.toBooleanStrictOrNull() ?: return null
        val amount = fields.getOrNull(1)?.toIntOrNull() ?: return null
        val cap = fields.getOrNull(2)?.toIntOrNull() ?: return null
        return if (amount > 0 && cap > 0 && (!percent || amount <= 100)) AutoPredictionRule(percent, amount, cap) else null
    }

    fun setRule(account: String, channel: String, rule: AutoPredictionRule?) {
        context.prefs().edit {
            putString(ruleKey(account, channel), rule?.let { "${it.percent}:${it.amount}:${it.cap}" })
        }
    }

    suspend fun submit(
        channel: String,
        eventId: String,
        isPrediction: Boolean,
        select: (balance: Int?) -> Entry?,
    ): Boolean = lock.withLock {
        val account = context.tokenPrefs().getString(C.USER_ID, null)
            ?: throw Failure(R.string.channel_points_sign_in)
        val sessions = TwitchApiHelper.getChannelPointsSessions(context)
        if (sessions.isEmpty()) throw Failure(R.string.channel_points_gql_required)
        val key = "chat_participation_sent_$account"
        val actionId = (if (isPrediction) "prediction:" else "poll:") + eventId
        val attempted = context.prefs().getString(key, "").orEmpty().split('|').filter { it.isNotEmpty() }
        if (actionId in attempted) throw Failure(R.string.chat_vote_already_attempted)
        val proxy = network.proxyClient()
        var renew = false
        for (headers in sessions) {
            val response = repository.loadChatParticipation(headers, channel, proxy)
            renew = renew || response.needsIntegrity()
            val viewer = response.obj("data") ?: continue
            if (viewer.obj("currentUser")?.text("id") != account) continue
            val balance = viewer.obj("user")?.obj("channel")?.obj("self")?.obj("communityPoints")
                ?.let { (it["balance"] as? JsonPrimitive)?.intOrNull }
            if (isPrediction && balance == null) continue
            if (account != context.tokenPrefs().getString(C.USER_ID, null)) throw Failure(R.string.channel_points_sign_in)
            val entry = select(balance) ?: return@withLock false
            val saved = withContext(Dispatchers.IO) {
                context.prefs().edit().putString(key, (attempted.takeLast(63) + actionId).joinToString("|")).commit()
            }
            if (!saved) throw Failure(R.string.chat_vote_failed)
            val result = repository.submitChatParticipation(headers, account, eventId, entry.optionId, entry.stake, proxy)
            if (result.needsIntegrity()) throw Failure(R.string.channel_points_session_refresh)
            val payload = result.obj("data")?.obj(if (isPrediction) "makePrediction" else "voteInPoll")
            if (payload?.obj("error") != null) throw Failure(R.string.chat_vote_denied)
            if (payload?.obj(if (isPrediction) "prediction" else "voter") == null) throw Failure(R.string.chat_vote_unconfirmed)
            return@withLock true
        }
        throw Failure(if (renew) R.string.channel_points_session_refresh else R.string.channel_points_balance_missing)
    }

    private fun ruleKey(account: String, channel: String) = "chat_autoprediction_${account}_$channel"
    private fun JsonObject.obj(key: String) = this[key] as? JsonObject
    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.needsIntegrity() = (this["errors"] as? JsonArray)?.any {
        (it as? JsonObject)?.text("message") == C.FAILED_INTEGRITY_CHECK
    } == true

    companion object {
        private val lock = Mutex()
    }
}
