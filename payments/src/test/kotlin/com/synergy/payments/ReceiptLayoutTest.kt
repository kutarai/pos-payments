package com.synergy.payments

import com.synergy.payments.printing.ReceiptContent
import com.synergy.payments.printing.ReceiptLayout
import com.synergy.payments.printing.ReceiptLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A receipt as it comes off a roll of till paper.
 *
 * The paper is a fixed number of characters wide and nothing wraps gracefully: a line one
 * character too long is broken by the printer wherever it happens to run out, which puts a price
 * on the next line where nobody looks for it. So the width is enforced here, where it can be
 * tested, rather than discovered on a customer's receipt.
 */
class ReceiptLayoutTest {

    private val paper = ReceiptLayout(width = 32)

    // ── Fitting the paper ──────────────────────────────────────────────

    @Test
    fun `a label and an amount sit on one line, pushed to the edges`() {
        assertEquals(
            "Total" + " ".repeat(32 - "Total".length - "12.50".length) + "12.50",
            paper.spread("Total", "12.50")
        )
    }

    @Test
    fun `a label too long for its amount is cut, never wrapped`() {
        // The amount is what must survive: a customer checks the figure, and a printer that
        // wraps puts it on a line of its own where it reads as a separate charge.
        val line = paper.spread("Chicken Pieces Family Pack Large", "104.75")

        assertEquals(32, line.length)
        assertTrue("the amount must survive", line.endsWith("104.75"))
    }

    @Test
    fun `centring a line puts it in the middle of the paper`() {
        val centred = paper.centre("SYNERGY RETAIL")
        assertEquals(" ".repeat((32 - "SYNERGY RETAIL".length) / 2) + "SYNERGY RETAIL", centred)
    }

    @Test
    fun `something wider than the paper is simply cut to it`() {
        assertEquals(32, paper.centre("x".repeat(50)).length)
    }

    @Test
    fun `a rule fills the width`() {
        assertEquals("-".repeat(32), paper.rule())
    }

    // ── The lines of a sale ────────────────────────────────────────────

    @Test
    fun `an item shows what was bought and what it came to`() {
        val lines = paper.item(name = "Beef Burger", quantity = 2.0, unitPrice = 3.50, lineTotal = 7.00)

        assertEquals("Beef Burger" + " ".repeat(32 - "Beef Burger".length - 4) + "7.00", lines.first())
        assertEquals("  2 x 3.50", lines[1])
    }

    @Test
    fun `a single unit needs no multiplication spelled out`() {
        val lines = paper.item(name = "Chips (Regular)", quantity = 1.0, unitPrice = 1.50, lineTotal = 1.50)

        assertEquals(1, lines.size)
        assertEquals("Chips (Regular)" + " ".repeat(32 - "Chips (Regular)".length - 4) + "1.50", lines.first())
    }

    @Test
    fun `a weighed item keeps the weight that was charged for`() {
        // 0.756 kg at 6.50 is not "1 x 4.91": the customer is checking the scale, and a receipt
        // that hides the weight cannot be argued with at the counter.
        val lines = paper.item(
            name = "Beef Mince", quantity = 0.756, unitPrice = 6.50, lineTotal = 4.91,
            isWeighted = true, unitOfMeasure = "kg"
        )

        assertEquals("Beef Mince" + " ".repeat(32 - "Beef Mince".length - 4) + "4.91", lines.first())
        assertEquals("  0.756 kg x 6.50", lines[1])
    }

    @Test
    fun `quantities do not carry meaningless decimals`() {
        val lines = paper.item(name = "Cola", quantity = 3.0, unitPrice = 1.20, lineTotal = 3.60)

        assertEquals("  3 x 1.20", lines[1])
    }

    // ── What the printer is handed ─────────────────────────────────────

    @Test
    fun `every line of a rendered receipt fits the paper`() {
        val receipt = paper.render(
            ReceiptContent(
                merchantName = "Synergy Retail — Harare Branch 10 Downtown",
                receiptNumber = "REC-000045",
                dateTime = "04 Aug 2026 10:15",
                terminalId = "A50-42GB-4F00315",
                lines = listOf(
                    ReceiptLine("Beef Burger", 2.0, 3.50, 7.00),
                    ReceiptLine("Chicken Pieces", 0.756, 6.50, 4.91, isWeighted = true, unitOfMeasure = "kg")
                ),
                subtotal = 10.36, tax = 1.55, total = 11.91,
                currency = "USD",
                tendered = 20.00, change = 8.09,
                paymentMethod = "CASH"
            )
        )

        for (line in receipt) {
            assertTrue("too wide for the paper: '$line'", line.length <= 32)
        }
    }

    @Test
    fun `a receipt says what it is, what was bought, and what was paid`() {
        val receipt = paper.render(
            ReceiptContent(
                merchantName = "Synergy Retail",
                receiptNumber = "REC-000045",
                dateTime = "04 Aug 2026 10:15",
                terminalId = "A50-42GB-4F00315",
                lines = listOf(ReceiptLine("Chips (Regular)", 1.0, 1.50, 1.50)),
                subtotal = 1.30, tax = 0.20, total = 1.50,
                currency = "USD",
                tendered = 2.00, change = 0.50,
                paymentMethod = "CASH"
            )
        ).joinToString("\n")

        assertTrue("the shop", receipt.contains("Synergy Retail"))
        assertTrue("the receipt number", receipt.contains("REC-000045"))
        assertTrue("the date", receipt.contains("04 Aug 2026 10:15"))
        assertTrue("the item", receipt.contains("Chips (Regular)"))
        assertTrue("the total", receipt.contains("TOTAL"))
        assertTrue("the currency", receipt.contains("USD"))
        assertTrue("what was tendered", receipt.contains("Cash"))
        assertTrue("the change", receipt.contains("Change"))
        assertTrue("which till", receipt.contains("A50-42GB-4F00315"))
    }

    @Test
    fun `VAT is shown as contained in the price, not added to it`() {
        // Prices here are VAT-inclusive everywhere else in the system; a receipt that reads as
        // though VAT were added on top invites an argument the shop will lose.
        val receipt = paper.render(
            ReceiptContent(
                merchantName = "Synergy Retail",
                receiptNumber = "REC-1", dateTime = "04 Aug 2026", terminalId = "T1",
                lines = listOf(ReceiptLine("Chips (Regular)", 1.0, 1.50, 1.50)),
                subtotal = 1.30, tax = 0.20, total = 1.50, currency = "USD",
                tendered = 1.50, change = 0.0, paymentMethod = "CASH"
            )
        ).joinToString("\n")

        assertTrue("VAT should be described as included", receipt.contains("VAT included"))
    }

    @Test
    fun `a card sale shows no change line`() {
        val receipt = paper.render(
            ReceiptContent(
                merchantName = "Synergy Retail",
                receiptNumber = "REC-2", dateTime = "04 Aug 2026", terminalId = "T1",
                lines = listOf(ReceiptLine("Chips (Regular)", 1.0, 1.50, 1.50)),
                subtotal = 1.30, tax = 0.20, total = 1.50, currency = "USD",
                tendered = 1.50, change = 0.0, paymentMethod = "CARD"
            )
        ).joinToString("\n")

        assertTrue(receipt.contains("Card"))
        assertTrue("no change was given, so no change line", !receipt.contains("Change"))
    }

    @Test
    fun `a wider roll uses the width it has`() {
        val wide = ReceiptLayout(width = 48)

        assertEquals(48, wide.rule().length)
        assertEquals("Total" + " ".repeat(48 - "Total".length - "12.50".length) + "12.50",
            wide.spread("Total", "12.50"))
    }
}
