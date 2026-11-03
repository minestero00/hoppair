import SwiftUI
import UniformTypeIdentifiers
import HoppairCore

private let accent = HoppairPalette.accent

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
                    VStack(alignment: .leading) { Text("Hoppair").font(.title3.bold()); Text("Two devices. One flow.").font(.caption).foregroundStyle(HoppairPalette.secondaryText) }
                }.padding(.top, 20)
                VStack(spacing: 6) {
                    ForEach(sections, id: \.0) { name, symbol in
                        Button { section = name } label: {
                            HStack { Image(systemName: symbol).frame(width: 22); Text(name); Spacer(); if name == "All Items" { Text("\(model.items.count)").foregroundStyle(HoppairPalette.secondaryText) } }
                                .padding(10).frame(maxWidth: .infinity, alignment: .leading)

                        }.hoppairButton(.navigation, selected: section == name)
                            .accessibilityAddTraits(section == name ? .isSelected : [])
                    }
                }
                Spacer()
                VStack(alignment: .leading, spacing: 12) {
                    Label("My Devices", systemImage: "laptopcomputer").font(.caption.bold()).foregroundStyle(HoppairPalette.secondaryText)
                    HStack { Image(systemName: "desktopcomputer"); Text("This Mac"); Spacer(); Text(model.isScreenshotMode ? "Preview" : model.port > 0 || model.relayConnected ? "Ready" : "Preparing").font(.caption).foregroundStyle(HoppairPalette.secondaryText) }
                    TimelineView(.periodic(from: .now, by: 3)) { context in
                        let online = model.lastSeen.map { context.date.timeIntervalSince($0) < 12 } ?? false
                        HStack { Image(systemName: "smartphone"); Text("Android"); Spacer(); Text(online ? "Connected" : "Waiting").font(.caption).foregroundStyle(online ? accent : .secondary) }
                    }
                    Button("Connect Phone", systemImage: "link") { model.showPairing = true }.frame(maxWidth: .infinity)
                }.padding(14).background(.quaternary.opacity(0.4), in: RoundedRectangle(cornerRadius: 12))
                Text(model.isScreenshotMode ? "Sample content" : model.relayConnected ? "Connected across networks" : model.relayAddress.isEmpty ? "Direct connection on your local network" : "Connecting to your relay")
                    .font(.caption).foregroundStyle(HoppairPalette.secondaryText)
            }.padding(18).navigationSplitViewColumnWidth(min: 220, ideal: 240, max: 280)
        } detail: {
            VStack(alignment: .leading, spacing: 0) {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(section).font(.system(size: 25, weight: .semibold))
                        Text("\(filtered.count) items · From your phone to your Mac, and back.").font(.system(size: 12)).foregroundStyle(HoppairPalette.secondaryText)
                    }
                    Spacer()
                    Button { model.openCommands() } label: { HStack(spacing: 10) { Text("Commands"); Text("⌘K").foregroundStyle(HoppairPalette.secondaryText) }.font(.system(size: 12, weight: .medium)) }.hoppairButton()
                }.padding(.horizontal, 24).padding(.top, 22).padding(.bottom, 18)
                HStack(spacing: 10) {
                    action("Add File", symbol: "arrow.up.doc", action: model.chooseFiles)
                    action("Add Clipboard", symbol: "doc.on.clipboard", action: model.paste)
                    action("Take Photo", symbol: "camera", action: model.requestCamera)
                }.padding(.horizontal, 24).padding(.bottom, 14)
                HStack { Image(systemName: "magnifyingglass").foregroundStyle(HoppairPalette.secondaryText); TextField("Search the shelf", text: $search).textFieldStyle(.plain); if !search.isEmpty { Button("Clear") { search = "" }.font(.caption) } }
                    .padding(11).background(.quaternary.opacity(0.4), in: RoundedRectangle(cornerRadius: 10)).padding(.horizontal, 24)
                if model.cameraRequest != nil {
                    HStack { Label("Open Hoppair on your phone to confirm the photo request", systemImage: "camera"); Spacer(); Button("Cancel Request") { model.cameraRequest = nil } }
                        .font(.callout).padding(12).background(accent.opacity(0.10), in: RoundedRectangle(cornerRadius: 8)).padding(.horizontal, 24).padding(.top, 12)
                }
                if filtered.isEmpty {
                    VStack(spacing: 14) {
                        Image(systemName: search.isEmpty ? "tray" : "magnifyingglass").font(.system(size: 44, weight: .light)).foregroundStyle(accent)
                        Text(emptyTitle).font(.title2.bold())
                        Text(emptyMessage)
                            .multilineTextAlignment(.center).foregroundStyle(HoppairPalette.secondaryText)
                        if search.isEmpty && model.items.isEmpty { Button("Connect Phone") { model.showPairing = true }.hoppairButton(.primary) }
                    }.frame(maxWidth: .infinity, maxHeight: .infinity).padding(30)
                } else {
                    ShelfBrowser(items: filtered)
                        .padding(.horizontal, 24).padding(.top, 14).padding(.bottom, 18)
                }
                Divider()
                VStack(spacing: 10) {
                    HStack(alignment: .top, spacing: 12) {
                        TextField("Add text or a link to the shelf", text: $draft, axis: .vertical)
                            .textFieldStyle(.plain).lineLimit(1...4).focused($composerFocused).padding(8)
                        Button("Add", systemImage: "arrow.up") { sendDraft() }
                            .hoppairButton(.primary).disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                    HStack { Text(model.message).lineLimit(2); Spacer(); Text("Up to 8 MiB per file") }.font(.caption).foregroundStyle(HoppairPalette.secondaryText)
                }.padding(.horizontal, 24).padding(.vertical, 18)
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
        .buttonStyle(HoppairButtonStyle())
        .tint(accent).frame(minWidth: 800, minHeight: 600)
        .sheet(isPresented: $model.showPairing) { PairingView().environmentObject(model) }
        .sheet(isPresented: $model.showCommands) { commands }
        .alert("Could Not Complete the Action", isPresented: Binding(get: { model.error != nil }, set: { if !$0 { model.error = nil } })) {
            Button("OK") { model.error = nil }
        } message: { Text(model.error ?? "") }
    }
    private var emptyTitle: String {
        if !search.isEmpty { return "No matching items" }
        switch section {
        case "Pinned": return "No pinned items"
        case "Files": return "No files yet"
        case "Text & Links": return "No notes or links yet"
        default: return "Add your first item"
        }
    }
    private var emptyMessage: String {
        if !search.isEmpty { return "Try another search or category." }
        switch section {
        case "Pinned": return "Pin an item to keep it within reach here."
        case "Files": return "Add a file or share one from your phone."
        case "Text & Links": return "Write a note below, or share a link from your phone."
        default: return "Drop a file here or write a note below.\nConnect your phone to share items between devices."
        }
    }
    private func action(_ title: String, symbol: String, action: @escaping () -> Void) -> some View {
        Button(action: action) { Label(title, systemImage: symbol).font(.system(size: 12, weight: .medium)).frame(maxWidth: .infinity) }.hoppairButton()
    }
    private var commands: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack { Text("What would you like to do?").font(.title2.bold()); Spacer(); Button("Close") { model.showCommands = false }.keyboardShortcut(.cancelAction) }
            Text("Anywhere: ⇧⌘Space · In the app: ⌘K").font(.callout).foregroundStyle(HoppairPalette.secondaryText)
            command("Add Clipboard", symbol: "doc.on.clipboard") { model.paste() }
            command("Add File…", symbol: "arrow.up.doc") { model.chooseFiles() }
            command("Take a Photo", symbol: "camera") { model.requestCamera() }
            command("Write a Note", symbol: "square.and.pencil") { composerFocused = true }
            command("Connect Phone", symbol: "link") { DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) { model.showPairing = true } }
        }.padding(24).frame(width: 460)
    }
    private func command(_ title: String, symbol: String, action: @escaping () -> Void) -> some View {
        Button { model.showCommands = false; action() } label: { Label(title, systemImage: symbol).frame(maxWidth: .infinity, alignment: .leading).padding(10) }.hoppairButton()
    }
    private func sendDraft() { model.addText(draft); if model.error == nil { draft = "" } }
}
