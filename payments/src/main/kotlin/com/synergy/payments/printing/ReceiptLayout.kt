package com.synergy.payments.printing

/** One line of a sale, as it appears on paper. */
data class ReceiptLine(
    val name: String,
    val quantity: Double,
    val unitPrice: Double,
    val lineTotal: Double,
    val isWeighted: Boolean = false,
    val unitOfMeasure: String = "Each"
)

/** Everything a receipt says, gathered before anything is formatted. */
data class ReceiptContent(
    val merchantName: String,
    val receiptNumber: String,
    val dateTime: String,
    val terminalId: String,
    val lines: List<ReceiptLine>,
    val subtotal: Double,
    val tax: Double,
    val total: Double,
    val currency: String,
    val tendered: Double,
    val change: Double,
    val paymentMethod: String,
    val footer: String = "Thank you for shopping with us"
)

/**
 * Lays a receipt out for a roll of till paper.
 *
 * The paper is a fixed number of characters wide and nothing wraps gracefully: a line one
 * character too long is broken wherever the printer happens to run out, which puts half a price
 * on the next line where nobody looks for it. Everything here is therefore cut to the width
 * deliberately, and the amount is what survives — a customer checks the figure.
 *
 * Kept free of Android and of the printer itself so the layout can be tested as text, which is
 * the only way to find a line that is one character too wide without feeding paper through.
 */
class ReceiptLayout(private val width: Int = 32) {

    /** A label on the left and an amount on the right, filling one line exactly. */
    fun spread(label: String, amount: String): String {
        val room = width - amount.length - 1
        if (room <= 0) return amount.takeLast(width)

        val cut = if (label.length > room) label.take(room) else label
        return cut.padEnd(width - amount.length) + amount
    }

    fun centre(text: String): String {
        if (text.length >= width) return text.take(width)
        val pad = (width - text.length) / 2
        return " ".repeat(pad) + text
    }

    fun rule(): String = "-".repeat(width)

    /**
     * An item: what it was and what it came to, with the working shown underneath when there is
     * any. A single unit at its own price needs no arithmetic spelled out; a weight does, because
     * the customer is checking the scale.
     */
    fun item(
        name: String,
        quantity: Double,
        unitPrice: Double,
        lineTotal: Double,
        isWeighted: Boolean = false,
        unitOfMeasure: String = "Each"
    ): List<String> {
        val head = spread(name, money(lineTotal))
        if (!isWeighted && quantity == 1.0) return listOf(head)

        val shownQuantity = if (isWeighted) "${trimZeros(quantity)} $unitOfMeasure" else trimZeros(quantity)
        return listOf(head, "  $shownQuantity x ${money(unitPrice)}")
    }

    fun render(content: ReceiptContent): List<String> {
        val out = mutableListOf<String>()

        out += centre(content.merchantName.take(width))
        out += centre(content.dateTime)
        out += rule()
        out += "Receipt: ${content.receiptNumber}".take(width)
        out += "Till: ${content.terminalId}".take(width)
        out += rule()

        for (line in content.lines) {
            out += item(
                line.name, line.quantity, line.unitPrice, line.lineTotal,
                line.isWeighted, line.unitOfMeasure
            )
        }

        out += rule()
        out += spread("Subtotal", money(content.subtotal))
        // Prices are VAT-inclusive everywhere in this system. A receipt reading as though VAT
        // were added on top invites an argument at the counter that the shop will lose.
        out += spread("VAT included", money(content.tax))
        out += spread("TOTAL ${content.currency}", money(content.total))
        out += rule()

        out += spread(tenderLabel(content.paymentMethod), money(content.tendered))
        if (content.change > 0.0) out += spread("Change", money(content.change))

        out += ""
        out += centre(content.footer)

        return out
    }

    private fun tenderLabel(method: String): String = when (method.uppercase()) {
        "CASH" -> "Cash"
        "CARD" -> "Card"
        "MOBILE_MONEY", "MOBILEMONEY" -> "Mobile money"
        "QR" -> "QR"
        else -> method.lowercase().replaceFirstChar { it.uppercase() }
    }

    private fun money(value: Double): String = String.format("%.2f", value)

    /** 3.0 reads as "3"; 0.756 keeps every digit that was weighed. */
    private fun trimZeros(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString()
        else value.toString().trimEnd('0').trimEnd('.')
}
