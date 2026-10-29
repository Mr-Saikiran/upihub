import AVFoundation
import PhotosUI
import SwiftUI
import UIKit

enum CameraState: Equatable {
    case unknown, authorized, denied, unavailable

    static var current: CameraState {
        guard AVCaptureDevice.default(for: .video) != nil else { return .unavailable }
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: return .authorized
        case .notDetermined: return .unknown
        default: return .denied
        }
    }
}

struct PaymentRequest: Identifiable {
    let id = UUID()
    let payment: UpiPayment
}

/// Opens straight into a live QR scanner. A code from the camera, a picked
/// screenshot or a typed UPI ID all go to the chooser sheet.
struct ContentView: View {
    @EnvironmentObject private var settings: AppSettings
    @Environment(\.scenePhase) private var scenePhase

    @State private var camera = CameraState.current
    @State private var request: PaymentRequest?
    @State private var pendingManual: UpiPayment?
    @State private var torchOn = false
    @State private var photoItem: PhotosPickerItem?
    @State private var showManual = false
    @State private var showSettings = false
    @State private var showDefaultPicker = false
    @State private var notice: String?
    @State private var status: String?
    @State private var lastCode: String?
    @State private var ignoreUntil = Date.distantPast

    private var sheetOpen: Bool { request != nil || showManual || showSettings || showDefaultPicker }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            cameraLayer
            VStack(spacing: 0) {
                topBar
                Spacer()
                if camera == .authorized { hint }
                cameraActions
                panel
            }
        }
        .sheet(item: $request, onDismiss: { ignoreUntil = Date().addingTimeInterval(2.5) }) { request in
            ChooserSheet(payment: request.payment, apps: settings.visible, defaultScheme: settings.defaultScheme) { payment, app, remember in
                pay(payment, with: app, remember: remember)
            }
        }
        .sheet(isPresented: $showManual, onDismiss: {
            if let payment = pendingManual { pendingManual = nil; present(payment) }
        }) {
            ManualEntryView { payment in
                pendingManual = payment
                showManual = false
            }
        }
        .sheet(isPresented: $showSettings) {
            SettingsView().environmentObject(settings)
        }
        .sheet(isPresented: $showDefaultPicker) {
            DefaultPickerView().environmentObject(settings).presentationDetents([.medium, .large])
        }
        .alert(notice ?? "", isPresented: Binding(get: { notice != nil }, set: { if !$0 { notice = nil } })) {
            Button("OK", role: .cancel) {}
        }
        .onChange(of: photoItem) { item in
            Task { await readPhoto(item) }
        }
        .onChange(of: scenePhase) { phase in
            guard phase == .active else { return }
            settings.refreshInstalled()
            camera = .current
        }
        .task {
            settings.refreshInstalled()
            if camera == .unknown {
                _ = await AVCaptureDevice.requestAccess(for: .video)
                camera = .current
            }
            // No default yet (or it was uninstalled): pick one from the UPI apps on this iPhone.
            if settings.defaultApp == nil && !settings.installed.isEmpty { showDefaultPicker = true }
        }
    }

    // MARK: - Camera

    @ViewBuilder private var cameraLayer: some View {
        switch camera {
        case .authorized:
            ScannerView(active: !sheetOpen, torchOn: torchOn, onCode: handleCode)
                .ignoresSafeArea()
            ScanFrame(detected: request != nil)
        case .denied:
            cameraMessage(title: "Scan with your camera",
                          body: "UPI Hub needs the camera to read QR codes. Photos are never saved or sent anywhere.",
                          action: "Open Settings") {
                if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
            }
        case .unavailable:
            cameraMessage(title: "No camera available",
                          body: "Pay from a screenshot or type a UPI ID instead.", action: nil) {}
        case .unknown:
            EmptyView()
        }
    }

    private func cameraMessage(title: String, body: String, action: String?, perform: @escaping () -> Void) -> some View {
        VStack(spacing: 14) {
            Image(systemName: "camera.fill").font(.system(size: 44)).foregroundStyle(Brand.primary.opacity(0.8))
            Text(title).font(.title2.bold()).foregroundStyle(.white)
            Text(body).font(.subheadline).foregroundStyle(.white.opacity(0.8)).multilineTextAlignment(.center)
            if let action { Button(action, action: perform).buttonStyle(.borderedProminent) }
        }
        .padding(32)
        .offset(y: -90)
    }

    private var topBar: some View {
        HStack(spacing: 10) {
            Image("Logo").resizable().frame(width: 32, height: 32)
                .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                .accessibilityHidden(true)
            Text("UPI Hub").font(.title2.bold()).foregroundStyle(.white)
            Spacer()
            Button { showSettings = true } label: {
                Image(systemName: "gearshape.fill").font(.title2).foregroundStyle(.white)
            }
            .accessibilityLabel("Settings")
        }
        .padding(.horizontal, 20)
        .padding(.top, 8)
    }

    private var hint: some View {
        Text("Point at any UPI QR code")
            .font(.subheadline.weight(.medium))
            .foregroundStyle(.white)
            .padding(.horizontal, 16).padding(.vertical, 8)
            .background(.black.opacity(0.5), in: Capsule())
            .padding(.bottom, 16)
    }

    private var cameraActions: some View {
        HStack(spacing: 24) {
            PhotosPicker(selection: $photoItem, matching: .images) { roundIcon("photo.on.rectangle") }
                .accessibilityLabel("Pay from screenshot")
            if camera == .authorized, AVCaptureDevice.default(for: .video)?.hasTorch == true {
                Button { torchOn.toggle() } label: { roundIcon(torchOn ? "flashlight.on.fill" : "flashlight.off.fill") }
                    .accessibilityLabel(torchOn ? "Turn flashlight off" : "Turn flashlight on")
            }
        }
        .padding(.bottom, 24)
    }

    private func roundIcon(_ name: String) -> some View {
        Image(systemName: name).font(.title3).foregroundStyle(.white)
            .frame(width: 56, height: 56)
            .background(.white.opacity(0.2), in: Circle())
    }

    // MARK: - Bottom panel

    private var panel: some View {
        VStack(spacing: 12) {
            Capsule().fill(Color.secondary.opacity(0.4)).frame(width: 32, height: 4)
            Button { showDefaultPicker = true } label: {
                HStack(spacing: 14) {
                    if let app = settings.defaultApp {
                        AppBadge(name: app.name, size: 44)
                    } else {
                        Image("Logo").resizable().frame(width: 44, height: 44)
                            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                    }
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Pays with").font(.caption).foregroundStyle(.secondary)
                        Text(settings.defaultApp?.name ?? "Choose default app").font(.headline).foregroundStyle(.primary)
                    }
                    Spacer()
                    Text("Change").font(.subheadline.weight(.semibold)).foregroundStyle(Brand.primary)
                    Image(systemName: "chevron.right").font(.subheadline.weight(.semibold)).foregroundStyle(Brand.primary)
                }
                .padding(14)
                .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
            }
            .buttonStyle(.plain)

            Button { showManual = true } label: {
                Label("Pay to UPI ID", systemImage: "at").font(.headline).frame(maxWidth: .infinity, minHeight: 50)
            }
            .buttonStyle(.bordered)
            .tint(Brand.secondary)

            if let status {
                Text(status).font(.footnote).foregroundStyle(.secondary).frame(maxWidth: .infinity, alignment: .leading)
            }
            Label("UPI Hub never sees your money or PIN. It opens the UPI app you pick, and that app completes the payment.",
                  systemImage: "lock.fill")
                .font(.caption).foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 20)
        .padding(.top, 10)
        .padding(.bottom, 8)
        .background(
            RoundedRectangle(cornerRadius: 28, style: .continuous)
                .fill(Color(.systemBackground))
                .ignoresSafeArea(edges: .bottom))
    }

    // MARK: - Payments

    private func handleCode(_ text: String) {
        guard !sheetOpen else { return }
        if text == lastCode && Date() < ignoreUntil { return }
        lastCode = text
        switch UpiPayment.parse(text) {
        case .success(let payment):
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            present(payment)
        case .failure(let error):
            ignoreUntil = Date().addingTimeInterval(3)
            status = error.message
        }
    }

    private func present(_ payment: UpiPayment) {
        lastCode = payment.raw
        let apps = settings.visible
        guard !apps.isEmpty else {
            notice = "No UPI app on this iPhone accepts payment links. Install one to continue."
            return
        }
        // Without an amount the sheet is still needed to collect one.
        if !settings.alwaysAsk, payment.amount != nil, let app = settings.defaultApp {
            pay(payment, with: app, remember: false)
            return
        }
        request = PaymentRequest(payment: payment)
    }

    private func pay(_ payment: UpiPayment, with app: UpiAppInfo, remember: Bool) {
        if remember {
            settings.defaultScheme = app.scheme
            settings.setVisible(app.scheme, true)
        }
        request = nil
        ignoreUntil = Date().addingTimeInterval(5)
        guard let url = app.link(for: payment) else {
            notice = "This payment link could not be opened."
            return
        }
        UIApplication.shared.open(url) { opened in
            // iOS apps do not report the payment result back, so only the hand-off is known.
            status = opened ? "Opened \(app.name). Finish the payment there." : "\(app.name) could not open this payment."
        }
    }

    private func readPhoto(_ item: PhotosPickerItem?) async {
        guard let item else { return }
        defer { photoItem = nil }
        guard let data = try? await item.loadTransferable(type: Data.self),
              let image = UIImage(data: data),
              let text = QRImageDecoder.decode(image) else {
            notice = "No QR code found in this image."
            return
        }
        switch UpiPayment.parse(text) {
        case .success(let payment): present(payment)
        case .failure(let error): notice = error.message
        }
    }
}
