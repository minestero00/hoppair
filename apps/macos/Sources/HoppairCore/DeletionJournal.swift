import Foundation
import SQLite3

/// Durable deletion receipts prevent a delayed upload from restoring a deleted item.
/// SQLite keeps receipt lookup bounded without loading deletion history into memory.
final class DeletionJournal {
    private var database: OpaquePointer?
    private let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

    init(root: URL) throws {
        let path = root.appendingPathComponent("deletions.sqlite").path
        guard sqlite3_open_v2(path, &database, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX, nil) == SQLITE_OK else {
            if let database { sqlite3_close(database) }
            database = nil
            throw BridgeError.invalid("Could not open deletion history.")
        }
        do {
            try execute("PRAGMA synchronous=FULL")
            try execute("CREATE TABLE IF NOT EXISTS deleted (id TEXT PRIMARY KEY NOT NULL, cleaned INTEGER NOT NULL DEFAULT 0)")
            try execute("CREATE INDEX IF NOT EXISTS pending_cleanup ON deleted(cleaned)")
            try execute("CREATE TABLE IF NOT EXISTS cleanup_progress (singleton INTEGER PRIMARY KEY, position INTEGER NOT NULL)")
            try execute("INSERT OR IGNORE INTO cleanup_progress VALUES(1, 0)")
        } catch {
            sqlite3_close(database); database = nil; throw error
        }
    }
    deinit { sqlite3_close(database) }

    private func execute(_ sql: String) throws {
        guard sqlite3_exec(database, sql, nil, nil, nil) == SQLITE_OK else { throw BridgeError.invalid("Could not save deletion history.") }
    }
    private func statement(_ sql: String) throws -> OpaquePointer {
        var result: OpaquePointer?
        guard sqlite3_prepare_v2(database, sql, -1, &result, nil) == SQLITE_OK, let result else {
            throw BridgeError.invalid("Could not read deletion history.")
        }
        return result
    }
    private func bind(_ id: String, to statement: OpaquePointer) throws {
        guard sqlite3_bind_text(statement, 1, id, -1, transient) == SQLITE_OK else { throw BridgeError.invalid("Could not read deletion history.") }
    }
    func contains(_ ids: [String]) throws -> Set<String> {
        let query = try statement("SELECT 1 FROM deleted WHERE id=?")
        defer { sqlite3_finalize(query) }
        var result = Set<String>()
        for id in ids {
            sqlite3_reset(query); sqlite3_clear_bindings(query)
            try bind(id, to: query)
            let status = sqlite3_step(query)
            if status == SQLITE_ROW { result.insert(id) }
            else if status != SQLITE_DONE { throw BridgeError.invalid("Could not read deletion history.") }
        }
        return result
    }
    func record(_ id: String) throws {
        let insert = try statement("INSERT OR IGNORE INTO deleted(id) VALUES(?)")
        defer { sqlite3_finalize(insert) }
        try bind(id, to: insert)
        guard sqlite3_step(insert) == SQLITE_DONE else { throw BridgeError.invalid("Could not save deletion history.") }
    }
    func markCleaned(_ id: String) throws {
        let update = try statement("UPDATE deleted SET cleaned=1 WHERE id=?")
        defer { sqlite3_finalize(update) }
        try bind(id, to: update)
        guard sqlite3_step(update) == SQLITE_DONE else { throw BridgeError.invalid("Could not save deletion history.") }
    }
    func pendingCleanup() throws -> [String] {
        let position = try statement("SELECT position FROM cleanup_progress WHERE singleton=1")
        defer { sqlite3_finalize(position) }
        guard sqlite3_step(position) == SQLITE_ROW else { throw BridgeError.invalid("Could not read deletion history.") }
        let cursor = sqlite3_column_int64(position, 0)
        var batch = try pending(after: cursor)
        if batch.isEmpty && cursor > 0 { batch = try pending(after: 0) }
        if let last = batch.last { try execute("UPDATE cleanup_progress SET position=\(last.0) WHERE singleton=1") }
        return batch.map(\.1)
    }
    private func pending(after cursor: Int64) throws -> [(Int64, String)] {
        let query = try statement("SELECT rowid,id FROM deleted WHERE cleaned=0 AND rowid>? ORDER BY rowid LIMIT 16")
        defer { sqlite3_finalize(query) }
        guard sqlite3_bind_int64(query, 1, cursor) == SQLITE_OK else { throw BridgeError.invalid("Could not read deletion history.") }
        var ids: [(Int64, String)] = []
        while true {
            let status = sqlite3_step(query)
            if status == SQLITE_DONE { return ids }
            guard status == SQLITE_ROW, let value = sqlite3_column_text(query, 1) else { throw BridgeError.invalid("Could not read deletion history.") }
            ids.append((sqlite3_column_int64(query, 0), String(cString: value)))
        }
    }
}
