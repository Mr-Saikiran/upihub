import UIKit

/// A UPI app UPI Hub can open on iOS. iOS has no "every app that handles
/// upi://pay" query, so each app is reached through its own URL scheme.
struct UpiAppInfo: Identifiable, Hashable {
    enum LinkStyle: Hashable {
        /// `scheme://upi/pay?<query>`
        case upiPath
        /// `scheme://pay?<query>`
        case payPath
        /// The original `upi://pay?<query>`; iOS decides which app opens it.
        case generic
    }

    let name: String
    let scheme: String
    let style: LinkStyle
    /// True when a payment gateway's iOS documentation confirms the link
    /// format. Otherwise the scheme is documented for detection and the link
    /// follows the common `scheme://upi/pay` form.
    let verifiedLink: Bool

    var id: String { scheme }

    func link(for payment: UpiPayment) -> URL? {
        switch style {
        case .upiPath: return UpiApps.url("\(scheme)://upi/pay?\(payment.query)")
        case .payPath: return UpiApps.url("\(scheme)://pay?\(payment.query)")
        case .generic: return UpiApps.url(payment.raw)
        }
    }
}

enum UpiApps {
    /// Schemes from Razorpay's iOS UPI intent guide. Link formats for
    /// Google Pay (`tez://upi/pay`), PhonePe (`phonepe://pay`) and Paytm
    /// (`paytmmp://upi/pay`) are given there; the others are listed for
    /// detection only. INDmoney and super.money publish no iOS scheme, so
    /// they are reachable through "Any UPI app". Every scheme here must also
    /// be in LSApplicationQueriesSchemes (project.yml).
    static let known: [UpiAppInfo] = [
        UpiAppInfo(name: "Kiwi", scheme: "kiwi", style: .upiPath, verifiedLink: false),
        UpiAppInfo(name: "Paytm", scheme: "paytmmp", style: .upiPath, verifiedLink: true),
        UpiAppInfo(name: "CRED", scheme: "credpay", style: .upiPath, verifiedLink: false),
        UpiAppInfo(name: "MobiKwik", scheme: "mobikwik", style: .upiPath, verifiedLink: false),
        UpiAppInfo(name: "Jupiter", scheme: "jupiter", style: .upiPath, verifiedLink: false),
        UpiAppInfo(name: "Google Pay", scheme: "tez", style: .upiPath, verifiedLink: true),
        UpiAppInfo(name: "PhonePe", scheme: "phonepe", style: .payPath, verifiedLink: true),
        UpiAppInfo(name: "BHIM", scheme: "bhim", style: .upiPath, verifiedLink: false),
        UpiAppInfo(name: "Amazon Pay", scheme: "amazonpay", style: .upiPath, verifiedLink: false),
    ]

    static let anyApp = UpiAppInfo(name: "Any UPI app", scheme: "upi", style: .generic, verifiedLink: true)

    static var catalogue: [UpiAppInfo] { known + [anyApp] }

    /// Apps whose scheme this iPhone can open, in catalogue order.
    @MainActor static func installed() -> [UpiAppInfo] {
        catalogue.filter { app in
            guard let probe = URL(string: "\(app.scheme)://") else { return false }
            return UIApplication.shared.canOpenURL(probe)
        }
    }

    /// A URL for link text that may contain characters a scanned QR leaves
    /// unencoded (spaces, non-ASCII). Existing `%` escapes are kept. Encoded
    /// here rather than left to URL(string:), whose leniency differs between
    /// iOS versions (iOS 17 started encoding invalid characters itself).
    static func url(_ text: String) -> URL? {
        var allowed = CharacterSet.urlQueryAllowed
        allowed.insert("%")
        return text.addingPercentEncoding(withAllowedCharacters: allowed).flatMap { URL(string: $0) }
    }
}
