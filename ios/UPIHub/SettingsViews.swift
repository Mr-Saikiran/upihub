import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var settings: AppSettings
    @Environment(\.dismiss) private var dismiss
    @State private var showDefaultPicker = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button { showDefaultPicker = true } label: {
                        HStack {
                            Text("Default app").foregroundStyle(.primary)
                            Spacer()
                            Text(settings.defaultApp?.name ?? "Not set").foregroundStyle(.secondary)
                        }
                    }
                    Toggle("Ask which app every time", isOn: $settings.alwaysAsk)
                } footer: {
                    Text("Off: scanning a code with an amount opens your default app directly.")
                }
                Section {
                    NavigationLink("Payment apps") { ManageAppsView() }
                } footer: {
                    Text("Add, remove and reorder the apps offered for payment.")
                }
                Section("About") {
                    LabeledContent("Version", value: Bundle.main.versionString)
                    Link("Privacy policy", destination: URL(string: "https://techswitch.in/upihub/privacy/")!)
                    Link("Source code", destination: URL(string: "https://github.com/Mr-Saikiran/upihub")!)
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
            .sheet(isPresented: $showDefaultPicker) {
                DefaultPickerView().environmentObject(settings).presentationDetents([.medium, .large])
            }
        }
    }
}

/// Reorder, show or hide the apps offered for payment.
struct ManageAppsView: View {
    @EnvironmentObject private var settings: AppSettings

    var body: some View {
        List {
            Section {
                ForEach(settings.arranged) { app in
                    HStack(spacing: 12) {
                        AppBadge(name: app.name, size: 32)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(app.name)
                            Text(status(of: app)).font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        // The default app is always offered; change the default first to hide it.
                        if app.scheme != settings.defaultScheme {
                            Toggle(app.name, isOn: Binding(
                                get: { !settings.hidden.contains(app.scheme) },
                                set: { settings.setVisible(app.scheme, $0) }))
                                .labelsHidden()
                        }
                    }
                }
                .onMove { settings.move(from: $0, to: $1) }
            } footer: {
                Text("Drag to reorder. Switch an app off to remove it from the payment list. Only apps on this iPhone are listed.")
            }
        }
        .overlay {
            if settings.arranged.isEmpty {
                Text("No UPI apps on this iPhone accept payment links.").foregroundStyle(.secondary).padding()
            }
        }
        .environment(\.editMode, .constant(.active))
        .navigationTitle("Payment apps")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Menu {
                    Button("Sort A–Z") { settings.sortAZ() }
                    Button("Reset to default", role: .destructive) { settings.reset() }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
                .accessibilityLabel("Sort and reset")
            }
        }
    }

    private func status(of app: UpiAppInfo) -> String {
        if app.scheme == settings.defaultScheme { return "Default · always shown" }
        if app.style == .generic { return "iOS picks which UPI app opens" }
        return settings.hidden.contains(app.scheme) ? "Hidden" : "Shown in payment list"
    }
}

/// Every UPI app on this iPhone, to choose the default from.
struct DefaultPickerView: View {
    @EnvironmentObject private var settings: AppSettings
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List(settings.arranged) { app in
                Button {
                    settings.defaultScheme = app.scheme
                    settings.setVisible(app.scheme, true)
                    dismiss()
                } label: {
                    HStack(spacing: 14) {
                        AppBadge(name: app.name)
                        Text(app.name).foregroundStyle(.primary)
                        Spacer()
                        if app.scheme == settings.defaultScheme {
                            Image(systemName: "checkmark.circle.fill").foregroundStyle(Brand.primary)
                        }
                    }
                }
            }
            .overlay {
                if settings.arranged.isEmpty {
                    Text("No UPI apps on this iPhone accept payment links.").foregroundStyle(.secondary).padding()
                }
            }
            .navigationTitle("Choose your default UPI app")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            }
        }
    }
}
