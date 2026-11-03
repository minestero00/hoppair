import Foundation

/// Share one instance between the TCP and relay hosts for a pairing. Callers may
/// use separate queues; admission is atomic and happens before invoking handlers.
public final class RequestReplayGuard {
    private let lock = NSLock()
    private var accepted: [UUID: TimeInterval] = [:]
    private let capacity: Int
    private let retention: TimeInterval = 120

    public init(capacity: Int = 4096) { self.capacity = max(1, capacity) }

    public func admit(_ request: BridgeRequest, now: TimeInterval = Date().timeIntervalSince1970,
                      uptime: TimeInterval = ProcessInfo.processInfo.systemUptime) throws {
        guard request.version == 1, request.issuedAt.isFinite, now.isFinite,
              abs(now - request.issuedAt) <= 60,
              request.requestId.utf8.count == 36, let id = UUID(uuidString: request.requestId) else {
            throw BridgeError.invalid("Invalid or expired request. Try again.")
        }
        lock.lock()
        defer { lock.unlock() }
        // Retain for the complete possible validity interval, including requests
        // issued up to 60 seconds in the future. A monotonic clock prevents a
        // wall-clock adjustment from prematurely evicting replay protection.
        accepted = accepted.filter { uptime - $0.value <= retention }
        guard accepted[id] == nil else {
            throw BridgeError.invalid("This request was already received. Refresh and try again.")
        }
        guard accepted.count < capacity else {
            throw BridgeError.invalid("Too many recent requests. Wait a moment and try again.")
        }
        accepted[id] = uptime
    }
}
