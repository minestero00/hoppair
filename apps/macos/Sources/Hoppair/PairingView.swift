import SwiftUI
import HoppairCore

struct PairingView: View {
    @EnvironmentObject private var model: AppModel
    @State private var advanced = false
    @State private var relayDraft = ""
    var body: some View {
        VStack(spacing: 20) {
            HStack {
                Spacer()
                Button { model.showPairing = false } label: { Image(systemName: "xmark.circle.fill").font(.title2).foregroundStyle(.secondary) }.hoppairButton(.icon).accessibilityLabel("Close pairing")
            }
            Image(nsImage: (NSImage(named: "BrandIcon") ?? NSApplication.shared.applicationIconImage)).resizable().scaledToFit().frame(width: 64, height: 64).clipShape(RoundedRectangle(cornerRadius: 15)).accessibilityHidden(true)
            VStack(spacing: 8) {
                Text("Scan to connect.").font(.title.bold())
                Text("Scan with your Android camera.\nOpen Hoppair, or install it first.").multilineTextAlignment(.center).foregroundStyle(.secondary)
            }
            if model.preparing {
                ProgressView("Preparing your connection").frame(width: 264, height: 264)
            } else if let image = qrImage {
                Image(nsImage: image).interpolation(.none).resizable().scaledToFit().frame(width: 224, height: 224)
                    .padding(20).background(.white, in: RoundedRectangle(cornerRadius: 16))
                    .accessibilityLabel("Pairing QR code for Hoppair on Android")
            } else {
                VStack(spacing: 14) {
                    Image(systemName: "wifi.exclamationmark").font(.largeTitle)
                    Text("Check your Mac’s network connection.").multilineTextAlignment(.center)
                    Button("Check Again") { model.refreshNetwork() }
                }.frame(width: 264, height: 264)
            }
            Label(model.relayConnected ? "Connect across different networks" : "Connect both devices to the same network", systemImage: model.relayConnected ? "globe" : "wifi").font(.callout)
            TimelineView(.periodic(from: .now, by: 2)) { context in
                if let lastSeen = model.lastSeen, context.date.timeIntervalSince(lastSeen) < 12 {
                    Label("Connected! Your shared shelf is ready.", systemImage: "checkmark.circle.fill").font(.callout).foregroundStyle(.tint)
                }
            }
            DisclosureGroup("More connection options", isExpanded: $advanced) {
                VStack(alignment: .leading, spacing: 12) {
                    Text("Send the pairing link to your phone and paste it there. Choose another address if your Mac uses multiple networks.").font(.callout).foregroundStyle(.secondary)
                    Divider()
                    Text("Relay Settings").font(.caption.bold())
                    TextField("Relay address (wss://…)", text: $relayDraft)
                    HStack { Text("Device ID: " + model.relayRoom).font(.caption).textSelection(.enabled); Spacer(); Button("Apply") { model.configureRelay(relayDraft) } }
                    Button("Copy Relay Registration") { model.copy(model.relayRegistration) }.disabled(model.preparing)
                    if !model.relayAddress.isEmpty && !model.relayConnected { Text("Connecting to the relay. Check that this device is registered.").font(.caption).foregroundStyle(.secondary) }
                    Picker("Network Address", selection: $model.selectedAddress) { ForEach(model.addresses, id: \.self) { Text($0).tag($0) } }
                    HStack { Text(model.pairingLink).font(.system(.caption, design: .monospaced)).lineLimit(2).textSelection(.enabled); Button("Copy Link") { model.copy(model.pairingLink) } }
                }.padding(.top, 12)
            }.font(.callout)
            Text("Scan this code with your own phone only.").font(.caption).foregroundStyle(.secondary)
            Button("Done") { model.showPairing = false }.hoppairButton(.primary).keyboardShortcut(.defaultAction)
        }.buttonStyle(HoppairButtonStyle()).padding(28).frame(width: 470).onAppear { model.refreshNetwork(); relayDraft = model.relayAddress }
    }
    private var qrImage: NSImage? {
        guard let image = PairingCode.image(for: model.pairingLink) else { return nil }
        return NSImage(cgImage: image, size: NSSize(width: image.width, height: image.height))
    }
}
