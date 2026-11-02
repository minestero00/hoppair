import SwiftUI
import UniformTypeIdentifiers
import MacDroidCore

private let accent = Color(nsColor: NSColor(name: nil) { appearance in
    if appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua {
        return NSColor(srgbRed: 0.50, green: 0.84, blue: 0.78, alpha: 1)
    }
    return NSColor(srgbRed: 0.04, green: 0.48, blue: 0.43, alpha: 1)
})

struct ShelfView: View {
    @EnvironmentObject private var model: AppModel
    @State private var search = ""
    @State private var section = "All Items"
    @State private var draft = ""
    @State private var targeting = false
    @FocusState private var composerFocused: Bool
    private let sections = [("All Items", "square.grid.2x2"), ("Pinned", "pin"), ("Files", "doc"), ("Text & Links", "text.alignleft")]
    var filtered: [ShelfItem] {
        model.items.filter { item in
            (section != "Pinned" || item.pinned) &&
            (section != "Files" || item.kind == "file") &&
            (section != "Text & Links" || item.kind != "file") &&
            (search.isEmpty || "\(item.title) \(item.text ?? "")".localizedCaseInsensitiveContains(search))
        }.sorted { a, b in a.pinned != b.pinned ? a.pinned : a.createdAt > b.createdAt }
    }
    var body: some View {
        NavigationSplitView {
            VStack(alignment: .leading, spacing: 24) {
                HStack(spacing: 10) {
                    Image(nsImage: (NSImage(named: "BrandIcon") ?? NSApplication.shared.applicationIconImage)).resizable().scaledToFit().frame(width: 38, height: 38).clipShape(RoundedRectangle(cornerRadius: 9)).accessibilityHidden(true)
                    VStack(alignment: .leading) { Text("MacDroid").font(.title3.bold()); Text("Two devices. One flow.").font(.caption).foregroundStyle(.secondary) }
                }.padding(.top, 20)
                VStack(spacing: 6) {
                    ForEach(sections, id: \.0) { name, symbol in
                        Button { section = name } label: {
                            HStack { Image(systemName: symbol).frame(width: 22); Text(name); Spacer(); if name == "All Items" { Text("\(model.items.count)").foregroundStyle(.secondary) } }
                                .padding(10).frame(maxWidth: .infinity, alignment: .leading)
                                .background(section == name ? accent.opacity(0.13) : .clear, in: RoundedRectangle(cornerRadius: 8))
                        }.buttonStyle(.plain).foregroundStyle(section == name ? accent : .primary)
                    }
                }
                Spacer()
                VStack(alignment: .leading, spacing: 12) {
                    Label("My Devices", systemImage: "laptopcomputer").font(.caption.bold()).foregroundStyle(.secondary)
                    HStack { Image(systemName: "desktopcomputer"); Text("This Mac"); Spacer(); Text(model.isScreenshotMode ? "Preview" : model.port > 0 || model.relayConnected ? "Ready" : "Preparing").font(.caption).foregroundStyle(.secondary) }
                    TimelineView(.periodic(from: .now, by: 3)) { context in
                        let online = model.lastSeen.map { context.date.timeIntervalSince($0) < 12 } ?? false
                        HStack { Image(systemName: "smartphone"); Text("Android"); Spacer(); Text(online ? "Connected" : "Waiting").font(.caption).foregroundStyle(online ? accent : .secondary) }
                    }
                    Button("Connect Phone", systemImage: "link") { model.showPairing = true }.frame(maxWidth: .infinity)
                }.padding(14).background(.quaternary.opacity(0.4), in: RoundedRectangle(cornerRadius: 12))
                Text(model.isScreenshotMode ? "Sample content" : model.relayConnected ? "Connected across networks" : model.relayAddress.isEmpty ? "Direct connection on your local network" : "Connecting to your relay")
                    .font(.caption).foregroundStyle(.secondary)
            }.padding(18).navigationSplitViewColumnWidth(min: 220, ideal: 240, max: 280)
        } detail: {
            VStack(alignment: .leading, spacing: 0) {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(section).font(.largeTitle.bold())
                        Text("From your phone to your Mac, and back.").foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button { model.openCommands() } label: { Label("Commands  ⌘K", systemImage: "command") }.controlSize(.large)
                }.padding(28)
                HStack(spacing: 10) {
                    action("Add File", symbol: "arrow.up.doc", action: model.chooseFiles)
                    action("Add Clipboard", symbol: "doc.on.clipboard", action: model.paste)
                    action("Take Photo", symbol: "camera", action: model.requestCamera)
                }.padding(.horizontal, 28).padding(.bottom, 22)
                HStack { Image(systemName: "magnifyingglass").foregroundStyle(.secondary); TextField("Search the shelf", text: $search).textFieldStyle(.plain); if !search.isEmpty { Button("Clear") { search = "" }.font(.caption) } }
                    .padding(11).background(.quaternary.opacity(0.4), in: RoundedRectangle(cornerRadius: 10)).padding(.horizontal, 28)
                if model.cameraRequest != nil {
                    HStack { Label("Open MacDroid on your phone to confirm the photo request", systemImage: "camera"); Spacer(); Button("Cancel Request") { model.cameraRequest = nil } }
                        .font(.callout).padding(12).background(accent.opacity(0.10), in: RoundedRectangle(cornerRadius: 8)).padding(.horizontal, 28).padding(.top, 12)
                }
                if filtered.isEmpty {
                    VStack(spacing: 14) {
                        Image(systemName: search.isEmpty ? "tray" : "magnifyingglass").font(.system(size: 44, weight: .light)).foregroundStyle(accent)
                        Text(search.isEmpty ? "Add your first item" : "No matching items").font(.title2.bold())
                        Text(search.isEmpty ? "Drop a file here or write a note below.\nConnect your phone to share items between devices." : "Try another search or category.")
                            .multilineTextAlignment(.center).foregroundStyle(.secondary)
                        if search.isEmpty { Button("Connect Phone") { model.showPairing = true }.buttonStyle(.borderedProminent).tint(accent) }
                    }.frame(maxWidth: .infinity, maxHeight: .infinity).padding(30)
                } else {
                    ScrollView {
                        LazyVStack(spacing: 10) {
                            ForEach(filtered) { item in itemRow(item) }
                        }.padding(28).padding(.top, 4)
                    }
                }
                Divider()
                VStack(spacing: 10) {
                    HStack(alignment: .top, spacing: 12) {
                        TextField("Add text or a link to the shelf", text: $draft, axis: .vertical)
                            .textFieldStyle(.plain).lineLimit(1...4).focused($composerFocused).padding(8)
                        Button("Add", systemImage: "arrow.up") { sendDraft() }
                            .buttonStyle(.borderedProminent).tint(accent).disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                    HStack { Text(model.message).lineLimit(2); Spacer(); Text("Up to 8 MiB per file") }.font(.caption).foregroundStyle(.secondary)
                }.padding(20)
            }
            .background(Color(nsColor: .windowBackgroundColor))
            .overlay { if targeting { RoundedRectangle(cornerRadius: 16).stroke(accent, style: StrokeStyle(lineWidth: 3, dash: [8])).padding(10).allowsHitTesting(false) } }
            .onDrop(of: [.fileURL], isTargeted: $targeting) { providers in
                for provider in providers {
                    _ = provider.loadObject(ofClass: URL.self) { url, _ in
                        guard let url else { return }
                        Task { @MainActor in model.importFile(url) }
                    }
                }
                return !providers.isEmpty
            }
        }
        .tint(accent).frame(minWidth: 800, minHeight: 600)
        .sheet(isPresented: $model.showPairing) { PairingView().environmentObject(model) }
        .sheet(isPresented: $model.showCommands) { commands }
        .alert("Could Not Complete the Action", isPresented: Binding(get: { model.error != nil }, set: { if !$0 { model.error = nil } })) {
            Button("OK") { model.error = nil }
        } message: { Text(model.error ?? "") }
    }
    private func action(_ title: String, symbol: String, action: @escaping () -> Void) -> some View {
        Button(action: action) { Label(title, systemImage: symbol).padding(.vertical, 7).frame(maxWidth: .infinity) }.buttonStyle(.bordered)
    }
    private func itemRow(_ item: ShelfItem) -> some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: item.kind == "file" ? "doc" : item.kind == "link" ? "link" : "text.alignleft")
                .font(.title3).foregroundStyle(accent).frame(width: 42, height: 42).background(accent.opacity(0.09), in: RoundedRectangle(cornerRadius: 10)).accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 7) {
                HStack { Text(item.title).font(.headline).lineLimit(2).textSelection(.enabled); if item.pinned { Image(systemName: "pin.fill").font(.caption).accessibilityLabel("Pinned") } }
                if let text = item.text, text != item.title { Text(text).font(.callout).foregroundStyle(.secondary).lineLimit(3).textSelection(.enabled) }
                HStack(spacing: 8) {
                    Label(item.source, systemImage: item.source == "Mac" ? "laptopcomputer" : "smartphone")
                    Text("·")
                    Text(Date(timeIntervalSince1970: item.createdAt), style: .relative)
                    if item.kind == "file" { Text("·"); Text(ByteCountFormatter.string(fromByteCount: Int64(item.size), countStyle: .file)) }
                }.font(.caption).foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
            VStack(alignment: .trailing, spacing: 8) {
                Button { model.pin(item) } label: { Image(systemName: item.pinned ? "pin.slash" : "pin") }.buttonStyle(.borderless).accessibilityLabel(item.pinned ? "Unpin" : "Pin Item")
                Button(item.kind == "file" ? "Save…" : item.kind == "link" ? "Open" : "Copy") { model.open(item) }.controlSize(.small)
            }
        }.padding(16).background(Color(nsColor: .controlBackgroundColor), in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(.separator.opacity(0.35)))
    }
    private var commands: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack { Text("What would you like to do?").font(.title2.bold()); Spacer(); Button("Close") { model.showCommands = false }.keyboardShortcut(.cancelAction) }
            Text("Anywhere: ⇧⌘Space · In the app: ⌘K").font(.callout).foregroundStyle(.secondary)
            command("Add Clipboard", symbol: "doc.on.clipboard") { model.paste() }
            command("Add File…", symbol: "arrow.up.doc") { model.chooseFiles() }
            command("Take a Photo", symbol: "camera") { model.requestCamera() }
            command("Write a Note", symbol: "square.and.pencil") { composerFocused = true }
            command("Connect Phone", symbol: "link") { DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) { model.showPairing = true } }
        }.padding(24).frame(width: 460)
    }
    private func command(_ title: String, symbol: String, action: @escaping () -> Void) -> some View {
        Button { model.showCommands = false; action() } label: { Label(title, systemImage: symbol).frame(maxWidth: .infinity, alignment: .leading).padding(10) }.buttonStyle(.bordered)
    }
    private func sendDraft() { model.addText(draft); if model.error == nil { draft = "" } }
}
