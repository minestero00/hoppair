import XCTest
import CryptoKit
import Network
import Vision
@testable import HoppairCore

final class CoreTests: XCTestCase {
    private func temporaryStore() throws -> ShelfStore {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: root) }
        return try ShelfStore(root: root)
    }
    func testCipherInteroperabilityVectorAndTamperRejection() throws {
        let key = Data(repeating: 0, count: 32)
        let encrypted = Data(repeating: 0, count: 12) + hex("cea7403d4d606b6e074ec5d3baf39d18d0d1c8a799996bf0265b98b5d48ab919")
        XCTAssertEqual(try Wire.open(encrypted, key: key), Data(repeating: 0, count: 16))
        let message = Data("Unicode: café and emoji 📎".utf8)
        let sealed = try Wire.seal(message, key: key)
        XCTAssertEqual(try Wire.open(sealed, key: key), message)
        var modified = sealed; modified[13] ^= 1
        XCTAssertThrowsError(try Wire.open(modified, key: key))
        XCTAssertThrowsError(try Wire.open(sealed, key: Data(repeating: 1, count: 32)))
    }
    func testFrameBounds() throws {
        let data = Data(repeating: 0, count: 128)
        XCTAssertEqual(try Wire.length(Wire.frame(data).prefix(4)), 128)
        for header in [Data(), Data([0, 0, 0, 1]), Data([255, 255, 255, 255])] {
            XCTAssertThrowsError(try Wire.length(header))
        }
    }
    func testRetryDoesNotDuplicateOrOverwriteFileAndSurvivesRestart() throws {
        let store = try temporaryStore()
        let item = ShelfItem(kind: "file", title: "Photo", filename: "../../photo.jpg")
        try store.put(item, content: Data("original".utf8))
        var retry = item; retry.id = item.id.uppercased()
        try store.put(retry, content: Data("retry".utf8))
        XCTAssertEqual(store.items.count, 1)
        XCTAssertEqual(store.items[0].filename, "photo.jpg")
        let reloaded = try ShelfStore(root: store.root)
        XCTAssertEqual(try reloaded.content(item.id), Data("original".utf8))
        try reloaded.togglePin(item.id)
        XCTAssertTrue(try ShelfStore(root: store.root).items[0].pinned)
    }
    func testInvalidPathsAndOversizedPayloadsAreRejectedWithoutIndexMutation() throws {
        let store = try temporaryStore()
        XCTAssertThrowsError(try store.put(ShelfItem(id: "../escape", kind: "file", title: "bad"), content: Data()))
        XCTAssertThrowsError(try store.put(ShelfItem(kind: "file", title: "missing")))
        XCTAssertThrowsError(try store.put(ShelfItem(kind: "file", title: "large"), content: Data(repeating: 0, count: Wire.maxFileSize + 1)))
        XCTAssertThrowsError(try store.put(ShelfItem(kind: "text", title: "large", text: String(repeating: "€", count: 23000))))
        XCTAssertThrowsError(try store.put(ShelfItem(kind: "link", title: "script", text: "javascript:alert(1)")))
        XCTAssertTrue(store.items.isEmpty)
    }
    func testCorruptIndexDoesNotSilentlyResetShelf() throws {
        let store = try temporaryStore()
        try Data("corrupt".utf8).write(to: store.root.appendingPathComponent("shelf.json"))
        XCTAssertThrowsError(try ShelfStore(root: store.root))
    }
    @MainActor
    func testRealTCPRoundtripWithFragmentedRequest() async throws {
        let key = Data(repeating: 7, count: 32)
        let server = BridgeServer(key: key, port: 0)
        server.handler = { request in
            XCTAssertEqual(request.action, "list")
            return BridgeResponse(items: [ShelfItem(kind: "text", title: "Response", text: "Connection successful")])
        }
        defer { server.stop() }
        let ready = expectation(description: "listener")
        var port: UInt16 = 0
        server.onPort = { port = $0; ready.fulfill() }
        try server.start()
        await fulfillment(of: [ready], timeout: 5)
        let done = expectation(description: "encrypted response")
        let client = NWConnection(host: "127.0.0.1", port: NWEndpoint.Port(rawValue: port)!, using: .tcp)
        defer { client.cancel() }
        let frame = try Wire.frame(Wire.seal(JSONEncoder().encode(BridgeRequest(action: "list")), key: key))
        client.stateUpdateHandler = { state in
            if case .ready = state {
                client.send(content: frame.prefix(2), completion: .contentProcessed { _ in
                    client.send(content: frame.dropFirst(2), completion: .contentProcessed { _ in
                        client.receive(minimumIncompleteLength: 4, maximumLength: 4) { data, _, _, _ in
                            guard let data, let size = try? Wire.length(data) else { XCTFail("missing header"); done.fulfill(); return }
                            client.receive(minimumIncompleteLength: size, maximumLength: size) { data, _, _, _ in
                                do {
                                    let response = try JSONDecoder().decode(BridgeResponse.self, from: Wire.open(XCTUnwrap(data), key: key))
                                    XCTAssertEqual(response.items?.first?.text, "Connection successful")
                                } catch { XCTFail("\(error)") }
                                done.fulfill()
                            }
                        }
                    })
                })
            }
        }
        client.start(queue: .main)
        await fulfillment(of: [done], timeout: 10)
    }
    func testWebPairingLinkKeepsCredentialsInFragmentAndRoundTripsQR() throws {
        let direct = "hoppair://192.168.1.12:49271?key=" + String(repeating: "A", count: 43)
        let value = PairingLink.webURL(for: direct)
        let url = try XCTUnwrap(URLComponents(string: value))
        XCTAssertNil(url.query)
        XCTAssertEqual(url.host, "hoppair-connect.macdroid-relay.workers.dev")
        let payload = try XCTUnwrap(url.fragment?.dropFirst(5))
        var encoded = String(payload).replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        encoded += String(repeating: "=", count: (4 - encoded.count % 4) % 4)
        XCTAssertEqual(Data(base64Encoded: encoded), Data(direct.utf8))
        let image = try XCTUnwrap(PairingCode.image(for: value))
        let request = VNDetectBarcodesRequest(); request.symbologies = [.qr]
        try VNImageRequestHandler(cgImage: image).perform([request])
        XCTAssertEqual(request.results?.first?.payloadStringValue, value)
    }
    func testPairingQRCodeDecodesToOriginalLink() throws {
        let link = "hoppair://192.168.1.12:49271?key=fixture-key-with-url-safe-characters_1234567"
        let image = try XCTUnwrap(PairingCode.image(for: link))
        let request = VNDetectBarcodesRequest()
        request.symbologies = [.qr]
        try VNImageRequestHandler(cgImage: image).perform([request])
        XCTAssertEqual(request.results?.first?.payloadStringValue, link)
    }
    func testRelayIdentityIsStableAcrossNetworksAndDistinctFromContentKey() throws {
        let key = Data(repeating: 7, count: 32)
        XCTAssertEqual(RelayIdentity.room(key: key), "cd0ab698-24ee-465f-e4d6-ef293b3cd2cd")
        let first = try RelayIdentity.endpoint(base: "wss://relay.example", key: key, role: "host")
        let second = try RelayIdentity.endpoint(base: "wss://relay.example/unused", key: key, role: "phone")
        XCTAssertEqual(first.path, second.path)
        XCTAssertNotEqual(RelayIdentity.auth(key: key), key.base64EncodedString())
        XCTAssertThrowsError(try RelayIdentity.endpoint(base: "ws://relay.example", key: key, role: "host"))
    }
    private func hex(_ value: String) -> Data {
        var data = Data(); var index = value.startIndex
        while index < value.endIndex { let end = value.index(index, offsetBy: 2); data.append(UInt8(value[index..<end], radix: 16)!); index = end }
        return data
    }
}
