package com.v2ray.ang.colitu.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.v2ray.ang.colitu.api.ColituTokenManager
import com.v2ray.ang.colitu.data.ColituDiagnosticsGate
import com.v2ray.ang.colitu.data.ColituDiagnosticsRequest
import com.v2ray.ang.colitu.repository.ColituSupportRepository

/**
 * Connection-report requests from support: the app asks the panel (at most
 * once a minute, from the foreground poll), puts a pending request in front of
 * the user and uploads the report only after "Send". The timing lives in
 * [ColituDiagnosticsGate].
 */
object ColituDiagnosticsCenter {
    enum class Outcome { Sent, Failed, Declined }

    private val gate = ColituDiagnosticsGate()

    /** The request the dialog is about, or null. Compose state. */
    var pending by mutableStateOf<ColituDiagnosticsRequest?>(null)
        private set

    /** The report is being built / uploaded (buttons disabled). */
    var busy by mutableStateOf(false)
        private set

    /** Asks the panel when due and shows the dialog for a new pending request. Never throws. */
    suspend fun check() {
        if (!ColituTokenManager.isLoggedIn() || busy) return
        if (!gate.tryStartCheck()) return
        val request = runCatching { ColituSupportRepository.pendingDiagnosticsRequest().getOrNull() }.getOrNull()
        val shown = pending
        if (request == null) {
            // Answered elsewhere or withdrawn by the agent.
            if (shown != null && !busy) {
                gate.markAnswered(shown.id)
                pending = null
            }
            return
        }
        if (shown == null && gate.shouldAsk(request)) {
            gate.markShown(request.id)
            pending = request
        }
    }

    /** "Send": [buildReport] makes the full text; the result decides the toast. */
    suspend fun send(buildReport: suspend () -> String): Outcome {
        val request = pending ?: return Outcome.Failed
        if (busy) return Outcome.Failed
        busy = true
        return try {
            val uploaded = runCatching {
                ColituSupportRepository.uploadReport(request.conversationId, request.id, buildReport()).isSuccess
            }.getOrDefault(false)
            if (uploaded) gate.markAnswered(request.id) else gate.markLater(request.id)
            pending = null
            if (uploaded) Outcome.Sent else Outcome.Failed
        } finally {
            busy = false
        }
    }

    /** "Don't send": the panel is told; a failure to tell it only means it asks again later. */
    suspend fun decline(): Outcome {
        val request = pending ?: return Outcome.Declined
        if (busy) return Outcome.Declined
        busy = true
        try {
            val told = runCatching { ColituSupportRepository.declineDiagnosticsRequest(request.id).isSuccess }.getOrDefault(false)
            if (told) gate.markAnswered(request.id) else gate.markLater(request.id)
            pending = null
        } finally {
            busy = false
        }
        return Outcome.Declined
    }

    /** Dialog dismissed (back, outside tap): ask again in 10 minutes or more. */
    fun dismiss() {
        val request = pending ?: return
        if (busy) return
        gate.markLater(request.id)
        pending = null
    }

    /** Sign-out / session end. */
    fun reset() {
        gate.reset()
        pending = null
        busy = false
    }
}
