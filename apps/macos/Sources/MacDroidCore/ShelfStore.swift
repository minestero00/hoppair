import Foundation

/// The caller serializes access. The app uses the main actor; tests use isolated roots.
public final class ShelfStore {
    public private(set) var items: [ShelfItem] = []
    public let root: URL
    private let index: URL
    public init(root: URL) throws {
        self.root = root; self.index = root.appendingPathComponent("shelf.json")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true,
                                               attributes: [.posixPermissions: 0o700])
        if FileManager.default.fileExists(atPath: index.path) {
            items = try JSONDecoder().decode([ShelfItem].self, from: Data(contentsOf: index))
        }
    }
    public func fileURL(_ id: String) throws -> URL {
        guard UUID(uuidString: id) != nil else { throw BridgeError.invalid("Invalid item ID.") }
        return root.appendingPathComponent(id.lowercased())
    }
    public func content(_ id: String) throws -> Data {
        guard items.contains(where: { $0.id == id && $0.kind == "file" }) else {
            throw BridgeError.invalid("File not found.")
        }
        return try Data(contentsOf: fileURL(id))
    }
    public func put(_ input: ShelfItem, content: Data? = nil) throws {
        guard UUID(uuidString: input.id) != nil else { throw BridgeError.invalid("Invalid item ID.") }
        if items.contains(where: { $0.id.lowercased() == input.id.lowercased() }) { return } // Retry after a lost acknowledgement.
        guard items.count < 500 else { throw BridgeError.invalid("The shelf is full (500 items).") }
        guard input.title.count <= 512, ["text", "link", "file"].contains(input.kind),
              input.source.count <= 100, input.createdAt.isFinite else { throw BridgeError.invalid("Invalid item.") }
        var item = input
        item.id = item.id.lowercased()
        if item.kind == "file" {
            item.text = nil
            guard (item.mime ?? "").utf8.count <= 256 else { throw BridgeError.invalid("Invalid file type information.") }
            guard let content, content.count <= Wire.maxFileSize else { throw BridgeError.invalid("Files must be 8 MiB or smaller.") }
            let filename = (item.filename ?? "Files").replacingOccurrences(of: "\\", with: "/")
            item.filename = String((filename as NSString).lastPathComponent.prefix(160))
            item.size = content.count
            try content.write(to: fileURL(item.id), options: .atomic)
        } else {
            guard let text = item.text, text.utf8.count <= 64 * 1024, !text.isEmpty else { throw BridgeError.invalid("Text must be 64 KiB or smaller.") }
            if item.kind == "link" {
                guard let url = URL(string: text), ["http", "https"].contains(url.scheme?.lowercased() ?? "") else {
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
}
