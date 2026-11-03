import SwiftUI
import UniformTypeIdentifiers
import HoppairCore

extension ShelfItem {
    var typeLabel: String {
        if kind == "link" { return "Link" }
        if kind != "file" { return "Note" }
        if let mime, let type = UTType(mimeType: mime), let name = type.localizedDescription { return name.prefix(1).uppercased() + name.dropFirst() }
        return "File"
    }
    var primaryTitle: String { kind == "file" ? "Save File…" : kind == "link" ? "Open Link" : "Copy Note" }
    var primarySymbol: String { kind == "file" ? "arrow.down.to.line" : kind == "link" ? "arrow.up.right" : "doc.on.doc" }
    var rowSubtitle: String {
        if kind == "file" { return typeLabel + " · " + ByteCountFormatter.string(fromByteCount: Int64(size), countStyle: .file) }
        if kind == "link", let text, let host = URL(string: text)?.host { return host }
        if let text, text != title, !text.hasPrefix(title) { return text.replacingOccurrences(of: "\n", with: " ") }
        return "Note from " + source
    }
}

struct ShelfItemRow: View {
    let item: ShelfItem
    let url: URL?
    let selected: Bool
    let focused: Bool
    let copied: Bool
    let onSelect: () -> Void
    let onAction: () -> Void
    let onPin: () -> Void
    let onDetails: () -> Void
    let onDelete: () -> Void
    @State private var hovered = false
    var body: some View {
        HStack(spacing: 6) {
            Button(action: onSelect) {
                HStack(spacing: 11) {
                    ItemThumbnail(item: item, url: url)
                    VStack(alignment: .leading, spacing: 5) {
                        HStack(spacing: 5) {
                            Text(item.title.replacingOccurrences(of: "\n", with: " "))
                                .font(.system(size: 13, weight: .semibold)).lineLimit(1)
                                .truncationMode(item.kind == "file" ? .middle : .tail)
                            if item.pinned { Image(systemName: "pin.fill").font(.system(size: 9)).foregroundStyle(HoppairPalette.secondaryText).accessibilityLabel("Pinned") }
                        }
                        Text(item.rowSubtitle).font(.system(size: 12)).foregroundStyle(HoppairPalette.secondaryText).lineLimit(1)
                    }.frame(maxWidth: .infinity, alignment: .leading)
                }.padding(.vertical, 9).padding(.leading, 10).padding(.trailing, 4)
                    .contentShape(Rectangle())
            }.hoppairButton(.row)
                .accessibilityAddTraits(selected ? .isSelected : [])
                .accessibilityHint("Select to preview")
            Button(action: onAction) {
                Image(systemName: copied ? "checkmark" : item.primarySymbol)
                    .font(.system(size: 13, weight: .medium)).frame(width: 16, height: 16)
                    .foregroundStyle(copied ? HoppairPalette.accent : HoppairPalette.secondaryText)
            }.hoppairButton(.icon).padding(.trailing, 7)
                .help(copied ? "Copied" : item.primaryTitle).accessibilityLabel(copied ? "Copied" : item.primaryTitle)
        }
        .background(selected ? HoppairPalette.selectionFill(focused: focused) : Color.primary.opacity(hovered ? 0.035 : 0), in: RoundedRectangle(cornerRadius: 10))
        .overlay(alignment: .leading) {
            if focused {
                Capsule().fill(Color.primary.opacity(0.65)).frame(width: 3, height: 24)
                    .padding(.leading, 3).allowsHitTesting(false).accessibilityHidden(true)
            }
        }
        .onHover { hovered = $0 }
        .contextMenu {
            Button(item.primaryTitle, action: onAction)
            Button("Open Preview", action: onDetails)
            Divider()
            Button(item.pinned ? "Unpin Item" : "Pin Item", action: onPin)
            Divider()
            Button("Delete…", role: .destructive, action: onDelete)
        }
    }
}

struct ShelfItemPreview: View {
    let item: ShelfItem
    let url: URL?
    let copied: Bool
    let expanded: Bool
    let onAction: () -> Void
    let onPin: () -> Void
    let onExpand: (() -> Void)?
    let onDelete: () -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Text("PREVIEW").font(.system(size: 10, weight: .semibold)).tracking(1).foregroundStyle(HoppairPalette.secondaryText)
                Spacer()
                Button(action: onDelete) { Image(systemName: "trash").frame(width: 15, height: 15) }
                    .hoppairButton(.icon).help("Delete from shared shelf").accessibilityLabel("Delete from shared shelf")
                Button(action: onPin) { Image(systemName: item.pinned ? "pin.fill" : "pin").frame(width: 15, height: 15) }
                    .hoppairButton(.icon).help(item.pinned ? "Unpin item" : "Pin item")
                    .accessibilityLabel(item.pinned ? "Unpin item" : "Pin item")
            }
            if item.kind == "file" {
                ItemThumbnail(item: item, url: url, fitted: true)
                    .frame(minHeight: 70, idealHeight: expanded ? 300 : 150, maxHeight: expanded ? 380 : 200)
                    .accessibilityElement(children: .ignore).accessibilityLabel("File: " + (item.filename ?? item.title))
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        if item.kind == "link" {
                            Label(URL(string: item.text ?? "")?.host ?? "Link", systemImage: "link")
                                .font(.system(size: 12, weight: .medium)).foregroundStyle(HoppairPalette.accent)
                        }
                        Text(item.text ?? item.title).font(.system(size: 14)).lineSpacing(5)
                            .textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading)
                    }.padding(14)
                }
                .frame(minHeight: 70, maxHeight: .infinity)
                .background(Color.primary.opacity(0.035), in: RoundedRectangle(cornerRadius: 10))
            }
            VStack(alignment: .leading, spacing: 10) {
                Text(item.kind == "file" ? (item.filename ?? item.title) : item.kind == "link" ? "Link" : "Note")
                    .font(.system(size: 13, weight: .semibold)).lineLimit(2).textSelection(.enabled)
                Grid(alignment: .leading, horizontalSpacing: 16, verticalSpacing: 6) {
                    GridRow { Text("From").foregroundStyle(HoppairPalette.secondaryText); Label(item.source, systemImage: item.source == "Mac" ? "laptopcomputer" : "smartphone") }
                    GridRow { Text("Added").foregroundStyle(HoppairPalette.secondaryText); Text(Date(timeIntervalSince1970: item.createdAt), format: .dateTime.month(.abbreviated).day().hour().minute()) }
                    if item.kind == "file" {
                        GridRow { Text("Type").foregroundStyle(HoppairPalette.secondaryText); Text(item.typeLabel) }
                        GridRow { Text("Size").foregroundStyle(HoppairPalette.secondaryText); Text(ByteCountFormatter.string(fromByteCount: Int64(item.size), countStyle: .file)) }
                    }
                }.font(.system(size: 11)).textSelection(.enabled)
            }
            HStack(spacing: 8) {
                Button(action: onAction) {
                    Label(copied ? "Copied" : item.primaryTitle, systemImage: copied ? "checkmark" : item.primarySymbol)
                        .font(.system(size: 12, weight: .semibold)).frame(maxWidth: .infinity)
                }.hoppairButton(.primary)
                if let onExpand {
                    Button(action: onExpand) { Image(systemName: "arrow.up.left.and.arrow.down.right").frame(width: 15, height: 15) }
                        .hoppairButton().help("Item details · Space").accessibilityLabel("Item details")
                }
            }
        }.padding(expanded ? 24 : 18)
    }
}
