import XCTest
@testable import UPIHub

/// Mirrors app/src/test/java/dev/upihub/UpiPaymentTest.kt so both apps parse alike.
final class UpiPaymentTests: XCTestCase {
    private func ok(_ text: String, file: StaticString = #filePath, line: UInt = #line) throws -> UpiPayment {
        try XCTUnwrap(try? UpiPayment.parse(text).get(), "expected \(text) to parse", file: file, line: line)
    }

    private func err(_ text: String) -> UpiPayment.ParseError? {
        if case .failure(let error) = UpiPayment.parse(text) { return error }
        return nil
    }

    func testParsesMerchantQrAndKeepsRawUnchanged() throws {
        let raw = "upi://pay?pa=shop.123@okaxis&pn=Chai%20Point&mc=5812&tr=TR42&am=49.50&cu=INR&sign=AbC%2B%3D"
        let p = try ok(raw)
        XCTAssertEqual(p.raw, raw)
        XCTAssertEqual(p.payeeAddress, "shop.123@okaxis")
        XCTAssertEqual(p.payeeName, "Chai Point")
        XCTAssertEqual(p.amount, "49.50")
        XCTAssertEqual(p.query, "pa=shop.123@okaxis&pn=Chai%20Point&mc=5812&tr=TR42&am=49.50&cu=INR&sign=AbC%2B%3D")
    }

    func testSchemeAndKeysAreCaseInsensitive() throws {
        let p = try ok("UPI://PAY?PA=a.b@ybl&PN=X")
        XCTAssertEqual(p.payeeAddress, "a.b@ybl")
        XCTAssertEqual(p.payeeName, "X")
    }

    func testAcceptsQrWithoutAmount() throws {
        XCTAssertNil(try ok("upi://pay?pa=someone@paytm").amount)
    }

    func testRejectsNonUpiAndOtherActions() {
        XCTAssertEqual(err("https://example.com"), .notUpi)
        XCTAssertEqual(err("upi://mandate?pa=a@b"), .unsupportedAction("mandate"))
    }

    func testRejectsMissingOrInvalidPayee() {
        XCTAssertEqual(err("upi://pay?pn=X"), .missingPayee)
        XCTAssertEqual(err("upi://pay?pa=9876543210"), .invalidPayee("9876543210"))
    }

    func testRejectsBadAmounts() {
        XCTAssertEqual(err("upi://pay?pa=a@ybl&am=-5"), .invalidAmount("-5"))
        XCTAssertEqual(err("upi://pay?pa=a@ybl&am=1.234"), .invalidAmount("1.234"))
    }

    func testZeroAmountMeansPayerEntersAmount() throws {
        let p = try ok("upi://pay?pa=a@ybl&am=0.00")
        XCTAssertNil(p.amount)
        XCTAssertEqual(p.raw, "upi://pay?pa=a@ybl&am=0.00")
    }

    func testWithAmountAddsAmountAndKeepsOtherParamsExactly() throws {
        let qr = "upi://pay?pa=ravi.k@okaxis&pn=Ravi%20Kumar&mode=02&purpose=00"
        let p = try XCTUnwrap(try? ok(qr).withAmount("150").get())
        XCTAssertEqual(p.raw, "\(qr)&cu=INR&am=150.00")
        XCTAssertEqual(p.amount, "150.00")
    }

    func testWithAmountReplacesZeroAmountAndKeepsCurrency() throws {
        let p = try XCTUnwrap(try? ok("upi://pay?pa=a@ybl&am=0&cu=INR&tn=Hi").withAmount("99.5").get())
        XCTAssertEqual(p.raw, "upi://pay?pa=a@ybl&cu=INR&tn=Hi&am=99.50")
    }

    func testWithAmountRejectsZeroAndGarbage() throws {
        let p = try ok("upi://pay?pa=a@ybl")
        for bad in ["0", "", "12.345", "abc"] {
            if case .success = p.withAmount(bad) { XCTFail("\(bad) should be rejected") }
        }
    }

    func testBuildsManualPaymentWithEncoding() throws {
        let p = try XCTUnwrap(try? UpiPayment.build(payeeAddress: "friend@okhdfcbank", payeeName: "Ravi K",
                                                     amount: "100", note: "Dinner & movie").get())
        XCTAssertEqual(p.raw, "upi://pay?pa=friend@okhdfcbank&pn=Ravi%20K&am=100&cu=INR&tn=Dinner%20%26%20movie")
        XCTAssertEqual(p.note, "Dinner & movie")
    }
}

final class UpiAppsTests: XCTestCase {
    private let qr = "upi://pay?pa=shop@okaxis&pn=Chai%20Point&am=49.50&cu=INR&sign=AbC%2B%3D"

    private func app(_ scheme: String) throws -> UpiAppInfo {
        try XCTUnwrap(UpiApps.catalogue.first { $0.scheme == scheme })
    }

    private func payment(_ text: String) throws -> UpiPayment {
        try XCTUnwrap(try? UpiPayment.parse(text).get())
    }

    func testDocumentedLinkFormats() throws {
        let p = try payment(qr)
        let query = "pa=shop@okaxis&pn=Chai%20Point&am=49.50&cu=INR&sign=AbC%2B%3D"
        XCTAssertEqual(try app("tez").link(for: p)?.absoluteString, "tez://upi/pay?\(query)")
        XCTAssertEqual(try app("phonepe").link(for: p)?.absoluteString, "phonepe://pay?\(query)")
        XCTAssertEqual(try app("paytmmp").link(for: p)?.absoluteString, "paytmmp://upi/pay?\(query)")
        XCTAssertEqual(UpiApps.anyApp.link(for: p)?.absoluteString, qr)
    }

    func testUnencodedSpacesFromAQrStillMakeALink() throws {
        let p = try payment("upi://pay?pa=shop@okaxis&pn=Chai Point&tn=Tea%20and%20snacks")
        XCTAssertEqual(try app("tez").link(for: p)?.absoluteString,
                       "tez://upi/pay?pa=shop@okaxis&pn=Chai%20Point&tn=Tea%20and%20snacks")
    }

    func testEverySchemeIsDeclaredForCanOpenURL() throws {
        let declared = try XCTUnwrap(Bundle.main.object(forInfoDictionaryKey: "LSApplicationQueriesSchemes") as? [String])
        for app in UpiApps.catalogue {
            XCTAssertTrue(declared.contains(app.scheme), "\(app.scheme) missing from LSApplicationQueriesSchemes")
        }
    }
}

@MainActor
final class AppSettingsTests: XCTestCase {
    private func settings() -> AppSettings {
        let suite = "upihub.tests.\(UUID().uuidString)"
        let s = AppSettings(store: UserDefaults(suiteName: suite)!)
        s.installed = UpiApps.catalogue.filter { ["paytmmp", "tez", "phonepe", "credpay", "upi"].contains($0.scheme) }
        return s
    }

    func testNoDefaultUntilPicked() {
        XCTAssertNil(settings().defaultApp)
    }

    func testDefaultComesFirstThenCatalogueOrder() {
        let s = settings()
        s.defaultScheme = "phonepe"
        XCTAssertEqual(s.arranged.map(\.name), ["PhonePe", "Paytm", "CRED", "Google Pay", "Any UPI app"])
    }

    func testMoveSortHideAndReset() {
        let s = settings()
        // Installed, in catalogue order: Paytm, CRED, Google Pay, PhonePe, Any UPI app.
        s.move(from: IndexSet(integer: 2), to: 0)
        XCTAssertEqual(s.arranged.first?.name, "Google Pay")
        s.sortAZ()
        XCTAssertEqual(s.arranged.map(\.name), ["Any UPI app", "CRED", "Google Pay", "Paytm", "PhonePe"])
        s.setVisible("credpay", false)
        XCTAssertFalse(s.visible.map(\.scheme).contains("credpay"))
        s.reset()
        XCTAssertEqual(s.visible.count, 5)
    }

    func testDefaultAppCannotBeHidden() {
        let s = settings()
        s.defaultScheme = "tez"
        s.setVisible("tez", false)
        XCTAssertTrue(s.visible.map(\.scheme).contains("tez"))
    }

    func testChoicesPersist() {
        let suite = "upihub.tests.\(UUID().uuidString)"
        let store = UserDefaults(suiteName: suite)!
        let first = AppSettings(store: store)
        first.defaultScheme = "paytmmp"
        first.alwaysAsk = false
        first.order = ["tez", "paytmmp"]
        first.setVisible("phonepe", false)
        let second = AppSettings(store: store)
        XCTAssertEqual(second.defaultScheme, "paytmmp")
        XCTAssertFalse(second.alwaysAsk)
        XCTAssertEqual(second.order, ["tez", "paytmmp"])
        XCTAssertEqual(second.hidden, ["phonepe"])
    }
}
