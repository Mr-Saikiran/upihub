import SwiftUI

/// Pay to a typed UPI ID.
struct ManualEntryView: View {
    let onContinue: (UpiPayment) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var upiId = ""
    @State private var name = ""
    @State private var amount = ""
    @State private var note = ""
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("UPI ID (e.g. name@okbank)", text: $upiId)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.emailAddress)
                    TextField("Payee name (optional)", text: $name)
                    TextField("Amount in ₹ (optional)", text: $amount)
                        .keyboardType(.decimalPad)
                    TextField("Note (optional)", text: $note)
                } footer: {
                    if let error { Text(error).foregroundStyle(.red) }
                }
            }
            .navigationTitle("Pay to UPI ID")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Continue", action: submit) }
            }
        }
    }

    private func submit() {
        let id = upiId.trimmingCharacters(in: .whitespaces)
        if !id.isEmpty && id.allSatisfy(\.isNumber) {
            // A bare mobile number is not a UPI address; the handle is required.
            error = "Enter the full UPI ID, e.g. \(id)@paytm"
            return
        }
        switch UpiPayment.build(payeeAddress: id, payeeName: name, amount: amount, note: note) {
        case .success(let payment): onContinue(payment)
        case .failure(let failure): error = failure.message
        }
    }
}
