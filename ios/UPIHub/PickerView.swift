import SwiftUI
import UIKit

/// Home: pick the UPI app first; its own scanner then starts the payment.
///
/// Payment apps decline payments handed over by another app far more often
/// than QRs scanned in the app, so the reliable path is to open the chosen
/// app and scan there. iOS can only open an app through its URL scheme, which
/// lands on the app's home screen, one tap from Scan.
struct PickerView: View {
    @EnvironmentObject private var settings: AppSettings
    @Environment(\.scenePhase) private var scenePhase
    @State private var scanHere = false
    @State private var notice: String?

    private let columns = [GridItem(.adaptive(minimum: 100), spacing: 12)]

    /// "Any UPI app" cannot be opened on its own: it has no app behind it.
    private var apps: [UpiAppInfo] { settings.visible.filter { $0.style != .generic } }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Scan with").font(.largeTitle.bold())
                    Text("Pick an app and tap Scan in it. The app scans the QR itself, so payment apps treat it as their own scan, not a payment handed over by another app.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    if apps.isEmpty {
                        Text("No UPI apps on this iPhone can be opened from here.")
                            .foregroundStyle(.secondary)
                            .padding(.top, 24)
                    } else {
                        LazyVGrid(columns: columns, spacing: 12) {
                            ForEach(apps) { app in tile(app) }
                        }
                        .padding(.top, 12)
                    }
                }
                .padding(20)
            }
            .safeAreaInset(edge: .bottom) { footer }
            .navigationTitle("UPI Hub")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    NavigationLink { ManageAppsView() } label: { Image(systemName: "gearshape") }
                        .accessibilityLabel("Payment apps")
                }
            }
        }
        .fullScreenCover(isPresented: $scanHere) {
            ContentView(onClose: { scanHere = false }).environmentObject(settings)
        }
        .alert(notice ?? "", isPresented: Binding(get: { notice != nil }, set: { if !$0 { notice = nil } })) {
            Button("OK", role: .cancel) {}
        }
        .task { settings.refreshInstalled() }
        .onChange(of: scenePhase) { phase in
            if phase == .active { settings.refreshInstalled() }
        }
    }

    private func tile(_ app: UpiAppInfo) -> some View {
        Button { open(app) } label: {
            VStack(spacing: 8) {
                AppBadge(name: app.name, size: 56)
                Text(app.name).font(.subheadline.weight(.semibold)).foregroundStyle(.primary).lineLimit(1)
                if app.scheme == settings.defaultScheme {
                    Text("Default").font(.caption2.weight(.semibold)).foregroundStyle(Brand.primary)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 16)
            .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Scan with \(app.name)")
        .contextMenu {
            if app.scheme != settings.defaultScheme {
                Button("Make default") { settings.defaultScheme = app.scheme }
                Button("Hide from this list") { settings.setVisible(app.scheme, false) }
            }
        }
    }

    private var footer: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button { scanHere = true } label: {
                Label("Scan here instead", systemImage: "qrcode.viewfinder")
                    .font(.headline)
                    .frame(maxWidth: .infinity, minHeight: 48)
            }
            .buttonStyle(.bordered)
            .tint(Brand.secondary)
            Text("Scans in UPI Hub and hands the payment over. Some apps decline these, mostly for small shops and credit-card UPI.")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 12)
        .background(.bar)
    }

    /// Opens the app through its URL scheme so its own scanner starts the payment.
    private func open(_ app: UpiAppInfo) {
        guard let url = URL(string: "\(app.scheme)://") else { return }
        UIApplication.shared.open(url) { opened in
            if !opened { notice = "\(app.name) could not be opened." }
        }
    }
}
