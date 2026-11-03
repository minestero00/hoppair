import SwiftUI
import AppKit
import Carbon

@main
struct HoppairApp: App {
    @StateObject private var model = AppModel()
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    var body: some Scene {
        WindowGroup("Hoppair", id: "shelf") {
            ShelfWindow(model: model, delegate: delegate)
        }
        .defaultSize(width: 1100, height: 740)
        .windowToolbarStyle(.unifiedCompact(showsTitle: false))
        .commands {
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
            Button("Quit") { NSApplication.shared.terminate(nil) }.keyboardShortcut("q")
        }
    }
}

private struct ShelfWindow: View {
    @Environment(\.openWindow) private var openWindow
    @ObservedObject var model: AppModel
    let delegate: AppDelegate
    var body: some View {
        ShelfView().environmentObject(model)
            .toolbarBackground(.hidden, for: .windowToolbar)
            .environment(\.locale, model.isScreenshotMode ? Locale(identifier: "en_US") : Locale.autoupdatingCurrent)
            .onAppear {
            delegate.onOpenShelf = { openWindow(id: "shelf") }
            delegate.onHotKey = { model.openCommands(); delegate.reveal() }
            delegate.configureWindowChrome()
            if model.isScreenshotMode { delegate.captureSampleView() }
        }
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    var onHotKey: (() -> Void)?
    var onOpenShelf: (() -> Void)?
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
        if size.count == 2, (760...1600).contains(size[0]), (540...1100).contains(size[1]) {
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
                try bitmap.representation(using: .png, properties: [:])?.write(to: output)
                print("Saved sample app view.")
            } catch { print("Could not save sample app view: " + error.localizedDescription) }
        }
    }
    deinit {
        if let hotKey { UnregisterEventHotKey(hotKey) }
        if let eventHandler { RemoveEventHandler(eventHandler) }
    }
}
