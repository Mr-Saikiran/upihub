import Foundation

/// A parsed `upi://pay` request, the iOS twin of the Android `UpiPayment`.
///
/// `raw` is the exact text that was scanned or typed. Its query is forwarded
/// to the chosen UPI app byte for byte, so merchant parameters and QR
/// signatures (`sign`, `mc`, `tr`, ...) stay valid. The other fields are only
/// used for display.
struct UpiPayment: Equatable {
    let raw: String
    let payeeAddress: String
    let payeeName: String?
    let amount: String?
    let note: String?

    enum ParseError: Error, Equatable {
        case notUpi
        case unsupportedAction(String)
        case missingPayee
        case invalidPayee(String)
        case invalidAmount(String)

        var message: String {
            switch self {
            case .notUpi: return "This QR code is not a UPI payment code."
            case .unsupportedAction(let action): return "UPI \"\(action)\" requests are not supported; only payments are."
            case .missingPayee: return "The UPI code has no payee address (pa)."
            case .invalidPayee(let value): return "\"\(value)\" is not a valid UPI ID."
            case .invalidAmount(let value): return "\"\(value)\" is not a valid amount."
            }
        }
    }

    /// Everything after the first `?`, exactly as scanned.
    var query: String {
        guard let mark = raw.firstIndex(of: "?") else { return "" }
        return String(raw[raw.index(after: mark)...])
    }

    /// This payment with the payer-entered amount. Only used for codes
    /// without one: some UPI apps lock the amount field for links from other
    /// apps, so it has to be in the link. Every other parameter is kept byte
    /// for byte; a zero `am` is replaced.
    func withAmount(_ value: String) -> Result<UpiPayment, ParseError> {
        let am = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard Self.isValidAmount(am), let formatted = Self.formatAmount(am) else {
            return .failure(.invalidAmount(am))
        }
        let base = raw.firstIndex(of: "?").map { String(raw[..<$0]) } ?? raw
        var params = query.split(separator: "&").map(String.init).filter { Self.key(of: $0) != "am" }
        if !params.contains(where: { Self.key(of: $0) == "cu" }) { params.append("cu=INR") }
        params.append("am=\(formatted)")
        return Self.parse(base + "?" + params.joined(separator: "&"))
    }

    // MARK: - Parsing

    private static let schemePattern = try! NSRegularExpression(
        pattern: "^upi://([^/?#]+)/?(?:\\?(.*))?$", options: [.caseInsensitive])
    private static let vpaPattern = try! NSRegularExpression(
        pattern: "^[A-Za-z0-9._-]{1,256}@[A-Za-z0-9.-]{2,64}$")
    private static let amountPattern = try! NSRegularExpression(pattern: "^[0-9]{1,9}(\\.[0-9]{1,2})?$")

    static func parse(_ text: String) -> Result<UpiPayment, ParseError> {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let ns = trimmed as NSString
        guard let match = schemePattern.firstMatch(in: trimmed, range: NSRange(location: 0, length: ns.length)) else {
            return .failure(.notUpi)
        }
        let action = ns.substring(with: match.range(at: 1))
        guard action.lowercased() == "pay" else { return .failure(.unsupportedAction(action)) }
        let queryRange = match.range(at: 2)
        let params = parseQuery(queryRange.location == NSNotFound ? "" : ns.substring(with: queryRange))

        guard let pa = params["pa"]?.trimmingCharacters(in: .whitespacesAndNewlines), !pa.isEmpty else {
            return .failure(.missingPayee)
        }
        guard matches(vpaPattern, pa) else { return .failure(.invalidPayee(pa)) }
        let am = params["am"]?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        if let am, !isValidAmount(am) { return .failure(.invalidAmount(am)) }
        // Some static merchant QRs carry am=0 meaning "payer enters the amount".
        let displayAmount = am.flatMap { formatAmount($0) == nil ? nil : $0 }
        return .success(UpiPayment(
            raw: trimmed,
            payeeAddress: pa,
            payeeName: params["pn"]?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty,
            amount: displayAmount,
            note: params["tn"]?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty))
    }

    /// Builds a payment request for a UPI ID typed by the user.
    static func build(payeeAddress: String, payeeName: String?, amount: String?, note: String?) -> Result<UpiPayment, ParseError> {
        var pairs: [(String, String)] = [("pa", payeeAddress.trimmingCharacters(in: .whitespaces))]
        if let v = payeeName?.trimmingCharacters(in: .whitespaces).nilIfEmpty { pairs.append(("pn", v)) }
        if let v = amount?.trimmingCharacters(in: .whitespaces).nilIfEmpty { pairs.append(("am", v)) }
        pairs.append(("cu", "INR"))
        if let v = note?.trimmingCharacters(in: .whitespaces).nilIfEmpty { pairs.append(("tn", v)) }
        return parse("upi://pay?" + pairs.map { "\($0.0)=\(encode($0.1))" }.joined(separator: "&"))
    }

    static func isValidAmount(_ value: String) -> Bool { matches(amountPattern, value) }

    /// "99.5" → "99.50", "007" → "7.00"; nil for zero. Expects a valid amount.
    static func formatAmount(_ value: String) -> String? {
        let parts = value.split(separator: ".", omittingEmptySubsequences: false)
        guard let whole = Int(parts[0]) else { return nil }
        var fraction = parts.count > 1 ? String(parts[1]) : ""
        while fraction.count < 2 { fraction += "0" }
        if whole == 0 && fraction == "00" { return nil }
        return "\(whole).\(fraction)"
    }

    private static func matches(_ pattern: NSRegularExpression, _ value: String) -> Bool {
        pattern.firstMatch(in: value, range: NSRange(location: 0, length: (value as NSString).length)) != nil
    }

    private static func key(of part: String) -> String {
        decode(String(part.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)[0])).lowercased()
    }

    /// First value wins for repeated keys; keys are case-insensitive.
    private static func parseQuery(_ query: String) -> [String: String] {
        var out: [String: String] = [:]
        for part in query.split(separator: "&") {
            let pieces = part.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
            let key = decode(String(pieces[0])).lowercased()
            let value = pieces.count > 1 ? decode(String(pieces[1])) : ""
            if out[key] == nil { out[key] = value }
        }
        return out
    }

    /// Form decoding, like Java's URLDecoder: `+` is a space.
    private static func decode(_ s: String) -> String {
        s.replacingOccurrences(of: "+", with: " ").removingPercentEncoding ?? s
    }

    /// Matches the Android encoder: ASCII letters, digits and `.-*_@` stay, a space is `%20`.
    private static let unreserved = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789.-*_@")
    private static func encode(_ s: String) -> String {
        s.addingPercentEncoding(withAllowedCharacters: unreserved) ?? s
    }
}

extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
