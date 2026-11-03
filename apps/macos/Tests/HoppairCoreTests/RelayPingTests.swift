import XCTest
@testable import HoppairCore

private final class PingCallbackBox: @unchecked Sendable {
    private let lock = NSLock()
    private var callback: RelayPing.Completion?
    func store(_ callback: @escaping RelayPing.Completion) {
        lock.lock(); self.callback = callback; lock.unlock()
    }
    func deliver(_ error: Error?) {
        lock.lock(); let callback = callback; lock.unlock()
        callback?(error)
    }
}

@MainActor
final class RelayPingTests: XCTestCase {
    func testDuplicatePongsAndLateErrorCannotResumeTwice() async throws {
        try await RelayPing.wait { complete in
            complete(nil)
            complete(nil)
            complete(URLError(.networkConnectionLost))
        }
    }

    func testFirstConnectionErrorWinsOverDuplicatesAndLatePong() async {
        do {
            try await RelayPing.wait { complete in
                complete(URLError(.networkConnectionLost))
                complete(URLError(.cancelled))
                complete(nil)
            }
            XCTFail("The original connection error must be returned.")
        } catch {
            XCTAssertEqual((error as? URLError)?.code, .networkConnectionLost)
        }
    }

    func testConcurrentPongAndDisconnectCallbacksCompleteOnce() async {
        for _ in 0..<50 {
            do {
                try await RelayPing.wait { complete in
                    DispatchQueue.concurrentPerform(iterations: 32) { index in
                        complete(index.isMultiple(of: 2) ? nil : URLError(.networkConnectionLost))
                    }
                }
            } catch {
                XCTAssertEqual((error as? URLError)?.code, .networkConnectionLost)
            }
        }
    }

    func testCancellationFinishesWithoutWaitingForPongAndIgnoresLateCallbacks() async {
        let callback = PingCallbackBox()
        let started = expectation(description: "ping sent")
        let finished = expectation(description: "cancelled ping finished")
        let task = Task {
            defer { finished.fulfill() }
            do {
                try await RelayPing.wait { complete in callback.store(complete); started.fulfill() }
                XCTFail("Cancellation must throw.")
            } catch { XCTAssertTrue(error is CancellationError) }
        }
        await fulfillment(of: [started], timeout: 2)
        task.cancel()
        await fulfillment(of: [finished], timeout: 2)
        // Also releases a broken implementation after the bounded assertion above.
        callback.deliver(nil)
        callback.deliver(URLError(.cancelled))
        await task.value
    }

    func testCancellationBeforeRegistrationDoesNotSendPing() async {
        let task = Task {
            try await RelayPing.wait { _ in XCTFail("A cancelled operation must not send a ping.") }
        }
        task.cancel()
        do { try await task.value; XCTFail("Cancellation must throw.") }
        catch { XCTAssertTrue(error is CancellationError) }
    }

    func testMissingPongTimesOutAndLateCallbacksAreIgnored() async {
        let callback = PingCallbackBox()
        let finished = expectation(description: "missing pong timed out")
        let task = Task {
            defer { finished.fulfill() }
            do {
                try await RelayPing.wait(timeout: .milliseconds(20)) { callback.store($0) }
                XCTFail("A missing pong must time out.")
            } catch { XCTAssertEqual((error as? URLError)?.code, .timedOut) }
        }
        await fulfillment(of: [finished], timeout: 2)
        callback.deliver(nil)
        callback.deliver(URLError(.networkConnectionLost))
        await task.value
    }
}
