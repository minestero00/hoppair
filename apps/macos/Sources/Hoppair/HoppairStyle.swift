import SwiftUI

// Shared native surfaces keep hover, press, focus and disabled states consistent.
enum HoppairPalette {
    // Blue is reserved for links and system interaction; everyday surfaces stay neutral.
    static let accent = Color(nsColor: .linkColor)
    static let primaryFill = adaptive(light: shade(0.13), dark: shade(0.88))
    static let primaryForeground = adaptive(light: .white, dark: shade(0.12))
    static let iconSurface = Color.primary.opacity(0.045)
    static let hoverFill = Color.primary.opacity(0.055)
    static func selectionFill(focused: Bool) -> Color { Color.primary.opacity(focused ? 0.085 : 0.05) }
    private static func shade(_ level: CGFloat) -> NSColor {
        NSColor(srgbRed: level, green: level, blue: level, alpha: 1)
    }
    private static func adaptive(light: NSColor, dark: NSColor) -> Color {
        Color(nsColor: NSColor(name: nil) { appearance in
            appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua ? dark : light
        })
    }
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
            if kind == .primary { return HoppairPalette.primaryFill.opacity(active ? 0.86 : 1) }
            // The row container owns its hover fill so the action and content share one surface.
            if kind == .row { return .clear }
            if kind == .navigation {
                return selected ? Color.primary.opacity(active ? 0.11 : 0.075) : Color.primary.opacity(active ? 0.055 : 0)
            }
            return active ? HoppairPalette.hoverFill : (kind == .icon ? .clear : HoppairPalette.surface)
        }
        var body: some View {
            configuration.label
                .foregroundStyle(kind == .primary ? HoppairPalette.primaryForeground : Color.primary)
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
