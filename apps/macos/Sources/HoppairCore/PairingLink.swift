import Foundation

public enum PairingLink {
    public static let page = "https://hoppair-connect.macdroid-relay.workers.dev/connect/"
    /// Fragments stay on the device: the HTTP server never receives the pairing credential.
    public static func webURL(for directLink: String) -> String {
        guard !directLink.isEmpty else { return "" }
        let payload = Data(directLink.utf8).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
        return page + "#pair=" + payload
    }
}
