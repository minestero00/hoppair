import XCTest
@testable import HoppairCore

final class AndroidPeerTests: XCTestCase {
    @MainActor
    func testAndroidPeer() async throws {
        guard ProcessInfo.processInfo.environment["HOPPAIR_ANDROID_PEER"] == "1" else { throw XCTSkip("Run scripts/test-peer.sh with an Android emulator.") }
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = try ShelfStore(root: root)
        try store.put(ShelfItem(kind: "file", title: "fixture", filename: "Shared from Mac – café.txt", mime: "text/plain"), content: Data("Swift → Android · café 📎".utf8))
        let server = BridgeServer(key: Data(repeating: 7, count: 32), port: 49272, loopbackOnly: true)
        var relay: RelayHost?
        defer { server.stop(); relay?.stop() }
        let ready = expectation(description: "peer ready")
        let complete = expectation(description: "Android finished")
        server.onPort = { _ in ready.fulfill() }
        let handler: (BridgeRequest) -> BridgeResponse = { request in
            do {
                switch request.action {
                case "list": return try store.legacyList()
                case "listPage":
                    let page = try store.page(after: request.id)
                    return BridgeResponse(items: page.items, nextCursor: page.nextCursor)
                case "get": return BridgeResponse(data: try store.content(request.id!).base64EncodedString())
                case "put":
                    let item = request.item!
                    try store.put(item, content: request.data.flatMap { Data(base64Encoded: $0) })
                    if item.title == "peer-complete" { complete.fulfill() }
                    return BridgeResponse()
                default: return BridgeResponse(ok: false)
                }
            } catch { return BridgeResponse(ok: false, error: error.localizedDescription) }
        }
        server.handler = handler
        if ProcessInfo.processInfo.environment["HOPPAIR_RELAY_PEER"] == "1" {
            let host = RelayHost(base: "ws://127.0.0.1:8787", key: Data(repeating: 7, count: 32), handler: handler)
            var announced = false
            host.onState = { connected in if connected && !announced { announced = true; ready.fulfill() } }
            relay = host; try host.start()
        } else { try server.start() }
        await fulfillment(of: [ready], timeout: 5)
        let marker = ProcessInfo.processInfo.environment["HOPPAIR_PEER_READY"]!
        try Data("ready".utf8).write(to: URL(fileURLWithPath: marker))
        await fulfillment(of: [complete], timeout: 90)
        XCTAssertEqual(store.items.count, 3)
        XCTAssertEqual(store.items.first?.text, "Android → Swift · success")
        XCTAssertEqual(store.items[1].filename, "android.bin")
        XCTAssertEqual(try store.content(store.items[1].id).count, 65537)
        // Allow the final encrypted acknowledgement to reach Android before stopping the listener.
        try await Task.sleep(nanoseconds: 300_000_000)
    }
}
