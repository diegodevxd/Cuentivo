package com.cuentivo.app.domain

/** One customer as it appears in a shared list: just what is needed to say who owes how much. */
data class Debtor(
    val name: String,
    val balanceCents: Long,
    val isOverdue: Boolean = false,
)

/**
 * Plain-text summaries meant for WhatsApp or any app in the Android Sharesheet.
 *
 * Two separate builders on purpose. [forCustomer] is sent *to* the customer and therefore must never
 * contain another customer's name or balance; [forBusiness] is the owner's own list and never leaves
 * their hands unless they choose to send it.
 *
 * El destinatario manda también en los signos. El mensaje del cliente se lee **desde su lado**: lo que
 * le aumenta la deuda va sin signo —una compra— y lo que se la baja lleva menos —un abono—. Las
 * pantallas del dueño muestran lo contrario ("−$799.00 Deuda") porque se leen desde el lado del
 * negocio, y por eso la regla vive aquí y no en un formateador compartido.
 */
object DebtorShareText {

    /** La URL del pie, una sola vez. */
    private const val PLAY_URL = "https://play.google.com/store/apps/details?id=com.cuentivo.app"

    /**
     * El pie para quien **no** paga.
     *
     * Se decide con [SubscriptionTier.includesProBenefits] y no con `== FREE` a propósito: un plan de
     * pago que se añada mañana no debe llevar publicidad por un olvido.
     */
    private fun footer(tier: SubscriptionTier): String =
        if (tier.includesProBenefits) "" else "\n\nEnviado con Cuentivo · Gratis en Google Play\n$PLAY_URL"

    /** The owner's collection list: everyone who owes, largest debt first. */
    fun forBusiness(tier: SubscriptionTier, businessName: String, debtors: List<Debtor>): String {
        val owing = debtors.filter { it.balanceCents > 0 }.sortedByDescending { it.balanceCents }
        if (owing.isEmpty()) return "$businessName\nNadie tiene saldo pendiente." + footer(tier)
        val lines = owing.joinToString("\n") { debtor ->
            val overdue = if (debtor.isOverdue) " (vencido)" else ""
            // Aquí el saldo va sin signo porque todos los de la lista deben: el signo no diría nada.
            "• ${debtor.name}: ${Money.format(debtor.balanceCents)}$overdue"
        }
        val total = owing.sumOf { it.balanceCents }
        return buildString {
            append(businessName).append('\n')
            append("Clientes por cobrar: ${owing.size}").append('\n').append('\n')
            append(lines).append('\n').append('\n')
            append("Total por cobrar: ${Money.format(total)}")
        } + footer(tier)
    }

    /**
     * The message sent to one customer. Deliberately short and neutral in tone, and it carries only
     * this customer's numbers.
     */
    fun forCustomer(
        tier: SubscriptionTier,
        businessName: String,
        customerName: String,
        balanceCents: Long,
        entries: List<StatementEntry> = emptyList(),
    ): String {
        // El nombre del negocio se calla cuando está vacío o cuando es el valor por defecto: el cliente
        // no tiene por qué recibir "con Mi negocio".
        val header = header(businessName, customerName)
        // No column alignment: WhatsApp renders this in a proportional font, so padded columns
        // arrive crooked. Separators and an indented second line survive any width.
        val detail = entries.takeIf { it.isNotEmpty() }?.joinToString("\n") { entry ->
            // La forma de la línea y el signo viven en `StatementLineFormat`, que la imagen del estado
            // de cuenta usa también: el texto y la imagen no pueden decir cosas distintas.
            val line = StatementLineFormat.movementLine(entry)
            StatementLineFormat.detailOrNull(entry)?.let { "$line\n   $it" } ?: line
        }
        return buildString {
            append(header).append('\n')
            if (detail != null) append('\n').append(detail).append('\n')
            append('\n')
            append(balanceLine(balanceCents))
        } + footer(tier)
    }

    /**
     * El texto que acompaña a la **imagen** del estado de cuenta (v44).
     *
     * WhatsApp manda imagen y texto juntos —el texto queda como pie de foto—, así que los
     * movimientos salían dos veces: en la imagen y en el texto. Decidido por Diego (2026-09-22):
     * cuando va con imagen, el texto se acorta. Sin movimientos —ya van en la imagen—, con el mismo
     * encabezado y el mismo pie que [forCustomer], y el mismo saldo en cero / a favor.
     *
     * Reutiliza [header], [balanceLine] y [footer] a propósito: el encabezado y el pie no pueden
     * divergir entre el texto corto y el completo.
     */
    fun forCustomerWithImage(
        tier: SubscriptionTier,
        businessName: String,
        customerName: String,
        balanceCents: Long,
    ): String = buildString {
        append(header(businessName, customerName)).append('\n')
        append('\n')
        append(balanceLine(balanceCents))
    } + footer(tier)

    /** El encabezado, con o sin nombre de negocio. Uno solo para los dos textos. */
    private fun header(businessName: String, customerName: String): String =
        if (BusinessName.isConfigured(businessName)) {
            "Hola $customerName, te comparto tu estado de cuenta de ${businessName.trim()}."
        } else {
            "Hola $customerName, te comparto tu estado de cuenta."
        }

    /** El saldo, en cero / a favor o pendiente. Uno solo para los dos textos. */
    private fun balanceLine(balanceCents: Long): String =
        if (balanceCents > 0) "Saldo pendiente: ${Money.format(balanceCents)}"
        else "Tu cuenta está al corriente. ¡Gracias!"
}
