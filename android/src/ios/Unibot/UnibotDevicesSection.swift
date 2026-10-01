// unibot: the account's devices on the hub, shown under the unibot Cloud account —
// this iPhone's two switches (reachable · operable), its name, the other devices with an
// online dot (tap an offline one to forget it), and the web console's address.

import SwiftUI
import UIKit

struct UnibotDevicesSection: View {
    @ObservedObject private var hub = UnibotHub.shared
    @State private var enabled = UnibotHub.shared.enabled
    @State private var remote = UnibotHub.shared.remoteControl
    @State private var name = UnibotHub.shared.name
    @State private var editingName = false
    @State private var forgetting: HubDevice?

    var body: some View {
        Section {
            Toggle(isOn: $enabled) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(AppLocalized("Reachable from your devices"))
                    Text(status).font(.footnote).foregroundStyle(.secondary)
                }
            }
            .onChange(of: enabled) { on in hub.enabled = on }
            Toggle(AppLocalized("Let other devices operate this iPhone"), isOn: $remote)
                .onChange(of: remote) { on in hub.remoteControl = on }
            Button {
                editingName = true
            } label: {
                LabeledContent(AppLocalized("This iPhone's name"), value: name)
            }
            .tint(.primary)
        } header: {
            Text(AppLocalized("Devices"))
        } footer: {
            Text(AppLocalized("Every device signed in to this account is a Muse: this iPhone, your computers running unibot Desktop, the web console. Each can ask the others for things — on any network, through unibot Cloud. On iOS the others can open links here and send notifications; the iPhone's own Muse works on the iPhone."))
        }
        .alert(AppLocalized("This iPhone's name"), isPresented: $editingName) {
            TextField(AppLocalized("Name"), text: $name)
            Button(AppLocalized("OK")) { hub.name = name; name = hub.name }
            Button(AppLocalized("Cancel"), role: .cancel) { name = hub.name }
        }

        Section {
            if hub.others.isEmpty {
                Text(AppLocalized("No other device yet. On your computer, install unibot Desktop and sign in with the same account; it appears here within seconds."))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } else {
                ForEach(hub.others) { d in
                    Button {
                        if !d.online { forgetting = d }
                    } label: {
                        HStack {
                            Image(systemName: d.isPhone ? "iphone" : "desktopcomputer").foregroundStyle(.primary)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(d.name).foregroundStyle(.primary)
                                Text([d.os, d.online ? AppLocalized("online") : AppLocalized("offline")].filter { !$0.isEmpty }.joined(separator: " · "))
                                    .font(.footnote).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Circle().fill(d.online ? Color.green : Color.secondary.opacity(0.35)).frame(width: 10, height: 10)
                        }
                    }
                }
            }
        } footer: {
            Text(AppLocalized("In a chat on your computer, just ask: “send my iPhone a notification”, “open this page on my iPhone”."))
        }
        .confirmationDialog(forgetting?.name ?? "", isPresented: Binding(get: { forgetting != nil }, set: { if !$0 { forgetting = nil } }), titleVisibility: .visible) {
            Button(AppLocalized("Forget"), role: .destructive) {
                if let d = forgetting { hub.forget(d.id) }
                forgetting = nil
            }
        } message: {
            Text(AppLocalized("Take this device off the list. It comes back the next time it signs in with this account."))
        }

        Section {
            Button {
                UIPasteboard.general.string = hub.webConsoleURL
            } label: {
                LabeledContent {
                    Text(AppLocalized("Copy link")).foregroundStyle(.tint)
                } label: {
                    Label(AppLocalized("Web console"), systemImage: "globe")
                    Text(hub.webConsoleURL).font(.footnote).foregroundStyle(.secondary)
                }
            }
            .tint(.primary)
        } footer: {
            Text(AppLocalized("Open the link in any browser, sign in with the same account, and drive your devices from there."))
        }
    }

    private var status: String {
        if !enabled { return AppLocalized("Off") }
        return hub.connected ? AppLocalized("Connected") : AppLocalized("Connecting…")
    }
}
