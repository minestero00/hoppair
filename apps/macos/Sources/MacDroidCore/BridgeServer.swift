import Foundation
import Network

public final class BridgeServer {
    private var listener: NWListener?
    private var connections: [UUID: NWConnection] = [:]
    private var timers: [UUID: DispatchWorkItem] = [:]
    private let key: Data
    public var handler: ((BridgeRequest) -> BridgeResponse)?
    public var onState: ((String) -> Void)?
    public var onPort: ((UInt16) -> Void)?
    private let loopbackOnly: Bool
    private let port: UInt16
    public init(key: Data, port: UInt16 = 49271, loopbackOnly: Bool = false) { self.key = key; self.port = port; self.loopbackOnly = loopbackOnly }
    public func start() throws {
        let parameters = NWParameters.tcp
        if loopbackOnly { parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: NWEndpoint.Port(rawValue: port)!) }
        let listener = try loopbackOnly ? NWListener(using: parameters) : NWListener(using: parameters, on: NWEndpoint.Port(rawValue: port)!)
        self.listener = listener
        listener.stateUpdateHandler = { [weak self, weak listener] state in
            switch state {
            case .ready:
                if let port = listener?.port { self?.onPort?(port.rawValue) }
                self?.onState?("Ready to connect your phone")
            case .failed(let error): self?.onState?(error.localizedDescription)
            default: break
            }
        }
        listener.newConnectionHandler = { [weak self] connection in self?.accept(connection) }
        listener.start(queue: .main)
    }
    public func stop() {
        listener?.cancel(); listener = nil
        for id in Array(connections.keys) { finish(id) }
    }
    private func finish(_ id: UUID) {
        timers.removeValue(forKey: id)?.cancel()
        connections.removeValue(forKey: id)?.cancel()
    }
    private func accept(_ connection: NWConnection) {
        guard connections.count < 8 else { connection.cancel(); return }
        let id = UUID()
        connections[id] = connection
        let timer = DispatchWorkItem { [weak self] in self?.finish(id) }
        timers[id] = timer
        DispatchQueue.main.asyncAfter(deadline: .now() + 20, execute: timer)
        connection.start(queue: .main)
        read(connection, count: 4, accumulated: Data()) { [weak self] header in
            guard let self, let header, let length = try? Wire.length(header) else { self?.finish(id); return }
            self.read(connection, count: length, accumulated: Data()) { [weak self] body in
                guard let self, let body else { self?.finish(id); return }
                do {
                    let plain = try Wire.open(body, key: self.key)
                    let request = try JSONDecoder().decode(BridgeRequest.self, from: plain)
                    guard request.version == 1, abs(Date().timeIntervalSince1970 - request.issuedAt) <= 60 else {
                        self.finish(id); return
                    }
                    let response = self.handler?(request) ?? BridgeResponse(ok: false, error: "Not ready yet.")
                    let payload = try Wire.frame(Wire.seal(JSONEncoder().encode(response), key: self.key))
                    connection.send(content: payload, completion: .contentProcessed { [weak self] _ in self?.finish(id) })
                } catch { self.finish(id) }
            }
        }
    }
    private func read(_ connection: NWConnection, count: Int, accumulated: Data, completion: @escaping (Data?) -> Void) {
        let remaining = count - accumulated.count
        connection.receive(minimumIncompleteLength: 1, maximumLength: remaining) { [weak self] data, _, complete, error in
            var next = accumulated
            if let data { next.append(data) }
            if next.count == count { completion(next) }
            else if complete || error != nil { completion(nil) }
            else { self?.read(connection, count: count, accumulated: next, completion: completion) }
        }
    }
}
