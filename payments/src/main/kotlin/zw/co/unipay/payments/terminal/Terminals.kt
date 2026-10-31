package zw.co.unipay.payments.terminal

import android.content.Context

/**
 * Whether a terminal's hardware is usable, in terms that mean the same on every make.
 *
 * Deliberately a closed list: a screen says "connecting…" or "terminal unavailable" and has no
 * business knowing which vendor service it is waiting on.
 */
enum class TerminalConnection {
    /** Not connected, and not trying. */
    DISCONNECTED,

    /** A connection is being made. */
    CONNECTING,

    /** The hardware is up and can be used. */
    CONNECTED,

    /** The connection failed or dropped, and may recover. */
    ERROR,

    /**
     * The hardware service is not on this device and retrying has been abandoned.
     *
     * Distinct from [ERROR], which is worth another attempt: this is a unit that does not have
     * what the application needs, and a screen should say so rather than keep spinning.
     */
    UNAVAILABLE
}

/**
 * Where the application says which terminal it is running on.
 *
 * The same shape and for the same reason as [zw.co.unipay.payments.card.CardPaymentDrivers]:
 * screens are started by the platform and cannot be handed dependencies. The application registers
 * once at start-up, from its `Application` subclass, and anything that needs the hardware asks here
 * — so no screen has to name a vendor. An application that registers nothing gets a clear failure
 * rather than a crash inside a vendor SDK.
 */
object Terminals {

    @Volatile
    private var factory: ((Context) -> Terminal)? = null

    fun register(factory: (Context) -> Terminal) {
        this.factory = factory
    }

    fun isRegistered(): Boolean = factory != null

    /** Forgets the registration. For tests only: an application registers once and never undoes it. */
    fun reset() {
        factory = null
    }

    /** @throws IllegalStateException if the application never said what terminal it has. */
    fun create(context: Context): Terminal =
        factory?.invoke(context) ?: error(
            "No terminal registered. The application must call " +
                "Terminals.register { … } at start-up, naming the terminal it runs on."
        )
}
