import Foundation

public struct ShelfPage: Sendable {
    public let items: [ShelfItem]
    public let nextCursor: String?
}

/// The caller serializes access. The app uses the main actor; tests use isolated roots.
public final class ShelfStore {
    public private(set) var items: [ShelfItem] = []
    public let root: URL
    private let index: URL
    private let deletions: DeletionJournal
    public init(root: URL) throws {
        self.root = root; self.index = root.appendingPathComponent("shelf.json")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true,
                                               attributes: [.posixPermissions: 0o700])
        deletions = try DeletionJournal(root: root)
        if FileManager.default.fileExists(atPath: index.path) {
            items = try JSONDecoder().decode([ShelfItem].self, from: Data(contentsOf: index))
            // Older versions accepted scheme-only links such as https:foo.
            // Preserve their content and identity as shareable text. Loading is
            // read-only; the next ordinary shelf mutation persists this repair.
            for position in items.indices where items[position].kind == "link" {
                if let text = items[position].text, !text.isEmpty, text.utf8.count <= 64 * 1024,
                   !WebLink.isValid(text) {
                    items[position].kind = "text"
                }
            }
        }
        let removed = try deletions.contains(items.map { $0.id.lowercased() })
        items.removeAll { removed.contains($0.id.lowercased()) }
        cleanupDeletedFiles()
    }
    /// UUID cursors anchor the previous page's last item. Prepending new items
    /// between pages cannot move the cursor or duplicate already returned items.
    public func page(after cursor: String? = nil) throws -> ShelfPage {
        let start: Int
        if let cursor {
            guard UUID(uuidString: cursor) != nil,
                  let previous = items.firstIndex(where: { $0.id.lowercased() == cursor.lowercased() }) else {
                throw BridgeError.invalid("The shelf changed. Refresh and try again.")
            }
            start = previous + 1
        } else { start = 0 }
        var page: [ShelfItem] = []
        var encodedSize = 1024 // response keys, request ID, camera ID, cursor, and JSON separators
        let encoder = JSONEncoder()
        for item in items.dropFirst(start) {
            let size = try encoder.encode(item).count + 1
            guard size + 1024 <= 2 * 1024 * 1024 else {
                throw BridgeError.invalid("A shelf item exceeds the page limit.")
            }
            if page.count == 100 || encodedSize + size > 2 * 1024 * 1024 { break }
            page.append(item)
            encodedSize += size
        }
        let hasMore = start + page.count < items.count
        return ShelfPage(items: page, nextCursor: hasMore ? page.last?.id : nil)
    }

    /// Legacy clients still receive small shelves. Large snapshots must use the
    /// bounded page action instead of losing the connection during encryption.
    public func legacyList(cameraRequest: String? = nil) throws -> BridgeResponse {
        var encodedSize = 1024
        let encoder = JSONEncoder()
        for item in items {
            encodedSize += try encoder.encode(item).count + 1
            guard encodedSize <= Wire.maxFrameSize - 28 else {
                return BridgeResponse(ok: false, error: "Update Hoppair on your phone to sync this shelf.")
            }
        }
        return BridgeResponse(items: items, cameraRequest: cameraRequest)
    }

    public func fileURL(_ id: String) throws -> URL {
        guard UUID(uuidString: id) != nil else { throw BridgeError.invalid("Invalid item ID.") }
        return root.appendingPathComponent(id.lowercased())
    }
    public func content(_ id: String) throws -> Data {
        guard items.contains(where: { $0.id == id && $0.kind == "file" }) else {
            throw BridgeError.invalid("File not found.")
        }
        return try FilePayload.read(fileURL(id))
    }
    public func put(_ input: ShelfItem, content: Data? = nil) throws {
        guard UUID(uuidString: input.id) != nil else { throw BridgeError.invalid("Invalid item ID.") }
        guard try deletions.contains([input.id.lowercased()]).isEmpty else { throw BridgeError.invalid("This item was deleted from the shelf.") }
        if items.contains(where: { $0.id.lowercased() == input.id.lowercased() }) { return } // Retry after a lost acknowledgement.
        guard items.count < 500 else { throw BridgeError.invalid("The shelf is full (500 items).") }
        guard input.title.utf8.count <= 4096, input.title.count <= 512, ["text", "link", "file"].contains(input.kind),
              input.source.utf8.count <= 1024, input.source.count <= 100, input.createdAt.isFinite else { throw BridgeError.invalid("Invalid item.") }
        var item = input
        item.id = item.id.lowercased()
        if item.kind == "file" {
            item.text = nil
            guard (item.mime ?? "").utf8.count <= 256,
                  !(item.mime ?? "").unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }) else { throw BridgeError.invalid("Invalid file type information.") }
            guard let content, content.count <= Wire.maxFileSize else { throw BridgeError.invalid("Files must be 8 MiB or smaller.") }
            let filename = (item.filename ?? "Files").replacingOccurrences(of: "\\", with: "/")
            let basename = (filename as NSString).lastPathComponent.unicodeScalars
                .filter { !CharacterSet.controlCharacters.contains($0) }.prefix(160)
            item.filename = basename.isEmpty ? "Files" : String(String.UnicodeScalarView(basename))
            item.size = content.count
            try content.write(to: fileURL(item.id), options: .atomic)
        } else {
            item.filename = nil; item.mime = nil
            guard let text = item.text, text.utf8.count <= 64 * 1024, !text.isEmpty else { throw BridgeError.invalid("Text must be 64 KiB or smaller.") }
            if item.kind == "link" {
                guard WebLink.isValid(text) else {
                    throw BridgeError.invalid("Links must use http or https.")
                }
            }
            item.size = text.utf8.count
        }
        let next = [item] + items
        try JSONEncoder().encode(next).write(to: index, options: .atomic)
        items = next
    }
    public func togglePin(_ id: String) throws {
        var next = items
        guard let i = next.firstIndex(where: { $0.id == id }) else { return }
        next[i].pinned.toggle()
        try JSONEncoder().encode(next).write(to: index, options: .atomic)
        items = next
    }

    public func deletedIDs(in ids: [String]) throws -> [String] {
        guard ids.count <= 1000, ids.allSatisfy({ UUID(uuidString: $0) != nil }) else { throw BridgeError.invalid("Invalid deletion query.") }
        return try deletions.contains(ids.map { $0.lowercased() }).sorted()
    }

    public func delete(_ id: String) throws {
        guard UUID(uuidString: id) != nil else { throw BridgeError.invalid("Invalid item ID.") }
        let id = id.lowercased()
        // The receipt is the commit point. Even a stale index or a delayed put
        // cannot restore the item after this durable write succeeds.
        try deletions.record(id)
        items.removeAll { $0.id.lowercased() == id }
        // The journal remains authoritative if index compaction fails.
        try? JSONEncoder().encode(items).write(to: index, options: .atomic)
        cleanupDeletedFiles(preferred: id)
    }

    private func cleanupDeletedFiles(preferred: String? = nil) {
        guard let queued = try? deletions.pendingCleanup() else { return }
        let pending = preferred.map { id in [id] + queued.filter { $0 != id } } ?? queued
        for id in pending {
            do {
                let url = try fileURL(id)
                if FileManager.default.fileExists(atPath: url.path) {
                    let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
                    guard attributes[.type] as? FileAttributeType != .typeDirectory else { continue }
                    try FileManager.default.removeItem(at: url)
                }
                try deletions.markCleaned(id)
            } catch { /* Keep the cleanup receipt for a later retry. */ }
        }
    }
}
