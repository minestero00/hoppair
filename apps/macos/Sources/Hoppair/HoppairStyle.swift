import SwiftUI

// Shared native surfaces keep hover, press, focus and disabled states consistent.
enum HoppairPalette {
    // Blue is reserved for links and system interaction; everyday surfaces stay neutral.
    static let accent = Color(nsColor: .linkColor)
    static let primaryFill = adaptive(light: shade(0.13), dark: shade(0.88))
    static let primaryForeground = adaptive(light: .white, dark: shade(0.12))
    static let primaryText = adaptive(light: shade(0.12), dark: shade(0.94))
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
            ? NSColor(srgbRed: 0.75, green: 0.75, blue: 0.75, alpha: 1)
            : NSColor(srgbRed: 0.40, green: 0.40, blue: 0.40, alpha: 1)
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
            return active ? HoppairPalette.hoverFill : (kind == .icon ? .clear : Color.primary.opacity(0.035))
        }
        var body: some View {
            configuration.label
                .symbolRenderingMode(.monochrome)
                .foregroundStyle(kind == .primary ? HoppairPalette.primaryForeground : HoppairPalette.primaryText)
                .padding(.horizontal, kind == .row ? 0 : kind == .icon ? 7 : 11)
                .padding(.vertical, kind == .row ? 0 : kind == .icon ? 7 : 8)
                .frame(minWidth: kind == .icon ? 32 : nil, minHeight: kind == .row ? nil : 32)
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
    func hoppairGlass(cornerRadius: CGFloat = 16) -> some View {
        modifier(HoppairGlassSurface(cornerRadius: cornerRadius))
    }
}

private struct HoppairGlassSurface: ViewModifier {
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.hoppairOpaquePreview) private var opaquePreview
    @Environment(\.colorSchemeContrast) private var contrast
    let cornerRadius: CGFloat

    @ViewBuilder func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
        if reduceTransparency || opaquePreview || contrast == .increased {
            content.background(HoppairPalette.surface, in: shape)
        } else if #available(macOS 26.0, *) {
            content.glassEffect(.regular.tint(HoppairPalette.surface.opacity(0.55)), in: shape)
        } else {
            content.background(.regularMaterial, in: shape)
        }
    }
}

private struct HoppairOpaquePreviewKey: EnvironmentKey {
    static let defaultValue = false
}

extension EnvironmentValues {
    var hoppairOpaquePreview: Bool {
        get { self[HoppairOpaquePreviewKey.self] }
        set { self[HoppairOpaquePreviewKey.self] = newValue }
    }
}

struct HoppairCanvas: View {
    var body: some View {
        LinearGradient(colors: [Color(nsColor: .windowBackgroundColor), HoppairPalette.surface],
                       startPoint: .topLeading, endPoint: .bottomTrailing)
    }
}

/// The complete disclosure row is a button, including the space after its label.
struct HoppairDisclosureStyle: DisclosureGroupStyle {
    func makeBody(configuration: Configuration) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Button { configuration.isExpanded.toggle() } label: {
                HStack(spacing: 10) {
                    Image(systemName: configuration.isExpanded ? "chevron.down" : "chevron.right")
                        .font(.caption.weight(.semibold)).frame(width: 12).accessibilityHidden(true)
                    configuration.label
                    Spacer(minLength: 0)
                }.frame(maxWidth: .infinity, minHeight: 40, alignment: .leading).contentShape(Rectangle())
            }
            .hoppairButton(.navigation)
            .accessibilityValue(configuration.isExpanded ? "Expanded" : "Collapsed")
            if configuration.isExpanded { configuration.content }
        }
    }
}
