import SwiftUI
import HoppairCore

struct PairingView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var advanced = false
    @State private var relayDraft = ""
    private let maximumHeight: CGFloat?

    init(initiallyExpanded: Bool = false, maximumHeight: CGFloat? = nil) {
        _advanced = State(initialValue: initiallyExpanded)
        self.maximumHeight = maximumHeight
    }

    private var sheetHeight: CGFloat {
        let screenHeight = (NSApp.keyWindow?.screen ?? NSScreen.main)?.visibleFrame.height ?? 770
        return max(360, min(650, maximumHeight ?? (screenHeight - 120)))
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Spacer()
                Button { model.showPairing = false } label: {
                    Image(systemName: "xmark").font(.body.weight(.medium)).foregroundStyle(HoppairPalette.secondaryText)
                }
                .hoppairButton(.icon)
                .accessibilityLabel("Close pairing")
                .keyboardShortcut(.cancelAction)
            }.padding(.horizontal, 16).padding(.top, 12).padding(.bottom, 4)

            ScrollViewReader { proxy in
                ScrollView {
                    VStack(spacing: 20) {
                        Image(nsImage: NSImage(named: "BrandIcon") ?? NSApplication.shared.applicationIconImage)
                            .resizable().scaledToFit().frame(width: 48, height: 48)
                            .accessibilityHidden(true)
                        VStack(spacing: 8) {
                            Text("Scan to connect.").font(.title.bold())
                            Text("Scan with your Android camera.\nOpen Hoppair, or install it first.")
                                .multilineTextAlignment(.center).foregroundStyle(HoppairPalette.secondaryText)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        pairingCode
                        Label(model.relayConnected ? "Connect across different networks" : "Connect both devices to the same network",
                              systemImage: model.relayConnected ? "globe" : "wifi")
                            .font(.callout).multilineTextAlignment(.center)
                            .fixedSize(horizontal: false, vertical: true)
                        Text("Pair once. Reopen both apps to reconnect automatically.")
                            .font(.caption).foregroundStyle(HoppairPalette.secondaryText).multilineTextAlignment(.center)
                        TimelineView(.periodic(from: .now, by: 2)) { context in
                            if let lastSeen = model.lastSeen, context.date.timeIntervalSince(lastSeen) < 12 {
                                Label("Connected! Your shared shelf is ready.", systemImage: "checkmark.circle.fill")
                                    .font(.callout).foregroundStyle(.tint)
                            }
                        }
                        connectionOptions.id("connection-options")
                    }
                    .padding(.horizontal, 24).padding(.top, 4).padding(.bottom, 24)
                    .frame(maxWidth: .infinity)
                }
                .scrollBounceBehavior(.basedOnSize)
                .scrollIndicators(.visible)
                .onAppear {
                    if advanced { proxy.scrollTo("connection-options", anchor: .top) }
                }
                .onChange(of: advanced) { _, expanded in
                    if expanded {
                        withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.2)) {
                            proxy.scrollTo("connection-options", anchor: .top)
                        }
                    }
                }
            }

            Divider()
            HStack(spacing: 16) {
                Text("Scan with your own phone only.").font(.caption).foregroundStyle(HoppairPalette.secondaryText)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
                Button("Done") { model.showPairing = false }
                    .hoppairButton(.primary).keyboardShortcut(.defaultAction)
            }.padding(.horizontal, 24).padding(.vertical, 16)
        }
        .buttonStyle(HoppairButtonStyle())
        .foregroundStyle(HoppairPalette.primaryText)
        .frame(width: 470, height: sheetHeight)
        .hoppairGlass(cornerRadius: 24)
        .onAppear { model.refreshNetwork(); relayDraft = model.relayAddress }
    }

    @ViewBuilder private var pairingCode: some View {
        if model.preparing {
            ProgressView("Preparing your connection").frame(width: 256, height: 256)
        } else if let image = qrImage {
            Image(nsImage: image).interpolation(.none).resizable().scaledToFit()
                .frame(width: 224, height: 224)
                .padding(16).background(.white, in: RoundedRectangle(cornerRadius: 16))
                .accessibilityLabel("Pairing QR code for Hoppair on Android")
        } else {
            VStack(spacing: 14) {
                Image(systemName: "wifi.exclamationmark").font(.largeTitle)
                Text("Check your Mac’s network connection.").multilineTextAlignment(.center)
                Button("Check Again") { model.refreshNetwork() }
            }.frame(width: 256, height: 256)
        }
    }

    private var connectionOptions: some View {
        DisclosureGroup("More connection options", isExpanded: $advanced) {
            VStack(alignment: .leading, spacing: 16) {
                Text("Send the pairing link to your phone, or choose another address if your Mac uses multiple networks.")
                    .foregroundStyle(HoppairPalette.secondaryText).fixedSize(horizontal: false, vertical: true)
                VStack(alignment: .leading, spacing: 8) {
                    Text("Relay address").font(.caption.weight(.semibold))
                    TextField("wss://…", text: $relayDraft).textFieldStyle(.roundedBorder)
                        .accessibilityLabel("Relay address")
                    HStack {
                        Text(model.relayRoom.isEmpty ? "Device ID unavailable" : "Device ID: " + model.relayRoom)
                            .font(.caption).foregroundStyle(HoppairPalette.secondaryText).textSelection(.enabled)
                            .fixedSize(horizontal: false, vertical: true)
                        Spacer(minLength: 8)
                        Button("Apply") { model.configureRelay(relayDraft) }
                            .hoppairButton(.secondary).disabled(model.preparing)
                    }
                    if !model.relayAddress.isEmpty && !model.relayConnected {
                        Text("Connecting to the relay. Check that this device is registered.")
                            .font(.caption).foregroundStyle(HoppairPalette.secondaryText).fixedSize(horizontal: false, vertical: true)
                    }
                    Button("Copy Relay Registration") { model.copy(model.relayRegistration) }
                        .disabled(model.preparing).hoppairButton(.secondary)
                }
                Divider()
                Picker("Network address", selection: $model.selectedAddress) {
                    ForEach(model.addresses, id: \.self) { Text($0).tag($0) }
                }
                VStack(alignment: .leading, spacing: 8) {
                    Text("Pairing link").font(.caption.weight(.semibold))
                    Text(model.pairingLink).font(.system(.caption, design: .monospaced))
                        .foregroundStyle(HoppairPalette.secondaryText).lineLimit(3).textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Button("Copy Link") { model.copy(model.pairingLink) }
                        .hoppairButton(.secondary).disabled(model.preparing || model.pairingLink.isEmpty)
                }
            }.padding(.top, 16)
        }.font(.callout)
    }

    private var qrImage: NSImage? {
        guard let image = PairingCode.image(for: model.pairingLink) else { return nil }
        return NSImage(cgImage: image, size: NSSize(width: image.width, height: image.height))
    }
}
