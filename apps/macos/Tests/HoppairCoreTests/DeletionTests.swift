import XCTest
@testable import HoppairCore

final class DeletionTests: XCTestCase {
    private func withStore(_ body: (ShelfStore) throws -> Void) throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        try body(ShelfStore(root: root))
    }
    func testDeletionPersistsAndRejectsDelayedUpload() throws {
        try withStore { store in
            let item = ShelfItem(kind: "text", title: "Fixture", text: "Keep no copy")
            try store.put(item)
            try store.delete(item.id.uppercased())
            XCTAssertTrue(store.items.isEmpty)
            let reopened = try ShelfStore(root: store.root)
            XCTAssertEqual(try reopened.deletedIDs(in: [item.id]), [item.id])
            XCTAssertThrowsError(try reopened.put(item))
            XCTAssertTrue(reopened.items.isEmpty)
        }
    }
    func testStaleIndexCannotResurrectDeletedItem() throws {
        try withStore { store in
            let item = ShelfItem(kind: "text", title: "Fixture", text: "Old index")
            try store.put(item)
            let index = store.root.appendingPathComponent("shelf.json")
            let oldIndex = try Data(contentsOf: index)
            try store.delete(item.id)
            // Simulate interruption before the optional index compaction persisted.
            try oldIndex.write(to: index, options: .atomic)
            XCTAssertTrue(try ShelfStore(root: store.root).items.isEmpty)
        }
    }
    func testFileDeletionReleasesPayloadAndCapacity() throws {
        try withStore { store in
            let file = ShelfItem(kind: "file", title: "Fixture", filename: "fixture.bin")
            try store.put(file, content: Data([1, 2, 3]))
            for index in 1..<500 { try store.put(ShelfItem(kind: "text", title: "\(index)", text: "Note")) }
            XCTAssertThrowsError(try store.put(ShelfItem(kind: "text", title: "Full", text: "Note")))
            try store.delete(file.id)
            XCTAssertFalse(FileManager.default.fileExists(atPath: try store.fileURL(file.id).path))
            try store.put(ShelfItem(kind: "text", title: "New", text: "Available slot"))
            XCTAssertEqual(store.items.count, 500)
        }
    }
    func testUnknownDeletionIsIdempotentAndPreventsLostAckResurrection() throws {
        try withStore { store in
            let item = ShelfItem(kind: "text", title: "Fixture", text: "Queued")
            try store.delete(item.id)
            try store.delete(item.id)
            XCTAssertEqual(try store.deletedIDs(in: [item.id, item.id.uppercased()]), [item.id])
            XCTAssertThrowsError(try store.put(item))
        }
    }
    func testDeletionQueryIsBoundedAndValidatesEveryID() throws {
        try withStore { store in
            XCTAssertThrowsError(try store.delete("../shelf.json"))
            XCTAssertThrowsError(try store.deletedIDs(in: ["invalid"]))
            XCTAssertThrowsError(try store.deletedIDs(in: Array(repeating: UUID().uuidString, count: 1001)))
            XCTAssertEqual(try store.deletedIDs(in: []), [])
        }
    }
    func testCleanupAdvancesPastBlockedReceiptsAfterRestart() throws {
        try withStore { store in
            let item = ShelfItem(kind: "file", title: "Cleanup", filename: "cleanup.bin")
            try store.put(item, content: Data([1, 2, 3]))
            let journal = try DeletionJournal(root: store.root)
            for _ in 0..<40 {
                let id = UUID().uuidString.lowercased()
                let directory = try store.fileURL(id)
                try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                try journal.record(id)
            }
            try journal.record(item.id)
            for _ in 0..<4 { _ = try ShelfStore(root: store.root) }
            XCTAssertFalse(FileManager.default.fileExists(atPath: try store.fileURL(item.id).path))
        }
    }

    func testUnexpectedDirectoryIsNotRecursivelyRemoved() throws {
        try withStore { store in
            let id = UUID().uuidString.lowercased()
            let directory = try store.fileURL(id)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let child = directory.appendingPathComponent("keep.txt")
            try Data("fixture".utf8).write(to: child)
            try store.delete(id)
            XCTAssertTrue(FileManager.default.fileExists(atPath: child.path))
            XCTAssertEqual(try store.deletedIDs(in: [id]), [id])
        }
    }
}
