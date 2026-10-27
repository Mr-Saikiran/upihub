package dev.upihub

import java.math.BigDecimal
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * A parsed `upi://pay` request.
 *
 * [raw] is the exact text that was scanned or received. It is forwarded to the
 * chosen UPI app unchanged so merchant parameters and QR signatures (`sign`,
 * `mc`, `tr`, ...) stay valid. The other fields are only used for display.
 */
data class UpiPayment(
    val raw: String,
    val payeeAddress: String,
    val payeeName: String?,
    val amount: String?,
    val note: String?,
) {
    sealed class ParseError(val message: String) {
        data object NotUpi : ParseError("This QR code is not a UPI payment code.")
        data class UnsupportedAction(val action: String) :
            ParseError("UPI \"$action\" requests are not supported; only payments are.")
        data object MissingPayee : ParseError("The UPI code has no payee address (pa).")
        data class InvalidPayee(val value: String) :
            ParseError("\"$value\" is not a valid UPI ID.")
        data class InvalidAmount(val value: String) :
            ParseError("\"$value\" is not a valid amount.")
    }

    sealed class Parsed {
        data class Ok(val payment: UpiPayment) : Parsed()
        data class Failed(val error: ParseError) : Parsed()
    }

    companion object {
        private val SCHEME = Regex("^upi://([^/?#]+)/?(?:\\?(.*))?$", RegexOption.IGNORE_CASE)
        private val VPA = Regex("^[A-Za-z0-9._-]{1,256}@[A-Za-z0-9.-]{2,64}$")

        fun parse(text: String): Parsed {
            val trimmed = text.trim()
            val match = SCHEME.matchEntire(trimmed) ?: return Parsed.Failed(ParseError.NotUpi)
            val action = match.groupValues[1]
            if (!action.equals("pay", ignoreCase = true)) {
                return Parsed.Failed(ParseError.UnsupportedAction(action))
            }
            val params = parseQuery(match.groupValues[2])
            val pa = params["pa"]?.trim()
            if (pa.isNullOrEmpty()) return Parsed.Failed(ParseError.MissingPayee)
            if (!VPA.matches(pa)) return Parsed.Failed(ParseError.InvalidPayee(pa))
            val am = params["am"]?.trim()?.takeIf { it.isNotEmpty() }
            if (am != null && !isValidAmount(am)) return Parsed.Failed(ParseError.InvalidAmount(am))
            // Some static merchant QRs carry am=0 meaning "payer enters the amount".
            val displayAmount = am?.takeIf { BigDecimal(it).signum() > 0 }
            return Parsed.Ok(
                UpiPayment(
                    raw = trimmed,
                    payeeAddress = pa,
                    payeeName = params["pn"]?.trim()?.takeIf { it.isNotEmpty() },
                    amount = displayAmount,
                    note = params["tn"]?.trim()?.takeIf { it.isNotEmpty() },
                ),
            )
        }

        /** Builds a payment request for a UPI ID typed by the user. */
        fun build(payeeAddress: String, payeeName: String?, amount: String?, note: String?): Parsed {
            val query = buildList {
                add("pa" to payeeAddress.trim())
                payeeName?.trim()?.takeIf { it.isNotEmpty() }?.let { add("pn" to it) }
                amount?.trim()?.takeIf { it.isNotEmpty() }?.let { add("am" to it) }
                add("cu" to "INR")
                note?.trim()?.takeIf { it.isNotEmpty() }?.let { add("tn" to it) }
            }.joinToString("&") { (k, v) -> "$k=${encode(v)}" }
            return parse("upi://pay?$query")
        }

        fun isValidAmount(value: String): Boolean = Regex("^\\d{1,9}(\\.\\d{1,2})?$").matches(value)

        /** First value wins for repeated keys; keys are case-insensitive. */
        private fun parseQuery(query: String): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            if (query.isEmpty()) return out
            for (part in query.split('&')) {
                if (part.isEmpty()) continue
                val eq = part.indexOf('=')
                val key = decode(if (eq < 0) part else part.substring(0, eq)).lowercase()
                val value = if (eq < 0) "" else decode(part.substring(eq + 1))
                out.putIfAbsent(key, value)
            }
            return out
        }

        private fun decode(s: String): String =
            try {
                URLDecoder.decode(s, "UTF-8")
            } catch (_: IllegalArgumentException) {
                s
            }

        private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("%40", "@")
    }
}
