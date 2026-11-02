import Foundation
import CryptoKit

public enum RelayIdentity {
    private static func derive(_ label: String, key: Data) -> Data {
        Data(HMAC<SHA256>.authenticationCode(for: Data(label.utf8), using: SymmetricKey(data: key)))
    }
    public static func room(key: Data) -> String {
        let hex = derive("macdroid-room-v1", key: key).prefix(16).map { String(format: "%02x", $0) }.joined()
        let parts = [8, 4, 4, 4, 12]
        var cursor = hex.startIndex
        return parts.map { length in let end = hex.index(cursor, offsetBy: length); defer { cursor = end }; return String(hex[cursor..<end]) }.joined(separator: "-")
    }
    public static func auth(key: Data) -> String {
        derive("macdroid-relay-auth-v1", key: key).base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }
    public static func endpoint(base: String, key: Data, role: String) throws -> URL {
        guard var components = URLComponents(string: base), let host = components.host,
              components.user == nil, components.password == nil,
              components.scheme == "wss" || (components.scheme == "ws" && ["127.0.0.1", "localhost"].contains(host)) else {
            throw BridgeError.invalid("Relay addresses must start with wss://.")
        }
        components.path = "/room/" + room(key: key)
        components.queryItems = [URLQueryItem(name: "role", value: role)]
        guard let url = components.url else { throw BridgeError.invalid("Check the relay address.") }
        return url
    }
}

@MainActor
public final class RelayHost {
    public var onState: ((Bool) -> Void)?
    private let base: String
    private let key: Data
    private let handler: (BridgeRequest) -> BridgeResponse
    private var task: Task<Void, Never>?
    private var socket: URLSessionWebSocketTask?
    public init(base: String, key: Data, handler: @escaping (BridgeRequest) -> BridgeResponse) {
        self.base = base; self.key = key; self.handler = handler
    }
    public func start() throws {
        let url = try RelayIdentity.endpoint(base: base, key: key, role: "host")
        var request = URLRequest(url: url, timeoutInterval: 15)
        request.setValue("Bearer " + RelayIdentity.auth(key: key), forHTTPHeaderField: "Authorization")
        task?.cancel()
        task = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                let socket = URLSession.shared.webSocketTask(with: request)
                socket.maximumMessageSize = Wire.maxFrameSize
                self.socket = socket
                socket.resume()
                var heartbeat: Task<Void, Never>?
                do {
                    try await Self.ping(socket)
                    self.onState?(true)
                    heartbeat = Task {
                        do {
                            while !Task.isCancelled {
                                try await Task.sleep(nanoseconds: 20_000_000_000)
                                try await Self.ping(socket)
                            }
                        } catch { socket.cancel(with: .goingAway, reason: nil) }
                    }
                    while !Task.isCancelled {
                        let message = try await socket.receive()
                        guard case .data(let encrypted) = message, encrypted.count <= Wire.maxFrameSize else { continue }
                        let request = try JSONDecoder().decode(BridgeRequest.self, from: Wire.open(encrypted, key: self.key))
                        guard request.version == 1, abs(Date().timeIntervalSince1970 - request.issuedAt) <= 60 else { continue }
                        var response = self.handler(request)
                        response.requestId = request.requestId
                        let payload = try Wire.seal(JSONEncoder().encode(response), key: self.key)
                        guard payload.count <= Wire.maxFrameSize else { continue }
                        try await socket.send(.data(payload))
                    }
                } catch { /* Retain queued items on the devices; reconnect without logging keys or content. */ }
                heartbeat?.cancel()
                socket.cancel(with: .goingAway, reason: nil)
                self.onState?(false)
                if Task.isCancelled { return }
                do { try await Task.sleep(nanoseconds: 5_000_000_000) } catch { return }
            }
        }
    }
    public func stop() { task?.cancel(); task = nil; socket?.cancel(with: .goingAway, reason: nil); socket = nil; onState?(false) }
    private static func ping(_ socket: URLSessionWebSocketTask) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            socket.sendPing { error in if let error { continuation.resume(throwing: error) } else { continuation.resume() } }
        }
    }
}
