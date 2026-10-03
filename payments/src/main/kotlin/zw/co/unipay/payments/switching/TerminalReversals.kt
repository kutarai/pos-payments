package zw.co.unipay.payments.switching

import android.util.Log
import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import zw.co.unipay.payments.grpc.payment.AcceptorReversalRequest
import zw.co.unipay.payments.grpc.payment.AcceptorReversalResponse

/**
 * Gets a reversal advice to the switch, and keeps trying until the switch says it has it.
 *
 * A timed-out withdrawal is a request the bank may well have approved: the customer's account
 * debited, no cash handed over. The switch can undo that — it queues a reversal durably and
 * retries the bank until it settles — but only once it has been told. And the moment a terminal
 * needs to tell it is the moment the line to it has just failed, so one attempt is not enough.
 *
 * In memory only. The advice carries the PAN, which this terminal must not write to storage; so
 * a reversal still undelivered when the process dies is lost here and has to be found in
 * reconciliation (the switch's record shows an approval the terminal never completed). The
 * schedule is front-loaded so that is rarely what happens.
 *
 * @param deliver the call to the switch; replaceable for tests.
 * @param pause how to wait between attempts; replaceable for tests.
 * @param report where the log lines go — android.util.Log on a terminal, anything in a JVM test,
 *        where Log is not available.
 */
class TerminalReversals(
    private val deliver: (AcceptorReversalRequest) -> AcceptorReversalResponse,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val schedule: List<Long> = DEFAULT_SCHEDULE_MS,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val report: (priority: Int, message: String, error: Throwable?) -> Unit = ::androidLog,
) {
    constructor(switchClient: SwitchClient) : this(deliver = switchClient::reverse)

    /** How an advice ended, for the log and for tests. */
    enum class Delivery { ACCEPTED, REFUSED, GAVE_UP }

    /**
     * Sends [request] in the background. Returns at once — the caller is usually the EMV kernel's
     * thread, which is waiting on its own answer and must not wait on this one too.
     */
    fun send(request: AcceptorReversalRequest, onDone: (Delivery) -> Unit = {}): Job = scope.launch {
        onDone(deliverWithRetries(request))
    }

    internal suspend fun deliverWithRetries(request: AcceptorReversalRequest): Delivery {
        for ((attempt, wait) in schedule.withIndex()) {
            if (wait > 0) pause(wait)
            try {
                val response = deliver(request)
                if (response.accepted) {
                    report(Log.INFO, "Reversal accepted by the switch: exchangeId=${request.exchangeId}, " +
                        "attempt ${attempt + 1} — ${response.message}", null)
                    return Delivery.ACCEPTED
                }
                // The switch answered and could not queue it — its own words say retry.
                report(Log.WARN, "Switch did not queue reversal ${request.exchangeId} " +
                    "(attempt ${attempt + 1}): ${response.message}", null)
            } catch (e: StatusRuntimeException) {
                if (e.status.code in REFUSALS) {
                    // The switch will not take this advice however often it is sent.
                    report(Log.ERROR, "Switch refused reversal ${request.exchangeId}: ${e.status}", e)
                    return Delivery.REFUSED
                }
                report(Log.WARN, "Reversal ${request.exchangeId} not delivered (attempt ${attempt + 1}): ${e.status}", null)
            } catch (e: Exception) {
                report(Log.WARN, "Reversal ${request.exchangeId} not delivered (attempt ${attempt + 1})", e)
            }
        }
        report(Log.ERROR, "Reversal ${request.exchangeId} could not be delivered after ${schedule.size} attempts " +
            "— the withdrawal must be reconciled by hand", null)
        return Delivery.GAVE_UP
    }

    companion object {
        /**
         * Immediately, then backing off over about twenty minutes: most outages that cause a
         * timeout are short, and a reversal delivered in seconds is one the customer never notices.
         */
        val DEFAULT_SCHEDULE_MS: List<Long> =
            listOf(0L, 5_000L, 15_000L, 30_000L, 60_000L, 120_000L, 300_000L, 600_000L)

        /** Answers that will not change on a retry. */
        private val REFUSALS = setOf(
            Status.Code.UNIMPLEMENTED,
            Status.Code.INVALID_ARGUMENT,
            Status.Code.PERMISSION_DENIED,
            Status.Code.UNAUTHENTICATED,
        )
    }
}

private fun androidLog(priority: Int, message: String, error: Throwable?) {
    val line = if (error != null) "$message\n${Log.getStackTraceString(error)}" else message
    Log.println(priority, "TerminalReversals", line)
}
