import Foundation

public struct ShelfSearchRequest: Equatable, Sendable {
    public let items: [ShelfItem]
    public let query: String
    public let section: String
    public init(items: [ShelfItem], query: String, section: String) {
        self.items = items; self.query = query; self.section = section
    }
    /// Cancellation propagates to the detached worker when a new query replaces this one.
    public func results() async throws -> [ShelfItem] {
        let worker = Task.detached(priority: .userInitiated) { try self.filter() }
        return try await withTaskCancellationHandler {
            let result = try await worker.value
            try Task.checkCancellation()
            return result
        } onCancel: { worker.cancel() }
    }
    private func filter() throws -> [ShelfItem] {
        var matches: [(Int, ShelfItem)] = []
        for (index, item) in items.enumerated() {
            try Task.checkCancellation()
            guard section != "Pinned" || item.pinned,
                  section != "Files" || item.kind == "file",
                  section != "Text & Links" || item.kind != "file" else { continue }
            if query.isEmpty || item.title.localizedCaseInsensitiveContains(query) ||
                item.text?.localizedCaseInsensitiveContains(query) == true {
                matches.append((index, item))
            }
        }
        try Task.checkCancellation()
        return matches.sorted { a, b in
            if a.1.pinned != b.1.pinned { return a.1.pinned }
            if a.1.createdAt != b.1.createdAt { return a.1.createdAt > b.1.createdAt }
            return a.0 < b.0
        }.map(\.1)
    }
}
