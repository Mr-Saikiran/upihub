package dev.upihub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpiPaymentTest {
    private fun ok(text: String) = (UpiPayment.parse(text) as UpiPayment.Parsed.Ok).payment
    private fun err(text: String) = (UpiPayment.parse(text) as UpiPayment.Parsed.Failed).error

    @Test fun parsesMerchantQrAndKeepsRawUnchanged() {
        val raw = "upi://pay?pa=shop.123@okaxis&pn=Chai%20Point&mc=5812&tr=TR42&am=49.50&cu=INR&sign=AbC%2B%3D"
        val p = ok(raw)
        assertEquals(raw, p.raw)
        assertEquals("shop.123@okaxis", p.payeeAddress)
        assertEquals("Chai Point", p.payeeName)
        assertEquals("49.50", p.amount)
    }

    @Test fun schemeAndKeysAreCaseInsensitive() {
        val p = ok("UPI://PAY?PA=a.b@ybl&PN=X")
        assertEquals("a.b@ybl", p.payeeAddress)
        assertEquals("X", p.payeeName)
    }

    @Test fun acceptsQrWithoutAmount() {
        assertNull(ok("upi://pay?pa=someone@paytm").amount)
    }

    @Test fun rejectsNonUpiAndOtherActions() {
        assertEquals(UpiPayment.ParseError.NotUpi, err("https://example.com"))
        assertTrue(err("upi://mandate?pa=a@b") is UpiPayment.ParseError.UnsupportedAction)
    }

    @Test fun rejectsMissingOrInvalidPayee() {
        assertEquals(UpiPayment.ParseError.MissingPayee, err("upi://pay?pn=X"))
        assertTrue(err("upi://pay?pa=9876543210") is UpiPayment.ParseError.InvalidPayee)
    }

    @Test fun rejectsBadAmounts() {
        assertTrue(err("upi://pay?pa=a@ybl&am=-5") is UpiPayment.ParseError.InvalidAmount)
        assertTrue(err("upi://pay?pa=a@ybl&am=1.234") is UpiPayment.ParseError.InvalidAmount)
    }

    @Test fun zeroAmountMeansPayerEntersAmount() {
        val p = ok("upi://pay?pa=a@ybl&am=0.00")
        assertNull(p.amount)
        assertEquals("upi://pay?pa=a@ybl&am=0.00", p.raw)
    }

    @Test fun withAmountAddsAmountAndKeepsOtherParamsExactly() {
        val qr = "upi://pay?pa=ravi.k@okaxis&pn=Ravi%20Kumar&mode=02&purpose=00"
        val p = (ok(qr).withAmount("150") as UpiPayment.Parsed.Ok).payment
        assertEquals("$qr&cu=INR&am=150.00", p.raw)
        assertEquals("150.00", p.amount)
    }

    @Test fun withAmountReplacesZeroAmountAndKeepsCurrency() {
        val p = (ok("upi://pay?pa=a@ybl&am=0&cu=INR&tn=Hi").withAmount("99.5") as UpiPayment.Parsed.Ok).payment
        assertEquals("upi://pay?pa=a@ybl&cu=INR&tn=Hi&am=99.50", p.raw)
    }

    @Test fun withAmountRejectsZeroAndGarbage() {
        val p = ok("upi://pay?pa=a@ybl")
        assertTrue(p.withAmount("0") is UpiPayment.Parsed.Failed)
        assertTrue(p.withAmount("") is UpiPayment.Parsed.Failed)
        assertTrue(p.withAmount("12.345") is UpiPayment.Parsed.Failed)
    }

    @Test fun buildsManualPaymentWithEncoding() {
        val p = (UpiPayment.build("friend@okhdfcbank", "Ravi K", "100", "Dinner & movie") as UpiPayment.Parsed.Ok).payment
        assertEquals("upi://pay?pa=friend@okhdfcbank&pn=Ravi%20K&am=100&cu=INR&tn=Dinner%20%26%20movie", p.raw)
        assertEquals("friend@okhdfcbank", p.payeeAddress)
        assertEquals("Dinner & movie", p.note)
    }
}
