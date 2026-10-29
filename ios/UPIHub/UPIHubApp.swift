import SwiftUI

@main
struct UPIHubApp: App {
    @StateObject private var settings = AppSettings()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(settings)
                .tint(Brand.primary)
        }
    }
}

/// The Android app's brand colours.
enum Brand {
    static let primary = Color(red: 0x5B / 255, green: 0x3F / 255, blue: 0xD1 / 255)
    static let secondary = Color(red: 0x00 / 255, green: 0x89 / 255, blue: 0x7B / 255)
}

/// A letter avatar: iOS does not let one app read another app's icon.
struct AppBadge: View {
    let name: String
    var size: CGFloat = 40

    private static let palette: [Color] = [
        Brand.primary, Brand.secondary, .orange, .blue, .pink, .indigo, .teal, .brown,
    ]

    var body: some View {
        Text(String(name.first ?? "U").uppercased())
            .font(.system(size: size * 0.45, weight: .bold))
            .foregroundStyle(.white)
            .frame(width: size, height: size)
            .background(Self.palette[stableIndex], in: Circle())
            .accessibilityHidden(true)
    }

    /// Same colour for the same name on every launch (String.hashValue is randomised).
    private var stableIndex: Int {
        name.unicodeScalars.reduce(0) { ($0 &* 31 &+ Int($1.value)) & 0x7fffffff } % Self.palette.count
    }
}

extension Bundle {
    var versionString: String {
        let version = infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
        let build = infoDictionary?["CFBundleVersion"] as? String ?? "?"
        return "\(version) (\(build))"
    }
}
