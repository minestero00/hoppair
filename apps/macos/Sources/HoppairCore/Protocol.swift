import Foundation
import CryptoKit

public enum BridgeError: LocalizedError {
    case invalid(String)
    public var errorDescription: String? { if case .invalid(let text) = self { return text }; return nil }
}

public struct ShelfItem: Codable, Identifiable, Equatable, Sendable {
    public var id: String
    public var kind: String
    public var title: String
    public var text: String?
    public var filename: String?
    public var mime: String?
    public var size: Int
    public var createdAt: Double
    public var source: String
    public var pinned: Bool
    public init(id: String = UUID().uuidString.lowercased(), kind: String, title: String, text: String? = nil,
                filename: String? = nil, mime: String? = nil, size: Int = 0,
                createdAt: Double = Date().timeIntervalSince1970, source: String = "Mac", pinned: Bool = false) {
        self.id = id; self.kind = kind; self.title = title; self.text = text; self.filename = filename
        self.mime = mime; self.size = size; self.createdAt = createdAt; self.source = source; self.pinned = pinned
    }
}

public struct BridgeRequest: Codable, Sendable {
    public var version: Int = 1
    public var requestId: String = UUID().uuidString
    public var issuedAt: Double = Date().timeIntervalSince1970
    public var action: String
    public var deviceName: String?
    public var item: ShelfItem?
    public var data: String?
    public var id: String?
    public var ids: [String]?
    public init(action: String, item: ShelfItem? = nil, data: String? = nil, id: String? = nil) {
        self.action = action; self.item = item; self.data = data; self.id = id
    }
}
public struct BridgeResponse: Codable, Sendable {
    public var requestId: String?
    public var ok: Bool
    public var error: String?
    public var items: [ShelfItem]?
    public var data: String?
    public var cameraRequest: String?
    public var nextCursor: String?
    public var deviceName: String?
    public var syncAcknowledgement: Bool?
    public var supportsDeletion: Bool?
    public var deleted: [String]?
    public init(ok: Bool = true, error: String? = nil, items: [ShelfItem]? = nil,
                data: String? = nil, cameraRequest: String? = nil, nextCursor: String? = nil) {
        self.ok = ok; self.error = error; self.items = items; self.data = data; self.cameraRequest = cameraRequest; self.nextCursor = nextCursor
    }
}

public enum Wire {
    public static let maxFileSize = 8 * 1024 * 1024
    public static let maxFrameSize = 12 * 1024 * 1024
    public static func seal(_ data: Data, key: Data) throws -> Data {
        guard key.count == 32 else { throw BridgeError.invalid("Invalid pairing key.") }
        guard data.count <= maxFrameSize - 28 else { throw BridgeError.invalid("Transfer exceeds the size limit.") }
        return try AES.GCM.seal(data, using: SymmetricKey(data: key)).combined!
    }
    public static func open(_ data: Data, key: Data) throws -> Data {
        guard key.count == 32 else { throw BridgeError.invalid("Invalid pairing key.") }
        guard (28...maxFrameSize).contains(data.count) else { throw BridgeError.invalid("Invalid encrypted frame size.") }
        return try AES.GCM.open(AES.GCM.SealedBox(combined: data), using: SymmetricKey(data: key))
    }
    public static func frame(_ encrypted: Data) throws -> Data {
        guard encrypted.count <= maxFrameSize, encrypted.count >= 28 else { throw BridgeError.invalid("Transfer exceeds the size limit.") }
        var length = UInt32(encrypted.count).bigEndian
        return withUnsafeBytes(of: &length) { Data($0) } + encrypted
    }
    public static func length(_ header: Data) throws -> Int {
        guard header.count == 4 else { throw BridgeError.invalid("Invalid frame.") }
        let count = header.reduce(0) { ($0 << 8) | Int($1) }
        guard count >= 28, count <= maxFrameSize else { throw BridgeError.invalid("Transfer exceeds the size limit.") }
        return count
    }
}
