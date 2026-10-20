package com.v2ray.ang.colitu.app

import com.v2ray.ang.colitu.api.ColituTokenManager
import com.v2ray.ang.colitu.data.ColituDiagnosticsGate
import com.v2ray.ang.colitu.repository.ColituSupportRepository

/**
 * Connection-report requests from support: the app asks the panel (at most
 * once a minute, from the foreground poll) and answers a pending request
 * silently with the redacted report, with no dialog and no toast. The timing
 * lives in [ColituDiagnosticsGate]. The F-Droid build (COLITU_OPT_IN_REPORTING)
 * never answers silently: there the report goes only when the user sends it
 * from the Support tab.
 */
object ColituDiagnosticsCenter {
    private val gate = ColituDiagnosticsGate()

    /** [buildReport] makes the full (redacted) text. Never throws. */
    suspend fun check(buildReport: suspend () -> String) {
        if (com.v2ray.ang.BuildConfig.COLITU_OPT_IN_REPORTING) return
        if (!ColituTokenManager.isLoggedIn()) return
        if (!gate.tryStartCheck()) return
        val request = runCatching { ColituSupportRepository.pendingDiagnosticsRequest().getOrNull() }.getOrNull() ?: return
        if (!gate.shouldSend(request)) return
        gate.markAttempt(request.id)
        val result = runCatching { ColituSupportRepository.sendRequestedReport(request.id, buildReport()) }
            .getOrDefault(ColituSupportRepository.RequestReportResult.Failed)
        when (result) {
            ColituSupportRepository.RequestReportResult.Sent,
            ColituSupportRepository.RequestReportResult.NoLongerPending -> gate.markAnswered(request.id)
            ColituSupportRepository.RequestReportResult.Failed -> gate.markFailed(request.id)
        }
    }

    /** Sign-out / session end. */
    fun reset() = gate.reset()
}
