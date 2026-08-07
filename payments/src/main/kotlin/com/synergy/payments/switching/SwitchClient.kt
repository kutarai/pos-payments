package com.synergy.payments.switching

import android.util.Log
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import com.synergy.payments.grpc.payment.AcceptorAuthorisationRequest
import com.synergy.payments.grpc.payment.AcceptorAuthorisationResponse
import com.synergy.payments.grpc.payment.PaymentServiceGrpc
import com.synergy.payments.grpc.payment.PaymentServiceGrpcKt
import com.synergy.payments.grpc.payment.MobileMoneyPaymentRequest
import com.synergy.payments.grpc.payment.MobileMoneyPaymentUpdate
import com.synergy.payments.grpc.payment.QrPaymentRequest
import com.synergy.payments.grpc.payment.QrPaymentUpdate
import com.synergy.payments.grpc.terminal.HeartbeatRequest
import com.synergy.payments.grpc.terminal.HeartbeatResponse
import com.synergy.payments.grpc.terminal.TerminalManagementServiceGrpc
import com.synergy.payments.grpc.terminal.TerminalRegistrationRequest
import com.synergy.payments.grpc.terminal.TerminalRegistrationResponse
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.TimeUnit

/**
 * gRPC client wrapper for SynergySwitch communication.
 *
 * Uses blocking stubs for card authorisation (called from EMV kernel's AIDL thread),
 * and Kotlin coroutine stubs for streaming operations (QR payments).
 */
class SwitchClient(val host: String, val port: Int) {

    companion object {
        private const val TAG = "SwitchClient"
        private const val AUTHORISE_DEADLINE_SECONDS = 20L
        private const val MANAGEMENT_DEADLINE_SECONDS = 15L
    }

    /**
     * TLS, verified against the device's trust store.
     *
     * This channel carries card authorisations — PAN, expiry, the EMV cryptogram and the
     * response that decides whether a customer is charged. In plaintext all of that is readable
     * by anything between the till and the switch, and an authorisation response is forgeable
     * by anything that can answer faster than the switch.
     *
     * The certificate must be one the terminal already trusts. A switch presenting a private
     * or self-signed certificate needs that CA added as a trust anchor in the app's network
     * security configuration — not a downgrade to plaintext.
     */
    private val channel: ManagedChannel by lazy {
        ManagedChannelBuilder.forAddress(host, port)
            .useTransportSecurity()
            .build()
    }

    private val paymentStub: PaymentServiceGrpc.PaymentServiceBlockingStub by lazy {
        PaymentServiceGrpc.newBlockingStub(channel)
    }

    private val paymentCoroutineStub: PaymentServiceGrpcKt.PaymentServiceCoroutineStub by lazy {
        PaymentServiceGrpcKt.PaymentServiceCoroutineStub(channel)
    }

    private val terminalStub: TerminalManagementServiceGrpc.TerminalManagementServiceBlockingStub by lazy {
        TerminalManagementServiceGrpc.newBlockingStub(channel)
    }

    fun authorise(request: AcceptorAuthorisationRequest): AcceptorAuthorisationResponse {
        Log.d(TAG, "Sending authorisation: exchangeId=${request.header.exchangeId}")
        // Deliberately not withWaitForReady. That queued the call against a channel that could
        // not connect and let it run to the deadline, so every connection-level fault — wrong
        // host, dead port, untrusted certificate — arrived as an identical DEADLINE_EXCEEDED
        // after 20 seconds with the real cause discarded. A self-signed switch certificate went
        // undiagnosed that way, looking for all the world like the switch being down.
        //
        // Without it the RPC fails as soon as the connection does, carrying the cause, which is
        // what the UNAVAILABLE branch in SwitchIntegration was always written to expect.
        return paymentStub
            .withDeadlineAfter(AUTHORISE_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .authorise(request)
    }

    /**
     * Open a server-streaming call for QR payment confirmation.
     * Returns a Flow that emits QrPaymentUpdate messages from the switch.
     * When the caller cancels the coroutine scope, the stream is closed,
     * signalling the switch to mark the payment as TIMED_OUT.
     */
    fun waitForQrPayment(request: QrPaymentRequest): Flow<QrPaymentUpdate> {
        Log.d(TAG, "Opening QR payment stream: ref=${request.paymentReference}")
        return paymentCoroutineStub.waitForQrPayment(request)
    }

    /**
     * Open a server-streaming call for push-initiated mobile money payment.
     * Returns a Flow that emits MobileMoneyPaymentUpdate messages from the switch.
     * When the caller cancels the coroutine scope (20s terminal timeout), the stream
     * is closed, signalling the switch to mark the payment as TIMED_OUT.
     */
    fun initiateMobileMoneyPayment(request: MobileMoneyPaymentRequest): Flow<MobileMoneyPaymentUpdate> {
        Log.d(TAG, "Opening mobile money payment stream: ref=${request.paymentReference}, mobile=${request.mobileNumber}")
        return paymentCoroutineStub.initiateMobileMoneyPayment(request)
    }

    fun register(request: TerminalRegistrationRequest): TerminalRegistrationResponse {
        Log.d(TAG, "Registering terminal: ${request.terminalId}")
        return terminalStub
            .withDeadlineAfter(MANAGEMENT_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .register(request)
    }

    fun heartbeat(request: HeartbeatRequest): HeartbeatResponse {
        return terminalStub
            .withDeadlineAfter(MANAGEMENT_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .heartbeat(request)
    }

    fun shutdown() {
        try {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "Error shutting down gRPC channel", e)
            channel.shutdownNow()
        }
    }
}
