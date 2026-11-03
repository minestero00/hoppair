import XCTest
@testable import HoppairCore

final class ShelfSearchTests: XCTestCase {
    func testBodyTitleCategoriesAndStableOrdering() async throws {
        let note = ShelfItem(kind: "text", title: "Morning", text: "Coffee in Seoul", createdAt: 1)
        let link = ShelfItem(kind: "link", title: "COFFEE", createdAt: 2, pinned: true)
        let file = ShelfItem(kind: "file", title: "coffee.png", createdAt: 3)
        let tie = ShelfItem(kind: "file", title: "coffee.jpg", createdAt: 3)
        let items = [note, link, file, tie]
        let all = try await ShelfSearchRequest(items: items, query: "coffee", section: "All Items").results()
        XCTAssertEqual(all.map(\.id), [link.id, file.id, tie.id, note.id])
        let pinned = try await ShelfSearchRequest(items: items, query: "coffee", section: "Pinned").results()
        XCTAssertEqual(pinned.map(\.id), [link.id])
        let files = try await ShelfSearchRequest(items: items, query: "", section: "Files").results()
        XCTAssertEqual(files.map(\.id), [file.id, tie.id])
        let text = try await ShelfSearchRequest(items: items, query: "coffee", section: "Text & Links").results()
        XCTAssertEqual(text.map(\.id), [link.id, note.id])
    }
    func testMaximumShelfLongBodiesAndNoMatch() async throws {
        let body = String(repeating: "x", count: 65_000)
        var items = (0..<500).map { ShelfItem(kind: "text", title: "Note \($0)", text: body, createdAt: Double($0)) }
        items[123].text = body + " needle"
        let matches = try await ShelfSearchRequest(items: items, query: "needle", section: "All Items").results()
        XCTAssertEqual(matches.map(\.id), [items[123].id])
        let missing = try await ShelfSearchRequest(items: items, query: "absent", section: "All Items").results()
        XCTAssertTrue(missing.isEmpty)
    }
    func testAlreadyCanceledRequestThrows() async throws {
        let task = Task {
            withUnsafeCurrentTask { $0?.cancel() }
            return try await ShelfSearchRequest(items: [], query: "", section: "All Items").results()
        }
        do { _ = try await task.value; XCTFail("Canceled search returned results") }
        catch is CancellationError { }
    }
}
