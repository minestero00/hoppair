import SwiftUI
import ImageIO
import HoppairCore

private actor ThumbnailLoader {
    static let shared = ThumbnailLoader()
    private let cache = NSCache<NSString, CGImage>()
    init() { cache.totalCostLimit = 20 * 1024 * 1024; cache.countLimit = 100 }
    func load(_ url: URL, pixels: Int) async -> CGImage? {
        let key = "\(pixels):\(url.absoluteString)" as NSString
        if let image = cache.object(forKey: key) { return image }
        let image = await Task.detached(priority: .utility) {
            guard let source = CGImageSourceCreateWithURL(url as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary) else { return nil as CGImage? }
            return CGImageSourceCreateThumbnailAtIndex(source, 0, [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceThumbnailMaxPixelSize: pixels,
                kCGImageSourceShouldCacheImmediately: true
            ] as CFDictionary)
        }.value
        if let image { cache.setObject(image, forKey: key, cost: image.bytesPerRow * image.height) }
        return image
    }
}

struct ItemThumbnail: View {
    let item: ShelfItem
    let url: URL?
    var size: CGFloat = 42
    var fitted = false
    @State private var bitmap: CGImage?
    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: fitted ? 12 : 9).fill(HoppairPalette.iconSurface)
            if let bitmap {
                Image(decorative: bitmap, scale: 1).resizable().aspectRatio(contentMode: fitted ? .fit : .fill)
            } else {
                Image(systemName: item.kind == "link" ? "link" : item.kind == "text" ? "text.alignleft" : item.mime?.hasPrefix("image/") == true ? "photo" : "doc.text")
                    .font(.system(size: fitted ? 42 : 18, weight: .regular)).foregroundStyle(item.kind == "link" ? HoppairPalette.accent : HoppairPalette.secondaryText)
            }
        }
        .frame(width: fitted ? nil : size, height: fitted ? nil : size)
        .clipShape(RoundedRectangle(cornerRadius: fitted ? 12 : 9))
        .accessibilityHidden(true)
        .task(id: "\(url?.absoluteString ?? ""):\(fitted)") {
            bitmap = nil
            guard item.mime?.hasPrefix("image/") == true, let url else { return }
            let result = await ThumbnailLoader.shared.load(url, pixels: fitted ? 1000 : 128)
            guard !Task.isCancelled else { return }
            bitmap = result
        }
    }
}
