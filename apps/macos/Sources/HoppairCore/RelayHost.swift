import Foundation
import CryptoKit

// Wire-protocol identifiers remain stable for devices paired before the Hoppair rename.
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
        guard key.count == 32 else { throw BridgeError.invalid("Invalid pairing key.") }
        guard ["host", "phone"].contains(role) else { throw BridgeError.invalid("Invalid relay role.") }
        guard var components = URLValidation.components(base), let host = components.host, URLValidation.validHost(host),
              components.percentEncodedHost == host,
              ["", "/"].contains(components.path), components.query == nil, components.fragment == nil,
              components.port.map({ (1...65535).contains($0) }) ?? true,
              components.user == nil, components.password == nil,
              components.scheme == "wss" || (components.scheme == "ws" && ["127.0.0.1", "localhost"].contains(host)) else {
            throw BridgeError.invalid("Use wss://host with an optional port. Remove any path, query, fragment, or sign-in details.")
        }
        components.path = "/room/" + room(key: key)
        components.queryItems = [URLQueryItem(name: "role", value: role)]
        guard let url = components.url else { throw BridgeError.invalid("Check the relay address.") }
        return url
    }
}

@MainActor
// Owners must call stop() before replacing/releasing a running host.
public final class RelayHost {
    public var onState: ((Bool) -> Void)?
    /// Fixed, non-sensitive messages only; never exposes URLs, headers, or payloads.
    public var onIssue: ((String?) -> Void)?
    private let base: String
    private let key: Data
    private let requestGuard: RequestReplayGuard
    private let handler: (BridgeRequest) -> BridgeResponse
    private var task: Task<Void, Never>?
    private var socket: URLSessionWebSocketTask?
    private var generation: UUID?
    public init(base: String, key: Data, requestGuard: RequestReplayGuard = RequestReplayGuard(), handler: @escaping (BridgeRequest) -> BridgeResponse) {
        self.base = base; self.key = key; self.requestGuard = requestGuard; self.handler = handler
    }
    public func start() throws {
        let url = try RelayIdentity.endpoint(base: base, key: key, role: "host")
        var request = URLRequest(url: url, timeoutInterval: 15)
        request.setValue("Bearer " + RelayIdentity.auth(key: key), forHTTPHeaderField: "Authorization")
        stop()
        let generation = UUID()
        self.generation = generation
        task = Task { [weak self] in
            while !Task.isCancelled {
                guard let self, self.generation == generation else { return }
                let socket = URLSession.shared.webSocketTask(with: request)
                socket.maximumMessageSize = Wire.maxFrameSize
                self.socket = socket
                socket.resume()
                var heartbeat: Task<Void, Never>?
                do {
                    try await Self.ping(socket)
                    try Task.checkCancellation()
                    guard self.generation == generation else {
                        socket.cancel(with: .goingAway, reason: nil)
                        return
                    }
                    self.onIssue?(nil)
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
                        try Task.checkCancellation()
                        guard self.generation == generation else { break }
                        guard case .data(let encrypted) = message, encrypted.count <= Wire.maxFrameSize else { continue }
                        let request = try JSONDecoder().decode(BridgeRequest.self, from: Wire.open(encrypted, key: self.key))
                        var response: BridgeResponse
                        do {
                            try self.requestGuard.admit(request)
                            response = self.handler(request)
                        } catch { response = BridgeResponse(ok: false, error: error.localizedDescription) }
                        response.requestId = request.requestId
                        let payload = try Wire.seal(JSONEncoder().encode(response), key: self.key)
                        guard payload.count <= Wire.maxFrameSize else { continue }
                        try await socket.send(.data(payload))
                    }
                } catch {
                    if !Task.isCancelled, self.generation == generation {
                        self.onIssue?(Self.issue(status: (socket.response as? HTTPURLResponse)?.statusCode,
                                                 error: error))
                    }
                }
                heartbeat?.cancel()
                socket.cancel(with: .goingAway, reason: nil)
                guard self.generation == generation else { return }
                self.socket = nil
                self.onState?(false)
                if Task.isCancelled { return }
                do { try await Task.sleep(nanoseconds: 5_000_000_000) } catch { return }
            }
        }
    }
    public func stop() {
        generation = nil
        task?.cancel(); task = nil
        socket?.cancel(with: .goingAway, reason: nil); socket = nil
        onState?(false)
    }
    static func issue(status: Int?, error: Error) -> String {
        switch status ?? 0 {
        case 401, 403: return "Relay registration was rejected. Check this Mac’s relay registration."
        case 429: return "Relay reconnect limit reached. Retrying shortly."
        case 500...599: return "Relay is temporarily unavailable. Retrying shortly."
        default:
            let code = (error as NSError).code
            if (error as NSError).domain == NSURLErrorDomain {
                if code == NSURLErrorNotConnectedToInternet { return "This Mac is offline. Waiting for internet access." }
                if code == NSURLErrorTimedOut { return "Relay did not respond in time. Retrying shortly." }
                if [NSURLErrorSecureConnectionFailed, NSURLErrorServerCertificateUntrusted,
                    NSURLErrorServerCertificateHasBadDate, NSURLErrorServerCertificateHasUnknownRoot,
                    NSURLErrorServerCertificateNotYetValid].contains(code) {
                    return "Could not verify the relay’s secure connection. Check the network and relay address."
                }
            }
            return "Relay connection interrupted. Retrying shortly."
        }
    }
    private static func ping(_ socket: URLSessionWebSocketTask) async throws {
        try await RelayPing.wait { socket.sendPing(pongReceiveHandler: $0) }
    }
}
