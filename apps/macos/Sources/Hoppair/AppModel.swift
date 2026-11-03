import SwiftUI
import AppKit
import Security
import CryptoKit
import UniformTypeIdentifiers
import HoppairCore
import Darwin

@MainActor
final class AppModel: ObservableObject {
    let isScreenshotMode = Bundle.main.bundleIdentifier == "dev.hoppair.screenshots"
    @Published var preparing = true
    @Published var items: [ShelfItem] = []
    @Published var message = "Preparing your connection"
    @Published var error: String?
    @Published var port: UInt16 = 0
    @Published var lastSeen: Date?
    @Published var cameraRequest: String?
    @Published var showPairing = false
    @Published var showCommands = false
    private var store: ShelfStore?
    private var server: BridgeServer?
    private var relayHost: RelayHost?
    @Published var relayAddress = UserDefaults.standard.string(forKey: "relayAddress") ?? (Bundle.main.object(forInfoDictionaryKey: "HoppairRelayURL") as? String ?? Bundle.main.object(forInfoDictionaryKey: "MacDroidRelayURL") as? String ?? "")
    @Published var relayConnected = false
    private var key = Data()
    @Published var addresses = AppModel.localAddresses()
    @Published var selectedAddress = ""
    init() {
        if isScreenshotMode {
            preparing = false
            relayAddress = ""
            message = "Sample content · no devices connected"
            items = [
                ShelfItem(kind: "text", title: "A few things for tomorrow",
                          text: "Pick up the design where you left off. Keep it simple, keep it useful.", source: "Android", pinned: true),
                ShelfItem(kind: "file", title: "Hoppair icon.png", filename: "Hoppair icon.png", mime: "image/png", size: 74599, source: "Android"),
                ShelfItem(kind: "link", title: "A little inspiration", text: "https://developer.android.com/design", source: "Mac"),
                ShelfItem(kind: "text", title: "Coffee, then a little planning.", text: "Coffee, then a little planning.", source: "Android")
            ]
            return
        }
        selectedAddress = addresses.first ?? ""
        do {
            // Keep the original storage and Keychain identities for in-place upgrades.
            let root = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                                  appropriateFor: nil, create: true).appendingPathComponent("MacDroid")
            store = try ShelfStore(root: root)
            refresh()
            Task { await startConnections() }
        } catch { preparing = false; self.error = "Could not start: \(error.localizedDescription)" }
    }
    private func startConnections() async {
        do {
            key = try await Task.detached(priority: .userInitiated) { try AppModel.loadKey() }.value
            let server = BridgeServer(key: key)
            self.server = server
            server.handler = { [weak self] request in self?.handle(request) ?? BridgeResponse(ok: false) }
            server.onState = { [weak self] state in self?.message = state }
            server.onPort = { [weak self] port in self?.port = port }
            try server.start()
            if !relayAddress.isEmpty { configureRelay() }
            preparing = false
        } catch { preparing = false; self.error = "Could not prepare the connection: \(error.localizedDescription)" }
    }
    var pairingLink: String { PairingLink.webURL(for: directPairingLink) }
    var directPairingLink: String {
        guard key.count == 32, port > 0 || !relayAddress.isEmpty, !selectedAddress.isEmpty || !relayAddress.isEmpty else { return "" }
        let secret = key.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
        var components = URLComponents()
        components.scheme = "hoppair"; components.host = selectedAddress.isEmpty ? "127.0.0.1" : selectedAddress; components.port = port > 0 ? Int(port) : 49271
        components.queryItems = [URLQueryItem(name: "key", value: secret)]
        if !relayAddress.isEmpty { components.queryItems?.append(URLQueryItem(name: "relay", value: relayAddress)) }
        return components.string ?? ""
    }
    var relayRoom: String { key.count == 32 ? RelayIdentity.room(key: key) : "" }
    var relayRegistration: String {
        guard key.count == 32 else { return "" }
        let hash = SHA256.hash(data: Data(RelayIdentity.auth(key: key).utf8)).map { String(format: "%02x", $0) }.joined()
        return "{\"\(relayRoom)\":\"\(hash)\"}"
    }
    func openCommands() {
        if showPairing {
            showPairing = false
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) { self.showCommands = true }
        } else { showCommands = true }
    }
    func configureRelay(_ input: String? = nil) {
        let address = (input ?? relayAddress).trimmingCharacters(in: .whitespacesAndNewlines)
        if address.isEmpty { relayHost?.stop(); relayHost = nil; relayAddress = ""; UserDefaults.standard.removeObject(forKey: "relayAddress"); return }
        do {
            _ = try RelayIdentity.endpoint(base: address, key: key, role: "host")
            relayHost?.stop(); relayHost = nil
            let host = RelayHost(base: address, key: key) { [weak self] request in self?.handle(request) ?? BridgeResponse(ok: false) }
            host.onState = { [weak self] connected in self?.relayConnected = connected }
            relayHost = host; try host.start()
            relayAddress = address; UserDefaults.standard.set(address, forKey: "relayAddress")
        } catch { self.error = error.localizedDescription }
    }
    func refreshNetwork() {
        addresses = Self.localAddresses()
        if !addresses.contains(selectedAddress) { selectedAddress = addresses.first ?? "" }
    }
    func refresh() { items = store?.items ?? [] }
    func handle(_ request: BridgeRequest) -> BridgeResponse {
        lastSeen = Date()
        do {
            guard let store else { throw BridgeError.invalid("Could not open local storage.") }
            switch request.action {
            case "list": return BridgeResponse(items: store.items, cameraRequest: cameraRequest)
            case "put":
                guard var item = request.item else { throw BridgeError.invalid("Item is missing.") }
                item.source = "Android"; item.pinned = false
                let data: Data?
                if let encoded = request.data {
                    guard let decoded = Data(base64Encoded: encoded) else { throw BridgeError.invalid("Invalid file data.") }
                    data = decoded
                } else { data = nil }
                try store.put(item, content: data); refresh()
                message = "Received an item from your phone"
                return BridgeResponse()
            case "get":
                guard let id = request.id else { throw BridgeError.invalid("Item ID is missing.") }
                return BridgeResponse(data: try store.content(id).base64EncodedString())
            case "cameraAck":
                if request.id == cameraRequest { cameraRequest = nil }
                return BridgeResponse()
            default: return BridgeResponse(ok: false, error: "Unsupported action.")
            }
        } catch { return BridgeResponse(ok: false, error: error.localizedDescription) }
    }
    func addText(_ text: String) {
        let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty else { return }
        let scheme = URL(string: value)?.scheme?.lowercased() ?? ""
        let link = ["http", "https"].contains(scheme) && !value.contains("\n")
        perform {
            try store?.put(ShelfItem(kind: link ? "link" : "text", title: String(value.prefix(90)), text: value))
            message = "Added to the shelf · your phone will receive it when connected"
        }
    }
    func paste() { if let text = NSPasteboard.general.string(forType: .string) { addText(text) } }
    func chooseFiles() {
        let panel = NSOpenPanel(); panel.allowsMultipleSelection = true; panel.canChooseDirectories = false
        if panel.runModal() == .OK { panel.urls.forEach(importFile) }
    }
    func importFile(_ url: URL) {
        perform {
            let granted = url.startAccessingSecurityScopedResource()
            defer { if granted { url.stopAccessingSecurityScopedResource() } }
            let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
            guard size <= Wire.maxFileSize else { throw BridgeError.invalid("\(url.lastPathComponent): Files must be 8 MiB or smaller.") }
            let data = try Data(contentsOf: url)
            try store?.put(ShelfItem(kind: "file", title: url.lastPathComponent, filename: url.lastPathComponent,
                                    mime: UTType(filenameExtension: url.pathExtension)?.preferredMIMEType, size: data.count), content: data)
            message = "File added to the shelf"
        }
    }
    func previewURL(for item: ShelfItem) -> URL? {
        guard item.kind == "file" else { return nil }
        if isScreenshotMode { return item.mime == "image/png" ? Bundle.main.url(forResource: "BrandIcon", withExtension: "png") : nil }
        guard let store, store.items.contains(where: { $0.id == item.id && $0.kind == "file" }) else { return nil }
        return try? store.fileURL(item.id)
    }
    func pin(_ item: ShelfItem) { perform { try store?.togglePin(item.id) } }
    @discardableResult func copy(_ text: String) -> Bool {
        NSPasteboard.general.clearContents()
        let copied = NSPasteboard.general.setString(text, forType: .string)
        if copied { message = "Copied" } else { error = "Could not copy to the clipboard. Try again." }
        return copied
    }
    func open(_ item: ShelfItem) {
        if item.kind == "link", let text = item.text, let url = URL(string: text), ["http", "https"].contains(url.scheme?.lowercased() ?? "") {
            NSWorkspace.shared.open(url)
        } else if let text = item.text { copy(text) }
        else { save(item) }
    }
    func save(_ item: ShelfItem) {
        perform {
            guard let data = try store?.content(item.id) else { return }
            let panel = NSSavePanel(); panel.nameFieldStringValue = item.filename ?? "Files"
            if panel.runModal() == .OK, let url = panel.url { try data.write(to: url, options: .atomic); message = "File saved" }
        }
    }
    func requestCamera() {
        cameraRequest = UUID().uuidString.lowercased()
        message = "Open Hoppair on your phone to confirm the photo request"
    }
    private func perform(_ work: () throws -> Void) {
        do { guard store != nil else { throw BridgeError.invalid("Could not open local storage. Please restart the app.") }; try work(); refresh() } catch { self.error = error.localizedDescription }
    }
    nonisolated private static func loadKey() throws -> Data {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "dev.macdroid.bridge", kSecAttrAccount as String: "pairing-v1"]
        var read = query; read[kSecReturnData as String] = true
        var result: CFTypeRef?
        let status = SecItemCopyMatching(read as CFDictionary, &result)
        if status == errSecSuccess, let data = result as? Data, data.count == 32 { return data }
        guard status == errSecItemNotFound else { throw BridgeError.invalid("Could not read the pairing key from Keychain (\(status)).") }
        let data = SymmetricKey(size: .bits256).withUnsafeBytes { Data($0) }
        var add = query; add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        guard SecItemAdd(add as CFDictionary, nil) == errSecSuccess else { throw BridgeError.invalid("Could not save the pairing key.") }
        return data
    }
    private static func localAddresses() -> [String] {
        var pointer: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&pointer) == 0 else { return [] }
        defer { freeifaddrs(pointer) }
        var result: [String] = []
        var current = pointer
        while let entry = current {
            defer { current = entry.pointee.ifa_next }
            guard let address = entry.pointee.ifa_addr, address.pointee.sa_family == UInt8(AF_INET),
                  entry.pointee.ifa_flags & UInt32(IFF_LOOPBACK) == 0 else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            if getnameinfo(address, socklen_t(address.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
                result.append(String(cString: host))
            }
        }
        return Array(Set(result)).sorted { a, b in
            func rank(_ value: String) -> Int { value.hasPrefix("192.168.") ? 0 : value.hasPrefix("10.") ? 1 : 2 }
            return rank(a) == rank(b) ? a < b : rank(a) < rank(b)
        }
    }
}
