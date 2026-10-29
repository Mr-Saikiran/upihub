import SwiftUI

/// "Which app should pay?" with the default preselected, plus an amount
/// field when the code has none. The pay button stays pinned below the list.
struct ChooserSheet: View {
    let payment: UpiPayment
    let apps: [UpiAppInfo]
    let defaultScheme: String?
    let onPay: (UpiPayment, UpiAppInfo, Bool) -> Void

    @State private var selected: String
    @State private var amountText = ""
    @State private var remember: Bool
    @FocusState private var amountFocused: Bool

    init(payment: UpiPayment, apps: [UpiAppInfo], defaultScheme: String?,
         onPay: @escaping (UpiPayment, UpiAppInfo, Bool) -> Void) {
        self.payment = payment
        self.apps = apps
        self.defaultScheme = defaultScheme
        self.onPay = onPay
        let initial = apps.first { $0.scheme == defaultScheme }?.scheme ?? apps.first?.scheme ?? ""
        _selected = State(initialValue: initial)
        // With no default yet, the first app picked becomes it unless switched off.
        _remember = State(initialValue: defaultScheme == nil)
    }

    private var needsAmount: Bool { payment.amount == nil }
    private var selectedApp: UpiAppInfo? { apps.first { $0.scheme == selected } }

    /// The payment to send, or nil while a required amount is missing or invalid.
    private var toSend: UpiPayment? {
        guard needsAmount else { return payment }
        if case .success(let withAmount) = payment.withAmount(amountText) { return withAmount }
        return nil
    }

    private var buttonTitle: String {
        let name = selectedApp?.name ?? "app"
        if needsAmount, let amount = toSend?.amount { return "Pay ₹\(amount) with \(name)" }
        return "Continue with \(name)"
    }

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    header
                    if needsAmount { amountField }
                    Text("Pay using").font(.subheadline.weight(.semibold)).foregroundStyle(.secondary)
                    VStack(spacing: 6) {
                        ForEach(apps) { app in row(app) }
                    }
                }
                .padding(20)
            }
            VStack(spacing: 10) {
                if selected != defaultScheme {
                    Toggle("Make this my default", isOn: $remember)
                }
                Button {
                    if let payment = toSend, let app = selectedApp {
                        onPay(payment, app, remember && selected != defaultScheme)
                    }
                } label: {
                    Text(buttonTitle).font(.headline).frame(maxWidth: .infinity, minHeight: 50)
                }
                .buttonStyle(.borderedProminent)
                .disabled(toSend == nil || selectedApp == nil)
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
            .background(.bar)
        }
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
        .onAppear { if needsAmount { amountFocused = true } }
    }

    private var header: some View {
        let title = payment.payeeName ?? payment.payeeAddress
        let detail = [payment.payeeName == nil ? nil : payment.payeeAddress, payment.note].compactMap { $0 }
        return HStack(spacing: 14) {
            Text(String(title.first ?? "U").uppercased())
                .font(.title2.weight(.semibold))
                .foregroundStyle(Brand.primary)
                .frame(width: 48, height: 48)
                .background(Brand.primary.opacity(0.15), in: Circle())
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.headline).lineLimit(1)
                if !detail.isEmpty {
                    Text(detail.joined(separator: " · ")).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            Spacer()
            if let amount = payment.amount {
                Text("₹\(amount)").font(.title2.bold()).monospacedDigit()
            }
        }
        .padding(.top, 8)
    }

    private var amountField: some View {
        HStack(spacing: 6) {
            Text("₹").font(.title2.weight(.semibold))
            TextField("Enter amount", text: $amountText)
                .keyboardType(.decimalPad)
                .font(.title2)
                .focused($amountFocused)
        }
        .padding(14)
        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(Brand.primary, lineWidth: 2))
    }

    private func row(_ app: UpiAppInfo) -> some View {
        let isSelected = app.scheme == selected
        return Button { selected = app.scheme } label: {
            HStack(spacing: 14) {
                AppBadge(name: app.name, size: 34)
                VStack(alignment: .leading, spacing: 2) {
                    Text(app.name).font(.body.weight(.semibold)).foregroundStyle(.primary)
                    if app.scheme == defaultScheme {
                        Text("Default").font(.caption).foregroundStyle(.secondary)
                    } else if app.style == .generic {
                        Text("iOS picks which UPI app opens").font(.caption).foregroundStyle(.secondary)
                    }
                }
                Spacer()
                if isSelected {
                    Image(systemName: "checkmark.circle.fill").font(.title3).foregroundStyle(Brand.primary)
                }
            }
            .padding(12)
            .contentShape(Rectangle())
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(isSelected ? Brand.primary : Color(.separator), lineWidth: isSelected ? 2 : 1))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}
