import SwiftUI

/// The user's choices, stored in UserDefaults: default app, app order,
/// hidden apps and whether to ask every time. Mirrors the Android `Prefs`.
@MainActor
final class AppSettings: ObservableObject {
    private enum Keys {
        static let defaultScheme = "default_scheme"
        static let alwaysAsk = "always_ask"
        static let order = "app_order"
        static let hidden = "hidden_apps"
    }

    private let store: UserDefaults

    /// Scheme of the app preselected for payments; nil until the user picks one.
    @Published var defaultScheme: String? { didSet { store.set(defaultScheme, forKey: Keys.defaultScheme) } }
    /// When false and the code has an amount, scanning opens the default app directly.
    @Published var alwaysAsk: Bool { didSet { store.set(alwaysAsk, forKey: Keys.alwaysAsk) } }
    @Published var order: [String] { didSet { store.set(order, forKey: Keys.order) } }
    @Published var hidden: Set<String> { didSet { store.set(hidden.sorted(), forKey: Keys.hidden) } }
    /// Apps this iPhone can open, in catalogue order. Refreshed when the app becomes active.
    @Published var installed: [UpiAppInfo] = []

    init(store: UserDefaults = .standard) {
        self.store = store
        defaultScheme = store.string(forKey: Keys.defaultScheme)
        alwaysAsk = store.object(forKey: Keys.alwaysAsk) as? Bool ?? true
        order = store.stringArray(forKey: Keys.order) ?? []
        hidden = Set(store.stringArray(forKey: Keys.hidden) ?? [])
    }

    func refreshInstalled() {
        installed = UpiApps.installed()
    }

    /// Installed apps in the user's order. Apps never arranged follow it:
    /// the default first, then catalogue order.
    var arranged: [UpiAppInfo] {
        installed.enumerated()
            .sorted { rank($0.element, $0.offset) < rank($1.element, $1.offset) }
            .map(\.element)
    }

    private func rank(_ app: UpiAppInfo, _ index: Int) -> (Int, Int, Int) {
        (order.firstIndex(of: app.scheme) ?? Int.max, app.scheme == defaultScheme ? 0 : 1, index)
    }

    /// Apps offered for payment. Should every app be hidden, all are offered rather than none.
    var visible: [UpiAppInfo] {
        let shown = arranged.filter { $0.scheme == defaultScheme || !hidden.contains($0.scheme) }
        return shown.isEmpty ? arranged : shown
    }

    /// The default app if it is still installed.
    var defaultApp: UpiAppInfo? { installed.first { $0.scheme == defaultScheme } }

    func setVisible(_ scheme: String, _ visible: Bool) {
        if visible { hidden.remove(scheme) } else { hidden.insert(scheme) }
    }

    func move(from source: IndexSet, to destination: Int) {
        var schemes = arranged.map(\.scheme)
        schemes.move(fromOffsets: source, toOffset: destination)
        order = schemes
    }

    func sortAZ() {
        order = arranged.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }.map(\.scheme)
    }

    /// Forgets the arranged order and every hide choice.
    func reset() {
        order = []
        hidden = []
    }
}
