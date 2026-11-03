import SwiftUI

// Shared native surfaces keep hover, press, focus and disabled states consistent.
enum HoppairPalette {
    static let accent = Color(nsColor: NSColor(name: nil) { appearance in
        if appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua {
            return NSColor(srgbRed: 0.50, green: 0.84, blue: 0.78, alpha: 1)
        }
        return NSColor(srgbRed: 0.04, green: 0.48, blue: 0.43, alpha: 1)
    })
    static let secondaryText = Color(nsColor: NSColor(name: nil) { appearance in
        appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
            ? NSColor(srgbRed: 0.70, green: 0.73, blue: 0.77, alpha: 1)
            : NSColor(srgbRed: 0.38, green: 0.41, blue: 0.46, alpha: 1)
    })
    static let surface = Color(nsColor: .controlBackgroundColor)
    static let border = Color(nsColor: .separatorColor).opacity(0.45)
}

struct HoppairButtonStyle: ButtonStyle {
    enum Kind { case secondary, primary, icon, navigation, row }
    var kind: Kind = .secondary
    var selected = false
    func makeBody(configuration: Configuration) -> some View {
        Surface(configuration: configuration, kind: kind, selected: selected)
    }
    private struct Surface: View {
        let configuration: ButtonStyle.Configuration
        let kind: Kind
        let selected: Bool
        @State private var hovered = false
        @Environment(\.isEnabled) private var enabled
        @Environment(\.accessibilityReduceMotion) private var reduceMotion
        private var active: Bool { enabled && (hovered || configuration.isPressed) }
        private var background: Color {
            if kind == .primary { return HoppairPalette.accent.opacity(active ? 0.86 : 1) }
            if kind == .navigation || kind == .row {
                return selected ? HoppairPalette.accent.opacity(active ? 0.20 : 0.12) : Color.primary.opacity(active ? 0.065 : 0)
            }
            return active ? HoppairPalette.accent.opacity(0.10) : (kind == .icon ? .clear : HoppairPalette.surface)
        }
        var body: some View {
            configuration.label
                .foregroundStyle(kind == .primary ? Color(nsColor: .windowBackgroundColor) : selected || (active && kind == .icon) ? HoppairPalette.accent : Color.primary)
                .padding(.horizontal, kind == .row ? 0 : kind == .icon ? 7 : 11)
                .padding(.vertical, kind == .row ? 0 : kind == .icon ? 7 : 8)
                .background(background, in: RoundedRectangle(cornerRadius: 8))
                .opacity(enabled ? configuration.isPressed ? 0.78 : 1 : 0.38)
                .contentShape(RoundedRectangle(cornerRadius: 8))
                .contentShape(.focusEffect, RoundedRectangle(cornerRadius: 8))
                .onHover { hovered = $0 }
                .animation(reduceMotion ? nil : .easeOut(duration: 0.12), value: hovered)
                .animation(reduceMotion ? nil : .easeOut(duration: 0.08), value: configuration.isPressed)
        }
    }
}
extension View {
    func hoppairButton(_ kind: HoppairButtonStyle.Kind = .secondary, selected: Bool = false) -> some View {
        buttonStyle(HoppairButtonStyle(kind: kind, selected: selected))
    }
}
