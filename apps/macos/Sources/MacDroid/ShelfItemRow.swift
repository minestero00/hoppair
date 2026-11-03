import SwiftUI
import UniformTypeIdentifiers
import MacDroidCore

struct ShelfItemRow: View {
    let item: ShelfItem
    let accent: Color
    let onAction: () -> Void
    let onPin: () -> Void
    let onDetails: () -> Void
    @State private var copied = false

    private var symbol: String {
        if item.kind == "link" { return "link" }
        if item.kind != "file" { return "text.alignleft" }
        if item.mime?.hasPrefix("image/") == true { return "photo" }
        if item.mime == "application/pdf" { return "doc.richtext" }
        return "doc.text"
    }
    private var preview: String? {
        guard let text = item.text, text != item.title, !text.hasPrefix(item.title) else { return nil }
        if item.kind == "link", let url = URL(string: text) { return url.host ?? text }
        return text.replacingOccurrences(of: "\n", with: " ")
    }
    private var actionTitle: String {
        if copied { return "Copied" }
        return item.kind == "file" ? "Save…" : item.kind == "link" ? "Open" : "Copy"
    }
    private var actionSymbol: String {
        if copied { return "checkmark" }
        return item.kind == "file" ? "arrow.down" : item.kind == "link" ? "arrow.up.right" : "doc.on.doc"
    }
    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: symbol)
                .font(.system(size: 19, weight: .regular))
                .foregroundStyle(accent)
                .frame(width: 40, height: 44)
                .background(accent.opacity(0.07), in: RoundedRectangle(cornerRadius: 9))
                .accessibilityHidden(true)
            Button(action: onDetails) {
                VStack(alignment: .leading, spacing: 5) {
                    Text(item.title.replacingOccurrences(of: "\n", with: " "))
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(.primary).lineLimit(1)
                        .truncationMode(item.kind == "file" ? .middle : .tail)
                    if let preview {
                        Text(preview).font(.system(size: 13)).foregroundStyle(.secondary).lineLimit(1)
                    }
                    ShelfItemMetadata(item: item)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain).help("View item details")
            .accessibilityHint("Opens the full item")
            Button(action: onPin) {
                Image(systemName: item.pinned ? "pin.fill" : "pin")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(item.pinned ? accent : Color.secondary)
                    .frame(width: 30, height: 30)
            }
            .buttonStyle(.borderless)
            .help(item.pinned ? "Unpin item" : "Pin item")
            .accessibilityLabel(item.pinned ? "Unpin item" : "Pin item")
            .accessibilityValue(item.pinned ? "Pinned" : "Not pinned")
            Button {
                onAction()
                if item.kind == "text" { copied = true }
            } label: {
                Label(actionTitle, systemImage: actionSymbol)
                    .foregroundStyle(.primary)
                    .font(.system(size: 12, weight: .medium))
                    .frame(width: 70, height: 24)
            }
            .buttonStyle(.bordered).controlSize(.regular)
            .help(item.kind == "file" ? "Save this file to your Mac" : item.kind == "link" ? "Open this link in your browser" : "Copy note to clipboard")
            .task(id: copied) {
                guard copied else { return }
                do { try await Task.sleep(for: .seconds(2)); copied = false } catch { }
            }
        }
        .padding(.vertical, 11).padding(.horizontal, 8)
        .contextMenu {
            Button("View Details", action: onDetails)
            Button(item.kind == "file" ? "Save File…" : item.kind == "link" ? "Open Link" : "Copy Text", action: onAction)
            Divider()
            Button(item.pinned ? "Unpin Item" : "Pin Item", action: onPin)
        }
    }
}

struct ShelfItemMetadata: View {
    let item: ShelfItem
    private var kind: String {
        if item.kind == "link" { return "Link" }
        if item.kind != "file" { return "Note" }
        if let mime = item.mime, let type = UTType(mimeType: mime), let name = type.localizedDescription { return name.prefix(1).uppercased() + name.dropFirst() }
        return "File"
    }
    var body: some View {
        HStack(spacing: 6) {
            Text(kind)
            if item.kind == "file" {
                Text("·").accessibilityHidden(true)
                Text(ByteCountFormatter.string(fromByteCount: Int64(item.size), countStyle: .file))
            }
            Text("·").accessibilityHidden(true)
            Label(item.source, systemImage: item.source == "Mac" ? "laptopcomputer" : "smartphone")
            Text("·").accessibilityHidden(true)
            Text(Date(timeIntervalSince1970: item.createdAt), format: .dateTime.month(.abbreviated).day().hour().minute())
        }
        .font(.system(size: 12)).foregroundStyle(.secondary).lineLimit(1)
        .accessibilityElement(children: .combine)
    }
}

struct ShelfItemDetails: View {
    let item: ShelfItem
    let onAction: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var copied = false
    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 8) {
                    Text(item.kind == "text" ? "Note" : item.kind == "link" ? "Link" : "File")
                        .font(.title2.weight(.semibold))
                    ShelfItemMetadata(item: item)
                }
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.cancelAction)
            }
            Divider()
            ScrollView {
                Text(item.text ?? item.filename ?? item.title)
                    .font(.system(size: 15)).lineSpacing(5)
                    .frame(maxWidth: .infinity, alignment: .leading).textSelection(.enabled)
                    .padding(.vertical, 4)
            }.frame(minHeight: 160, maxHeight: 400)
            HStack {
                Spacer()
                Button(copied ? "Copied" : item.kind == "file" ? "Save File…" : item.kind == "link" ? "Open Link" : "Copy Text") {
                    onAction()
                    if item.kind == "text" { copied = true }
                }.keyboardShortcut(.defaultAction).controlSize(.large)
            }
        }.padding(24).frame(width: 530)
    }
}
