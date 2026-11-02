import SwiftUI
import AppKit
import Carbon

@main
struct MacDroidApp: App {
    @StateObject private var model = AppModel()
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    var body: some Scene {
        WindowGroup("MacDroid", id: "shelf") {
            ShelfView().environmentObject(model)
                .onAppear { delegate.onHotKey = { model.showCommands = true; delegate.reveal() } }
        }
        .defaultSize(width: 1000, height: 720)
        .commands {
            CommandGroup(after: .newItem) {
                Button("Open Commands") { model.showCommands = true; delegate.reveal() }.keyboardShortcut("k")
                Button("Add File…") { model.chooseFiles() }.keyboardShortcut("o")
                Button("Add from Clipboard") { model.paste() }.keyboardShortcut("v", modifiers: [.command, .shift])
            }
        }
        MenuBarExtra("MacDroid", systemImage: "rectangle.on.rectangle") {
            Button("Open Shared Shelf") { delegate.reveal() }
            Button("Open Commands  ⇧⌘Space") { model.showCommands = true; delegate.reveal() }
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

final class AppDelegate: NSObject, NSApplicationDelegate {
    var onHotKey: (() -> Void)?
    private var hotKey: EventHotKeyRef?
    private var eventHandler: EventHandlerRef?
    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApplication.shared.setActivationPolicy(.regular)
        var event = EventTypeSpec(eventClass: OSType(kEventClassKeyboard), eventKind: UInt32(kEventHotKeyPressed))
        InstallEventHandler(GetApplicationEventTarget(), { _, _, userData in
            guard let userData else { return OSStatus(eventNotHandledErr) }
            let delegate = Unmanaged<AppDelegate>.fromOpaque(userData).takeUnretainedValue()
            DispatchQueue.main.async { delegate.onHotKey?() }
            return noErr
        }, 1, &event, Unmanaged.passUnretained(self).toOpaque(), &eventHandler)
        let identifier = EventHotKeyID(signature: 0x4D445244, id: 1)
        RegisterEventHotKey(UInt32(kVK_Space), UInt32(cmdKey | shiftKey), identifier, GetApplicationEventTarget(), 0, &hotKey)
    }
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }
    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool { reveal(); return true }
    func reveal() {
        NSApplication.shared.activate(ignoringOtherApps: true)
        if let window = NSApplication.shared.windows.first(where: { $0.identifier?.rawValue.contains("shelf") == true || $0.title == "MacDroid" }) {
            window.makeKeyAndOrderFront(nil)
        }
    }
    deinit {
        if let hotKey { UnregisterEventHotKey(hotKey) }
        if let eventHandler { RemoveEventHandler(eventHandler) }
    }
}
