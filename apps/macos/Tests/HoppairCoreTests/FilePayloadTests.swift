import XCTest
import Darwin
@testable import HoppairCore

final class FilePayloadTests: XCTestCase {
    func testReadsEmptyAndMaximumSizedFilesWithoutTruncation() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let file = root.appendingPathComponent("payload")
        for data in [Data(), Data(repeating: 0xAB, count: Wire.maxFileSize)] {
            try data.write(to: file)
            XCTAssertEqual(try FilePayload.read(file), data)
        }
        try Data(repeating: 0, count: Wire.maxFileSize + 1).write(to: file)
        XCTAssertThrowsError(try FilePayload.read(file))
    }

    func testRejectsDirectoriesStreamsAndNonFileAddresses() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        XCTAssertThrowsError(try FilePayload.read(root))
        XCTAssertThrowsError(try FilePayload.read(URL(string: "https://example.com/file")!))
        let fifo = root.appendingPathComponent("pipe")
        XCTAssertEqual(mkfifo(fifo.path, 0o600), 0)
        // No writer exists. A blocking open would hang this test before file-type validation.
        XCTAssertThrowsError(try FilePayload.read(fifo))
    }
}
