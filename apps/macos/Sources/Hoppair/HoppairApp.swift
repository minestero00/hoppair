import SwiftUI
import AppKit
import Carbon

@main
struct HoppairApp: App {
    @StateObject private var model = AppModel()
    @StateObject private var updater = AppUpdater()
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    var body: some Scene {
        WindowGroup("Hoppair", id: "shelf") {
            ShelfWindow(model: model, delegate: delegate)
        }
        .defaultSize(width: 1100, height: 740)
        .windowToolbarStyle(.unifiedCompact(showsTitle: false))
        .commands {
            CommandGroup(after: .appInfo) { CheckForUpdatesButton(updater: updater) }
            CommandGroup(after: .newItem) {
                Button("Open Commands") { model.openCommands(); delegate.reveal() }.keyboardShortcut("k")
                Button("Add File…") { model.chooseFiles() }.keyboardShortcut("o")
                Button("Add from Clipboard") { model.paste() }.keyboardShortcut("v", modifiers: [.command, .shift])
            }
        }
        MenuBarExtra("Hoppair", systemImage: "rectangle.on.rectangle") {
            Button("Open Shared Shelf") { delegate.reveal() }
            Button("Open Commands  ⇧⌘Space") { model.openCommands(); delegate.reveal() }
            Divider()
            Button("Add Clipboard") { model.paste() }
            Button("Add File…") { model.chooseFiles() }
            Button("Take a Photo") { model.requestCamera(); delegate.reveal() }
            Divider()
            Button("Connect Phone") { model.showPairing = true; delegate.reveal() }
            CheckForUpdatesButton(updater: updater)
            Button("Quit") { NSApplication.shared.terminate(nil) }.keyboardShortcut("q")
        }
    }
}

private struct ShelfWindow: View {
    @Environment(\.openWindow) private var openWindow
    @ObservedObject var model: AppModel
    let delegate: AppDelegate
    private var sampleScene: String { ProcessInfo.processInfo.environment["HOPPAIR_SCREENSHOT_SCENE"] ?? "shelf" }
    private var sampleHeight: CGFloat? {
        ProcessInfo.processInfo.environment["HOPPAIR_SCREENSHOT_SIZE"]?.split(separator: "x").last.flatMap { Double($0) }.map { CGFloat($0) }
    }
    var body: some View {
        Group {
            if model.isScreenshotMode && sampleScene.hasPrefix("pairing") {
                PairingView(initiallyExpanded: sampleScene == "pairing-expanded", maximumHeight: sampleHeight)
            } else {
                ShelfView()
            }
        }.environmentObject(model)
            .environment(\.hoppairOpaquePreview, model.isScreenshotMode && ProcessInfo.processInfo.environment["HOPPAIR_SCREENSHOT_OPAQUE"] == "1")
            .background { if model.isScreenshotMode { HoppairCanvas().ignoresSafeArea() } }
            .toolbarBackground(.hidden, for: .windowToolbar)
            .environment(\.locale, model.isScreenshotMode ? Locale(identifier: "en_US") : Locale.autoupdatingCurrent)
            .onAppear {
            delegate.onOpenShelf = { openWindow(id: "shelf") }
            delegate.onHotKey = { model.openCommands(); delegate.reveal() }
            delegate.hasPendingImports = { model.pendingImports > 0 }
            delegate.finishPendingImports = { await model.finishPendingImports() }
            delegate.configureWindowChrome()
            if model.isScreenshotMode { delegate.captureSampleView() }
        }
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    var onHotKey: (() -> Void)?
    var onOpenShelf: (() -> Void)?
    var hasPendingImports: (() -> Bool)?
    var finishPendingImports: (() async -> Void)?
    private var hotKey: EventHotKeyRef?
    private var eventHandler: EventHandlerRef?
    private var sampleCaptured = false
    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApplication.shared.setActivationPolicy(.regular)
        if Bundle.main.bundleIdentifier == "dev.hoppair.screenshots" {
            let dark = ProcessInfo.processInfo.environment["HOPPAIR_SCREENSHOT_APPEARANCE"] == "dark"
            NSApplication.shared.appearance = NSAppearance(named: dark ? .darkAqua : .aqua)
            return
        }
        var event = EventTypeSpec(eventClass: OSType(kEventClassKeyboard), eventKind: UInt32(kEventHotKeyPressed))
        InstallEventHandler(GetApplicationEventTarget(), { _, _, userData in
            guard let userData else { return OSStatus(eventNotHandledErr) }
            let delegate = Unmanaged<AppDelegate>.fromOpaque(userData).takeUnretainedValue()
            DispatchQueue.main.async { delegate.onHotKey?() }
            return noErr
        }, 1, &event, Unmanaged.passUnretained(self).toOpaque(), &eventHandler)
        let identifier = EventHotKeyID(signature: 0x4D445244, id: 1)
        let status = RegisterEventHotKey(UInt32(kVK_Space), UInt32(cmdKey | shiftKey), identifier, GetApplicationEventTarget(), 0, &hotKey)
        if status != noErr { NSLog("Hoppair global shortcut unavailable (status %d).", status) }
    }
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }
    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        guard hasPendingImports?() == true, let finishPendingImports else { return .terminateNow }
        Task { @MainActor in
            await finishPendingImports()
            sender.reply(toApplicationShouldTerminate: true)
        }
        return .terminateLater
    }
    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool { reveal(); return true }
    func reveal() {
        NSApplication.shared.activate(ignoringOtherApps: true)
        if let window = NSApplication.shared.windows.first(where: { $0.identifier?.rawValue.contains("shelf") == true || $0.title == "Hoppair" }) {
            window.makeKeyAndOrderFront(nil)
        } else { onOpenShelf?() }
    }
    func configureWindowChrome() {
        if let window = NSApplication.shared.windows.first(where: { $0.title == "Hoppair" }) {
            window.titleVisibility = .hidden
            window.titlebarAppearsTransparent = true
            window.titlebarSeparatorStyle = .none
        }
    }
    func captureSampleView() {
        guard !sampleCaptured, Bundle.main.bundleIdentifier == "dev.hoppair.screenshots",
              let path = ProcessInfo.processInfo.environment["HOPPAIR_SCREENSHOT_OUTPUT"] else { return }
        sampleCaptured = true
        guard let window = NSApplication.shared.windows.first(where: { $0.title == "Hoppair" }) else { return }
        let size = ProcessInfo.processInfo.environment["HOPPAIR_SCREENSHOT_SIZE", default: "1100x740"].split(separator: "x").compactMap { Double($0) }
        let pairing = ProcessInfo.processInfo.environment["HOPPAIR_SCREENSHOT_SCENE"]?.hasPrefix("pairing") == true
        if size.count == 2, pairing ? (size[0] == 470 && (360...650).contains(size[1])) : ((760...1600).contains(size[0]) && (540...1100).contains(size[1])) {
            window.setContentSize(NSSize(width: size[0], height: size[1]))
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
            guard let view = window.contentView,
                  let bitmap = view.bitmapImageRepForCachingDisplay(in: view.bounds) else { return }
            NSApplication.shared.activate(ignoringOtherApps: true)
            window.makeKeyAndOrderFront(nil)
            view.layoutSubtreeIfNeeded()
            view.cacheDisplay(in: view.bounds, to: bitmap)
            do {
                let output = URL(fileURLWithPath: path)
                try FileManager.default.createDirectory(at: output.deletingLastPathComponent(), withIntermediateDirectories: true)
                // Native materials leave transparent pixels in an offscreen view capture.
                // Render the window's backing color as part of the exported sample.
                guard let image = bitmap.cgImage,
                    let context = CGContext(data: nil, width: bitmap.pixelsWide, height: bitmap.pixelsHigh,
                        bitsPerComponent: 8, bytesPerRow: 0, space: CGColorSpaceCreateDeviceRGB(),
                        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return }
                var backing = CGColor(gray: 0.95, alpha: 1)
                window.effectiveAppearance.performAsCurrentDrawingAppearance { backing = NSColor.windowBackgroundColor.cgColor }
                let bounds = CGRect(x: 0, y: 0, width: bitmap.pixelsWide, height: bitmap.pixelsHigh)
                context.setFillColor(backing)
                context.fill(bounds)
                context.setBlendMode(.normal)
                context.draw(image, in: bounds)
                guard let rendered = context.makeImage() else { return }
                try NSBitmapImageRep(cgImage: rendered).representation(using: .png, properties: [:])?.write(to: output)
                print("Saved sample app view.")
                if ProcessInfo.processInfo.environment["HOPPAIR_SCREENSHOT_EXIT"] == "1" { NSApplication.shared.terminate(nil) }
            } catch { print("Could not save sample app view: " + error.localizedDescription) }
        }
    }
    deinit {
        if let hotKey { UnregisterEventHotKey(hotKey) }
        if let eventHandler { RemoveEventHandler(eventHandler) }
    }
}
