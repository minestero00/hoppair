import SwiftUI
import HoppairCore

struct ShelfBrowser: View {
    @EnvironmentObject private var model: AppModel
    let items: [ShelfItem]
    @State private var selectedID: String?
    @State private var detail: ShelfItem?
    @State private var copiedID: String?
    @FocusState private var listFocused: Bool
    private var selected: ShelfItem? { items.first(where: { $0.id == selectedID }) ?? items.first }
    var body: some View {
        GeometryReader { geometry in
            let hasPreview = geometry.size.width >= 620 && geometry.size.height >= 340
            HStack(spacing: 0) {
                ScrollViewReader { scroll in
                    List {
                        ForEach(items) { item in
                            ShelfItemRow(item: item, url: model.previewURL(for: item), selected: item.id == selected?.id, focused: listFocused && item.id == selected?.id, copied: copiedID == item.id,
                                onSelect: { selectedID = item.id; listFocused = true; if !hasPreview { detail = item } },
                                onAction: { perform(item) }, onPin: { model.pin(item) }, onDetails: { detail = item })
                                .id(item.id)
                                .listRowInsets(EdgeInsets(top: 2, leading: 8, bottom: 2, trailing: 8))
                                .listRowSeparator(.hidden).listRowBackground(Color.clear)
                        }
                    }
                    .listStyle(.plain).scrollContentBackground(.hidden)
                    .focusable().focused($listFocused).focusEffectDisabled()
                    .onChange(of: selectedID) { _, id in if let id { scroll.scrollTo(id) } }
                    .onKeyPress(.upArrow) { guard listFocused else { return .ignored }; move(-1); return .handled }
                    .onKeyPress(.downArrow) { guard listFocused else { return .ignored }; move(1); return .handled }
                    .onKeyPress(.return) { guard listFocused, let selected else { return .ignored }; perform(selected); return .handled }
                    .onKeyPress(.space) { guard listFocused, let selected else { return .ignored }; detail = selected; return .handled }
                }
                .padding(.vertical, 8).frame(minWidth: 290, maxWidth: .infinity)
                if hasPreview, let selected {
                    Divider()
                    ShelfItemPreview(item: selected, url: model.previewURL(for: selected), copied: copiedID == selected.id, expanded: false,
                        onAction: { perform(selected) }, onPin: { model.pin(selected) }, onExpand: { detail = selected })
                        .frame(width: min(310, geometry.size.width * 0.45)).id(selected.id)
                }
            }
            .background(HoppairPalette.surface, in: RoundedRectangle(cornerRadius: 14))
            .clipShape(RoundedRectangle(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(HoppairPalette.border))
        }
        .onAppear {
            selectedID = (model.isScreenshotMode ? items.first(where: { $0.mime?.hasPrefix("image/") == true }) : nil)?.id ?? items.first?.id
        }
        .onChange(of: items.map(\.id)) { _, ids in if let selectedID, !ids.contains(selectedID) { self.selectedID = ids.first } }
        .task(id: copiedID) {
            guard copiedID != nil else { return }
            do { try await Task.sleep(for: .seconds(2)); copiedID = nil } catch { }
        }
        .sheet(item: $detail) { item in
            let item = model.items.first(where: { $0.id == item.id }) ?? item
            VStack(spacing: 0) {
                HStack { Text("Item Preview").font(.headline); Spacer(); Button("Done") { detail = nil }.hoppairButton().keyboardShortcut(.cancelAction) }.padding(20)
                Divider()
                ShelfItemPreview(item: item, url: model.previewURL(for: item), copied: copiedID == item.id, expanded: true,
                    onAction: { perform(item) }, onPin: { model.pin(item) }, onExpand: nil)
            }.frame(width: 560, height: 540)
        }
    }
    private func perform(_ item: ShelfItem) {
        if item.kind == "text", let text = item.text { if model.copy(text) { copiedID = item.id } }
        else { model.open(item) }
    }
    private func move(_ offset: Int) {
        guard !items.isEmpty else { return }
        let index = items.firstIndex(where: { $0.id == selected?.id }) ?? 0
        selectedID = items[max(0, min(items.count - 1, index + offset))].id
    }
}
