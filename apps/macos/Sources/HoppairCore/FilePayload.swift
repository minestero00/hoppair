import Foundation
import Darwin

/// Read the bytes actually present, rather than trusting a provider's file-size metadata.
public enum FilePayload {
    public static func read(_ url: URL) throws -> Data {
        guard url.isFileURL else { throw BridgeError.invalid("Choose a local file.") }
        let descriptor = Darwin.open(url.path, O_RDONLY | O_NONBLOCK | O_CLOEXEC)
        guard descriptor >= 0 else { throw CocoaError(.fileReadUnknown) }
        let file = FileHandle(fileDescriptor: descriptor, closeOnDealloc: true)
        defer { try? file.close() }
        var info = stat()
        guard fstat(descriptor, &info) == 0, info.st_mode & S_IFMT == S_IFREG else {
            throw BridgeError.invalid("Choose a regular file, rather than a folder or stream.")
        }
        guard info.st_size <= Wire.maxFileSize else { throw BridgeError.invalid("Files must be 8 MiB or smaller.") }
        var content = Data()
        while let chunk = try file.read(upToCount: min(64 * 1024, Wire.maxFileSize + 1 - content.count)), !chunk.isEmpty {
            content.append(chunk)
            guard content.count <= Wire.maxFileSize else { throw BridgeError.invalid("Files must be 8 MiB or smaller.") }
        }
        return content
    }
}
