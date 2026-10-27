package dev.upihub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpiResponseTest {
    @Test fun parsesSuccess() {
        val r = UpiResponse.parse("txnId=AXI1&responseCode=00&Status=SUCCESS&txnRef=TR42")
        assertEquals(UpiResponse.Status.SUCCESS, r.status)
        assertEquals("AXI1", r.transactionId)
        assertEquals("TR42", r.approvalRef)
    }

    @Test fun missingResponseIsUnknownNotFailure() {
        assertEquals(UpiResponse.Status.UNKNOWN, UpiResponse.parse(null).status)
        assertNull(UpiResponse.parse("").raw)
    }

    @Test fun parsesFailureCaseInsensitively() {
        assertEquals(UpiResponse.Status.FAILURE, UpiResponse.parse("status=failure&responseCode=ZD").status)
    }
}
