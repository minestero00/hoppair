import Foundation

// URLSession may deliver a late pong/error while a socket is being cancelled.
// Claim completion under the lock, then resume outside it to avoid reentrancy.
private final class PingCompletion: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Void, Error>?
    private var result: Result<Void, Error>?

    func install(_ continuation: CheckedContinuation<Void, Error>) {
        lock.lock()
        if let result {
            lock.unlock()
            continuation.resume(with: result)
        } else {
            self.continuation = continuation
            lock.unlock()
        }
    }

    func finish(_ result: Result<Void, Error>) {
        lock.lock()
        guard self.result == nil else { lock.unlock(); return }
        self.result = result
        let continuation = self.continuation
        self.continuation = nil
        lock.unlock()
        continuation?.resume(with: result)
    }
}

enum RelayPing {
    typealias Completion = @Sendable (Error?) -> Void

    static func wait(timeout: Duration = .seconds(10),
                     send: (@escaping Completion) -> Void) async throws {
        let completion = PingCompletion()
        let deadline = Task {
            do {
                try await Task.sleep(for: timeout)
                completion.finish(.failure(URLError(.timedOut)))
            } catch { }
        }
        defer { deadline.cancel() }
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                completion.install(continuation)
                guard !Task.isCancelled else {
                    completion.finish(.failure(CancellationError()))
                    return
                }
                send { error in
                    completion.finish(error.map { .failure($0) } ?? .success(()))
                }
            }
        } onCancel: {
            completion.finish(.failure(CancellationError()))
        }
    }
}
