import XCTest
import Network
@testable import HoppairCore

final class SecurityTests: XCTestCase {
    func testReplayGuardRejectsInvalidRequestsWithoutConsumingCapacity() throws {
        let guardState = RequestReplayGuard(capacity: 1)
        var request = BridgeRequest(action: "list")
        request.issuedAt = 1_000
        var invalid = request
        invalid.requestId = "not-a-uuid"
        XCTAssertThrowsError(try guardState.admit(invalid, now: 1_000, uptime: 0))
        invalid = request; invalid.version = 2
        XCTAssertThrowsError(try guardState.admit(invalid, now: 1_000, uptime: 0))
        invalid = request; invalid.issuedAt = .infinity
        XCTAssertThrowsError(try guardState.admit(invalid, now: 1_000, uptime: 0))
        invalid = request; invalid.issuedAt = 939
        XCTAssertThrowsError(try guardState.admit(invalid, now: 1_000, uptime: 0))
        invalid = request; invalid.issuedAt = 1_061
        XCTAssertThrowsError(try guardState.admit(invalid, now: 1_000, uptime: 0))
        try guardState.admit(request, now: 1_000, uptime: 0)
        var sameID = request; sameID.requestId = request.requestId.lowercased()
        XCTAssertThrowsError(try guardState.admit(sameID, now: 1_000, uptime: 1))
        var other = request; other.requestId = UUID().uuidString
        XCTAssertThrowsError(try guardState.admit(other, now: 1_000, uptime: 1))
        // Wall-clock jumps do not evict IDs. The complete 120-second validity
        // interval is protected; capacity becomes available after it expires.
        XCTAssertThrowsError(try guardState.admit(other, now: 1_060, uptime: 120))
        other.issuedAt = 1_121
        try guardState.admit(other, now: 1_121, uptime: 121)
    }

    func testWireRejectsOversizedAndUndersizedEncryptedPayloads() throws {
        let key = Data(repeating: 7, count: 32)
        XCTAssertThrowsError(try Wire.seal(Data(repeating: 0, count: Wire.maxFrameSize - 27), key: key))
        XCTAssertThrowsError(try Wire.open(Data(repeating: 0, count: Wire.maxFrameSize + 1), key: key))
        XCTAssertThrowsError(try Wire.open(Data(repeating: 0, count: 27), key: key))
        XCTAssertThrowsError(try Wire.seal(Data(), key: Data(repeating: 7, count: 31)))
    }

    func testMetadataBoundsAndChangedBackingFileCannotEscapeTransferLimit() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = try ShelfStore(root: root)
        // One extended grapheme can contain thousands of combining scalars.
        let hugeGrapheme = "a" + String(repeating: "\u{301}", count: 5_000)
        XCTAssertEqual(hugeGrapheme.count, 1)
        XCTAssertThrowsError(try store.put(ShelfItem(kind: "text", title: hugeGrapheme, text: "x")))
        let item = ShelfItem(kind: "file", title: "fixture", filename: "../" + hugeGrapheme + "\n", mime: "text/plain")
        try store.put(item, content: Data([1]))
        XCTAssertLessThanOrEqual(try XCTUnwrap(store.items.first?.filename).unicodeScalars.count, 160)
        XCTAssertFalse(try XCTUnwrap(store.items.first?.filename).contains("\n"))
        XCTAssertThrowsError(try store.put(ShelfItem(kind: "file", title: "bad mime", mime: "text/plain\nspoof"), content: Data()))
        let path = try store.fileURL(item.id)
        let file = try FileHandle(forWritingTo: path)
        try file.truncate(atOffset: UInt64(Wire.maxFileSize + 1)); try file.close()
        XCTAssertThrowsError(try store.content(item.id))
        var text = ShelfItem(kind: "text", title: "normal", text: "hello", filename: hugeGrapheme, mime: hugeGrapheme)
        try store.put(text)
        XCTAssertNil(store.items.first?.filename)
        XCTAssertNil(store.items.first?.mime)
        text.id = UUID().uuidString; text.source = hugeGrapheme
        XCTAssertThrowsError(try store.put(text))
    }

    func testRelayEndpointRejectsCredentialsFragmentsInvalidKeysAndRoles() throws {
        let key = Data(repeating: 7, count: 32)
        for address in ["wss://user:password@relay.example", "wss://relay.example/#fragment", "ws://relay.example",
                        "wss://relay.example/path", "wss://relay.example?query=1", "wss://relay.example?",
                        "wss://relay.example:0", "wss://relay.example:65536", "wss://relay.example:-1",
                        "wss://relay.example:", "wss://a_b.example", "wss://%65xample.com"] {
            XCTAssertThrowsError(try RelayIdentity.endpoint(base: address, key: key, role: "host"))
        }
        XCTAssertThrowsError(try RelayIdentity.endpoint(base: "wss://relay.example", key: Data(), role: "host"))
        XCTAssertThrowsError(try RelayIdentity.endpoint(base: "wss://relay.example", key: key, role: "invalid"))
    }

    func testLinksRequirePortableHTTPAuthorityWithoutSilentURLRepair() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = try ShelfStore(root: root)
        for text in ["https:foo", "https:/foo", "https://", "https://example.com/with space",
                     "https://example.com/\n", "https://user:pass@example.com", "https://a_b.example",
                     "https://example.com/%ZZ", "https://café.example", "https://example.com:0",
                     "https://999.999.999.999", "https://foo.123", "https://%65xample.com"] {
            XCTAssertFalse(WebLink.isValid(text), text)
            XCTAssertThrowsError(try store.put(ShelfItem(kind: "link", title: "fixture", text: text)), text)
        }
        for text in ["https://example.com/café?q=📎", "http://127.0.0.1:8080/path",
                     "https://[::1]/", "https://example.com/#fragment", "https://example.com/%20"] {
            XCTAssertTrue(WebLink.isValid(text), text)
            try store.put(ShelfItem(kind: "link", title: "fixture", text: text))
        }
    }

    func testOldMalformedLinkLoadsAsTextWithoutRewritingOrLosingContent() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let malformed = ShelfItem(kind: "link", title: "Preserved title", text: "https:foo", size: 9,
                                  createdAt: 1_000, source: "Android", pinned: true)
        let valid = ShelfItem(kind: "link", title: "Valid", text: "https://example.com/", size: 20)
        let invalidMissingText = ShelfItem(kind: "link", title: "Missing", text: nil)
        let invalidOversizedText = ShelfItem(kind: "link", title: "Too large", text: String(repeating: "x", count: 64 * 1024 + 1))
        let fixture = try JSONEncoder().encode([malformed, valid, invalidMissingText, invalidOversizedText])
        let index = root.appendingPathComponent("shelf.json")
        try fixture.write(to: index)
        let store = try ShelfStore(root: root)
        var repaired = malformed; repaired.kind = "text"
        XCTAssertEqual(store.items, [repaired, valid, invalidMissingText, invalidOversizedText])
        XCTAssertEqual(try Data(contentsOf: index), fixture) // read-only upgrade
        let page = try store.page()
        XCTAssertEqual(page.items.first, repaired)
        XCTAssertEqual(page.items.first?.text, "https:foo")
        try store.togglePin(valid.id)
        let persisted = try JSONDecoder().decode([ShelfItem].self, from: Data(contentsOf: index))
        XCTAssertEqual(persisted.first, repaired)
        XCTAssertEqual(try ShelfStore(root: root).items.first, repaired)
    }

    func testEscapingHeavyShelfPaginatesWithinWireBudgetAndLegacyFailsExplicitly() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = try ShelfStore(root: root)
        let text = String(repeating: "\u{0}", count: 64 * 1024)
        for number in 0..<33 { try store.put(ShelfItem(kind: "text", title: "fixture \(number)", text: text)) }
        XCTAssertGreaterThan(try JSONEncoder().encode(BridgeResponse(items: store.items)).count, Wire.maxFrameSize)
        let legacy = try store.legacyList()
        XCTAssertFalse(legacy.ok)
        XCTAssertTrue(legacy.error?.contains("Update") == true)
        XCTAssertNoThrow(try Wire.seal(JSONEncoder().encode(legacy), key: Data(repeating: 7, count: 32)))
        var cursor: String?
        var received: [ShelfItem] = []
        var cursors = Set<String>()
        repeat {
            let page = try store.page(after: cursor)
            var response = BridgeResponse(items: page.items, cameraRequest: UUID().uuidString, nextCursor: page.nextCursor)
            response.requestId = UUID().uuidString
            let bytes = try JSONEncoder().encode(response)
            XCTAssertLessThanOrEqual(bytes.count, 2 * 1024 * 1024)
            XCTAssertLessThanOrEqual(page.items.count, 100)
            XCTAssertFalse(page.items.isEmpty)
            received += page.items
            cursor = page.nextCursor
            if let cursor { XCTAssertTrue(cursors.insert(cursor).inserted) }
            XCTAssertNoThrow(try Wire.seal(bytes, key: Data(repeating: 7, count: 32)))
        } while cursor != nil
        XCTAssertGreaterThan(cursors.count, 1)
        XCTAssertEqual(received, store.items)
        XCTAssertThrowsError(try store.page(after: UUID().uuidString))
        XCTAssertThrowsError(try store.page(after: "not-a-uuid"))
    }

    func testPageItemCapCursorStabilityAndSmallLegacyShelf() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = try ShelfStore(root: root)
        for number in 0..<101 { try store.put(ShelfItem(kind: "text", title: "fixture \(number)", text: "small")) }
        let original = store.items
        let first = try store.page()
        XCTAssertEqual(first.items.count, 100)
        let cursor = try XCTUnwrap(first.nextCursor)
        try store.put(ShelfItem(kind: "text", title: "new between pages", text: "small"))
        let last = try store.page(after: cursor.uppercased())
        XCTAssertEqual(first.items + last.items, original)
        XCTAssertNil(last.nextCursor)
        let legacy = try store.legacyList()
        XCTAssertTrue(legacy.ok)
        XCTAssertEqual(legacy.items, store.items)
        XCTAssertTrue(try store.page(after: store.items.last!.id).items.isEmpty)
    }

    @MainActor
    func testTCPReplayIsRejectedAndEveryResponseIsCorrelated() async throws {
        let key = Data(repeating: 7, count: 32)
        let replayGuard = RequestReplayGuard()
        let server = BridgeServer(key: key, port: 0, loopbackOnly: true, requestGuard: replayGuard)
        var calls = 0
        server.handler = { _ in calls += 1; return BridgeResponse(ok: false, error: "fixture handler error") }
        let ready = expectation(description: "listener")
        var port: UInt16 = 0
        server.onPort = { port = $0; ready.fulfill() }
        defer { server.stop() }
        try server.start()
        await fulfillment(of: [ready], timeout: 5)
        let request = BridgeRequest(action: "cameraAck", id: UUID().uuidString)
        let encrypted = try Wire.seal(JSONEncoder().encode(request), key: key)
        let first = try await exchange(encrypted, port: port, key: key)
        XCTAssertEqual(first.requestId, request.requestId)
        XCTAssertFalse(first.ok)
        XCTAssertEqual(first.error, "fixture handler error")
        let replay = try await exchange(encrypted, port: port, key: key)
        XCTAssertEqual(replay.requestId, request.requestId)
        XCTAssertFalse(replay.ok)
        XCTAssertTrue(replay.error?.contains("already received") == true)
        XCTAssertEqual(calls, 1)
        // The same object can also be supplied to RelayHost; cross-transport
        // replay admission sees the request already accepted by the TCP server.
        XCTAssertThrowsError(try replayGuard.admit(request))
        var expired = BridgeRequest(action: "list"); expired.issuedAt -= 61
        let rejected = try await exchange(Wire.seal(JSONEncoder().encode(expired), key: key), port: port, key: key)
        XCTAssertEqual(rejected.requestId, expired.requestId)
        XCTAssertFalse(rejected.ok)
        XCTAssertEqual(calls, 1)
    }

    @MainActor
    private func exchange(_ encrypted: Data, port: UInt16, key: Data) async throws -> BridgeResponse {
        let done = expectation(description: "encrypted TCP response")
        let connection = NWConnection(host: "127.0.0.1", port: NWEndpoint.Port(rawValue: port)!, using: .tcp)
        defer { connection.cancel() }
        var result: Result<BridgeResponse, Error>?
        let frame = try Wire.frame(encrypted)
        connection.stateUpdateHandler = { state in
            guard case .ready = state else { return }
            connection.send(content: frame, completion: .contentProcessed { error in
                if let error { result = .failure(error); done.fulfill(); return }
                connection.receive(minimumIncompleteLength: 4, maximumLength: 4) { data, _, _, _ in
                    do {
                        let count = try Wire.length(XCTUnwrap(data))
                        connection.receive(minimumIncompleteLength: count, maximumLength: count) { body, _, _, _ in
                            result = Result { try JSONDecoder().decode(BridgeResponse.self, from: Wire.open(XCTUnwrap(body), key: key)) }
                            done.fulfill()
                        }
                    } catch { result = .failure(error); done.fulfill() }
                }
            })
        }
        connection.start(queue: .main)
        await fulfillment(of: [done], timeout: 5)
        return try XCTUnwrap(result).get()
    }
}
