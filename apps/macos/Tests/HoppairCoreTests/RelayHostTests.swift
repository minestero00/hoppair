import XCTest
import Network
@testable import HoppairCore

// Real loopback sockets exercise URLSession's delegate callbacks without a
// personal relay, credentials, network changes, or an external test server.
@MainActor
private final class LoopbackRelay {
    let listener: NWListener
    var connections: [NWConnection] = []
    var pings = 0
    var onAccept: (() -> Void)?
    var onData: ((Data, NWConnection) -> Void)?

    init(webSocket: Bool = true, stallFirstHandshake: Bool = false) throws {
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
        if webSocket {
            let options = NWProtocolWebSocket.Options()
            options.autoReplyPing = false
            parameters.defaultProtocolStack.applicationProtocols.insert(options, at: 0)
        }
        listener = try NWListener(using: parameters)
        listener.newConnectionHandler = { [weak self] connection in
            Task { @MainActor in
                guard let self else { connection.cancel(); return }
                self.connections.append(connection)
                if !stallFirstHandshake || self.connections.count > 1 {
                    connection.start(queue: .main)
                    if webSocket { self.receive(connection) }
                }
                self.onAccept?()
            }
        }
    }

    private func receive(_ connection: NWConnection) {
        connection.receiveMessage { [weak self] data, context, _, error in
            Task { @MainActor in
                guard let self, error == nil,
                      let metadata = context?.protocolMetadata(definition: NWProtocolWebSocket.definition) as? NWProtocolWebSocket.Metadata,
                      metadata.opcode != .close else { return }
                if metadata.opcode == .ping {
                    self.pings += 1
                    let pong = NWConnection.ContentContext(identifier: "pong", metadata: [NWProtocolWebSocket.Metadata(opcode: .pong)])
                    connection.send(content: data ?? Data(), contentContext: pong, completion: .contentProcessed { _ in })
                }
                if metadata.opcode == .binary, let data { self.onData?(data, connection) }
                self.receive(connection)
            }
        }
    }

    func send(_ data: Data, on connection: NWConnection) {
        let context = NWConnection.ContentContext(identifier: UUID().uuidString, metadata: [NWProtocolWebSocket.Metadata(opcode: .binary)])
        connection.send(content: data, contentContext: context, isComplete: true, completion: .contentProcessed { _ in })
    }

    func start() async throws -> String {
        let ready = XCTestExpectation(description: "loopback relay listening")
        listener.stateUpdateHandler = { state in
            if case .ready = state { ready.fulfill() }
        }
        listener.start(queue: .main)
        let result = await XCTWaiter.fulfillment(of: [ready], timeout: 5)
        XCTAssertEqual(result, .completed)
        return "ws://127.0.0.1:\(try XCTUnwrap(listener.port).rawValue)"
    }

    func stop() {
        listener.cancel()
        connections.forEach { $0.cancel() }
    }
}

final class RelayHostTests: XCTestCase {
    @MainActor
    func testDiagnosticsClassifyHandshakeWithoutLeakingErrorText() {
        let error = NSError(domain: NSURLErrorDomain, code: NSURLErrorTimedOut,
                            userInfo: [NSLocalizedDescriptionKey: "SECRET URL"])
        XCTAssertTrue(RelayHost.issue(status: 401, error: error).contains("registration"))
        XCTAssertTrue(RelayHost.issue(status: 429, error: error).contains("reconnect limit"))
        XCTAssertTrue(RelayHost.issue(status: 503, error: error).contains("unavailable"))
        XCTAssertTrue(RelayHost.issue(status: nil, error: error).contains("respond in time"))
        XCTAssertFalse(RelayHost.issue(status: nil, error: error).contains("SECRET"))
    }

    @MainActor
    func testRejectedHTTPHandshakeNeverReportsReadyAndExplainsRegistration() async throws {
        let server = try LoopbackRelay(webSocket: false)
        defer { server.stop() }
        let host = RelayHost(base: try await server.start(), key: Data(repeating: 7, count: 32)) { _ in BridgeResponse() }
        defer { host.stop() }
        let rejected = expectation(description: "registration rejection reported")
        var ready = false
        server.onAccept = {
            server.connections.last?.send(content: Data("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".utf8),
                                          completion: .contentProcessed { _ in })
        }
        host.onState = { ready = ready || $0 }
        host.onIssue = { issue in
            XCTAssertTrue(issue?.contains("registration was rejected") == true)
            rejected.fulfill()
        }
        try host.start()
        await fulfillment(of: [rejected], timeout: 5)
        XCTAssertFalse(ready)
    }

    @MainActor
    func testRelayRejectsReplayWithCorrelatedResponseWithoutRepeatingHandler() async throws {
        let server = try LoopbackRelay()
        defer { server.stop() }
        let key = Data(repeating: 7, count: 32)
        let replayGuard = RequestReplayGuard()
        var calls = 0
        let host = RelayHost(base: try await server.start(), key: key, requestGuard: replayGuard) { _ in
            calls += 1
            return BridgeResponse()
        }
        defer { host.stop() }
        let request = BridgeRequest(action: "cameraAck", id: UUID().uuidString)
        let encrypted = try Wire.seal(JSONEncoder().encode(request), key: key)
        let done = expectation(description: "replayed request rejected")
        var responses = 0
        server.onData = { data, connection in
            do {
                let response = try JSONDecoder().decode(BridgeResponse.self, from: Wire.open(data, key: key))
                XCTAssertEqual(response.requestId, request.requestId)
                responses += 1
                if responses == 1 {
                    XCTAssertTrue(response.ok)
                    server.send(encrypted, on: connection)
                } else {
                    XCTAssertFalse(response.ok)
                    XCTAssertTrue(response.error?.contains("already received") == true)
                    done.fulfill()
                }
            } catch { XCTFail("\(error)"); done.fulfill() }
        }
        host.onState = { connected in
            if connected, let connection = server.connections.last { server.send(encrypted, on: connection) }
        }
        try host.start()
        await fulfillment(of: [done], timeout: 5)
        XCTAssertEqual(responses, 2)
        XCTAssertEqual(calls, 1)
    }

    @MainActor
    func testReconnectsAfterRepeatedDisconnectsAndKeepsHeartbeatAlive() async throws {
        let server = try LoopbackRelay()
        defer { server.stop() }
        let host = RelayHost(base: try await server.start(), key: Data(repeating: 7, count: 32)) { _ in BridgeResponse() }
        defer { host.stop() }
        let restored = expectation(description: "fourth connection restored")
        var connected = 0
        var states: [Bool] = []
        host.onState = { state in
            states.append(state)
            guard state else { return }
            connected += 1
            if connected <= 3 { server.connections.last?.cancel() }
            else if connected == 4 { restored.fulfill() }
        }
        try host.start()
        await fulfillment(of: [restored], timeout: 25)
        XCTAssertEqual(connected, 4)
        XCTAssertEqual(states.filter { !$0 }.count, 4) // start + three drops
        XCTAssertEqual(server.pings, 4)
        // Cross the production 20-second heartbeat interval on the real socket.
        try await Task.sleep(for: .seconds(22))
        XCTAssertEqual(connected, 4)
        XCTAssertEqual(states.last, true)
        XCTAssertGreaterThanOrEqual(server.pings, 5)
        host.stop()
        let stoppedCount = states.count
        try await Task.sleep(for: .milliseconds(200))
        XCTAssertEqual(states.count, stoppedCount)
        XCTAssertEqual(states.last, false)
    }

    @MainActor
    func testRestartDuringHandshakeIgnoresOldConnectionCleanup() async throws {
        let server = try LoopbackRelay(stallFirstHandshake: true)
        defer { server.stop() }
        let host = RelayHost(base: try await server.start(), key: Data(repeating: 7, count: 32)) { _ in BridgeResponse() }
        defer { host.stop() }
        let restored = expectation(description: "replacement connection ready")
        var states: [Bool] = []
        var replaced = false
        server.onAccept = {
            if !replaced {
                replaced = true
                do { try host.start() } catch { XCTFail("\(error)") }
            }
        }
        host.onState = {
            states.append($0)
            if $0 { restored.fulfill() }
        }
        try host.start()
        await fulfillment(of: [restored], timeout: 5)
        try await Task.sleep(for: .milliseconds(300))
        XCTAssertEqual(states, [false, false, true])
        XCTAssertEqual(server.connections.count, 2)
        XCTAssertEqual(server.pings, 1)
    }

    @MainActor
    func testStopDuringStalledHandshakeStaysStopped() async throws {
        let server = try LoopbackRelay(webSocket: false)
        defer { server.stop() }
        let host = RelayHost(base: try await server.start(), key: Data(repeating: 7, count: 32)) { _ in BridgeResponse() }
        defer { host.stop() }
        let accepted = expectation(description: "TCP accepted without WebSocket upgrade")
        server.onAccept = { accepted.fulfill() }
        var states: [Bool] = []
        host.onState = { states.append($0) }
        try host.start()
        await fulfillment(of: [accepted], timeout: 5)
        host.stop()
        try await Task.sleep(for: .milliseconds(300))
        XCTAssertEqual(states, [false, false])
    }
}
