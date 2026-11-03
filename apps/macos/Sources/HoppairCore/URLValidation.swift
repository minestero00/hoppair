import Foundation
import Darwin

/// The shared link check used for local classification and received shelf items.
/// It accepts Unicode paths, while requiring an ASCII DNS name or IP address so
/// URLs survive both Foundation and Android's strict URI parser unchanged.
public enum WebLink {
    public static func isValid(_ text: String) -> Bool {
        guard let components = URLValidation.components(text),
              ["http", "https"].contains(components.scheme),
              components.user == nil, components.password == nil,
              let host = components.host, URLValidation.validHost(host),
              components.percentEncodedHost == host,
              components.port.map({ (1...65535).contains($0) }) ?? true else { return false }
        return true
    }
}

enum URLValidation {
    static func components(_ text: String) -> URLComponents? {
        guard !text.unicodeScalars.contains(where: {
            CharacterSet.whitespacesAndNewlines.contains($0) || CharacterSet.controlCharacters.contains($0)
        }) else { return nil }
        // Encode Unicode only. Foundation's automatic escaping must not repair
        // malformed ASCII URI syntax that Android would reject.
        if let separator = text.range(of: "://") {
            let authority = text[separator.upperBound...].prefix { !"/?#".contains($0) }
            if authority.hasSuffix(":") { return nil }
        }
        let ascii = text.unicodeScalars.map { scalar -> String in
            if scalar.isASCII { return String(scalar) }
            return String(scalar).utf8.map { String(format: "%%%02X", $0) }.joined()
        }.joined()
        guard let url = URL(string: ascii, encodingInvalidCharacters: false) else { return nil }
        return URLComponents(url: url, resolvingAgainstBaseURL: false)
    }

    static func validHost(_ host: String) -> Bool {
        guard !host.isEmpty, host.utf8.count <= 253, host.unicodeScalars.allSatisfy(\.isASCII) else { return false }
        if host.hasPrefix("["), host.hasSuffix("]") {
            var address = in6_addr()
            return String(host.dropFirst().dropLast()).withCString { inet_pton(AF_INET6, $0, &address) == 1 }
        }
        let labels = host.split(separator: ".", omittingEmptySubsequences: false)
        if labels.count == 4, labels.allSatisfy({ !$0.isEmpty && $0.allSatisfy(\.isNumber) }) {
            return labels.allSatisfy { Int($0).map { (0...255).contains($0) } ?? false }
        }
        let domain = host.hasSuffix(".") ? Array(labels.dropLast()) : labels
        guard !domain.isEmpty, domain.allSatisfy({ label in
            !label.isEmpty && label.utf8.count <= 63 &&
            label.first!.isLetterOrNumber && label.last!.isLetterOrNumber &&
            label.allSatisfy { $0.isLetterOrNumber || $0 == "-" }
        }) else { return false }
        return domain.count == 1 || domain.last!.first!.isLetter
    }
}

private extension Character {
    var isLetterOrNumber: Bool { isLetter || isNumber }
}
