import SwiftUI
import Sparkle

@MainActor
final class AppUpdater: ObservableObject {
    @Published private(set) var canCheck = false
    private let controller: SPUStandardUpdaterController?

    init() {
        // Sample app captures never load personal settings or contact update feeds.
        guard Bundle.main.bundleIdentifier == "dev.macdroid.mac",
              Bundle.main.object(forInfoDictionaryKey: "HoppairUpdateChecksEnabled") as? Bool == true,
              Bundle.main.object(forInfoDictionaryKey: "SUPublicEDKey") as? String != nil else {
            controller = nil
            return
        }
        let controller = SPUStandardUpdaterController(startingUpdater: false, updaterDelegate: nil, userDriverDelegate: nil)
        self.controller = controller
        controller.updater.publisher(for: \.canCheckForUpdates).assign(to: &$canCheck)
        controller.startUpdater()
    }

    func checkForUpdates() { controller?.checkForUpdates(nil) }
}

struct CheckForUpdatesButton: View {
    @ObservedObject var updater: AppUpdater
    var body: some View {
        Button("Check for Updates…", action: updater.checkForUpdates).disabled(!updater.canCheck)
    }
}
