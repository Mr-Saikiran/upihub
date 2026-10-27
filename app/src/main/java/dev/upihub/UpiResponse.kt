package dev.upihub

/**
 * The `response` string UPI apps return, e.g.
 * `txnId=AXI123&responseCode=00&Status=SUCCESS&txnRef=ABC`.
 *
 * Many apps return nothing or a partial value, so every field is optional and
 * an absent status stays [Status.UNKNOWN] rather than being treated as failure.
 */
data class UpiResponse(
    val status: Status,
    val transactionId: String?,
    val responseCode: String?,
    val approvalRef: String?,
    val raw: String?,
) {
    enum class Status { SUCCESS, SUBMITTED, FAILURE, UNKNOWN }

    companion object {
        fun parse(raw: String?): UpiResponse {
            val fields = raw.orEmpty().split('&').mapNotNull { part ->
                val eq = part.indexOf('=')
                if (eq <= 0) null else part.substring(0, eq).trim().lowercase() to part.substring(eq + 1).trim()
            }.toMap()
            val status = when (fields["status"]?.uppercase()) {
                "SUCCESS" -> Status.SUCCESS
                "SUBMITTED", "PENDING" -> Status.SUBMITTED
                "FAILURE", "FAILED" -> Status.FAILURE
                else -> Status.UNKNOWN
            }
            return UpiResponse(
                status = status,
                transactionId = fields["txnid"]?.takeIf { it.isNotEmpty() },
                responseCode = fields["responsecode"]?.takeIf { it.isNotEmpty() },
                approvalRef = (fields["approvalrefno"] ?: fields["txnref"])?.takeIf { it.isNotEmpty() },
                raw = raw?.takeIf { it.isNotEmpty() },
            )
        }
    }
}
