import SwiftUI

/// Settings › Providers › unibot Cloud. Signed out: a phone number or an e-mail address, a
/// code, done. Signed in: what is left of the starter allowance, a refresh, the way out.
/// Mirror of the Android `CloudSignInScreen` / `CloudAccountScreen`.
struct UnibotCloudView: View {
    @ObservedObject private var store = ProviderConfigStore.shared
    @State private var account: UnibotCloudAccount?
    @State private var identifier = ""
    @State private var code = ""
    @State private var codeSent = false
    @State private var busy = false
    @State private var message: String?
    @State private var failed = false
    @State private var confirmSignOut = false
    @State private var relayBase = ""

    private var signedIn: Bool {
        _ = store.instances  // re-evaluate when the provider is removed elsewhere
        return UnibotCloud.isSignedIn
    }

    var body: some View {
        Form {
            if signedIn {
                accountSections
                UnibotDevicesSection()
            } else {
                signInSections
            }
            if UnibotCloud.canOverrideBase {
                relaySection
            }
        }
        .navigationTitle(UnibotCloud.label)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            account = UnibotCloud.account
            relayBase = UnibotCloud.baseURL == UnibotCloud.defaultBase ? "" : UnibotCloud.baseURL
            if signedIn { await refresh(quiet: true) }
        }
    }

    // MARK: - Signed in

    @ViewBuilder
    private var accountSections: some View {
        Section {
            if let account {
                LabeledContent(AppLocalized("Signed in as"), value: account.hint)
                VStack(alignment: .leading, spacing: 6) {
                    if account.unlimited {
                        // No ceiling on this relay: what was used, nothing to run out of.
                        let used = account.used.formatted()
                        let usedToday = account.usedToday.formatted()
                        Text(AppLocalized("No limit on this account"))
                            .font(.subheadline)
                        Text(AppLocalized("\(used) tokens used so far, \(usedToday) today"))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    } else {
                        ProgressView(value: account.fraction)
                        let remaining = account.remaining.formatted()
                        let granted = account.granted.formatted()
                        Text(AppLocalized("\(remaining) of \(granted) tokens left"))
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                        if account.dailyCap > 0 {
                            let usedToday = account.usedToday.formatted()
                            let dailyCap = account.dailyCap.formatted()
                            Text(AppLocalized("Today: \(usedToday) of \(dailyCap)"))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                    let checked = account.checkedAt.formatted(.relative(presentation: .named))
                    Text(AppLocalized("Checked \(checked)"))
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                }
                .padding(.vertical, 4)
            } else {
                Text(AppLocalized("Signed in. Pull the balance with Refresh."))
                    .foregroundStyle(.secondary)
            }
            Button {
                Task { await refresh(quiet: false) }
            } label: {
                Label(AppLocalized("Refresh"), systemImage: "arrow.clockwise")
            }
            .disabled(busy)
        } footer: {
            if let message {
                Text(message).foregroundStyle(failed ? Color.red : Color.secondary)
            }
        }

        if let inst = UnibotCloud.instance {
            Section {
                NavigationLink {
                    ProviderInstanceDetailView(instanceId: inst.id)
                } label: {
                    Label(AppLocalized("Provider settings"), systemImage: "slider.horizontal.3")
                }
            } footer: {
                Text(AppLocalized("The relay is an ordinary provider named \"unibot Cloud\": its models can join any model group, and a key of your own can sit next to it."))
            }
        }

        Section {
            Button(role: .destructive) {
                confirmSignOut = true
            } label: {
                Label(AppLocalized("Sign out"), systemImage: "rectangle.portrait.and.arrow.right")
            }
            .disabled(busy)
            .confirmationDialog(
                AppLocalized("Sign out of unibot Cloud?"),
                isPresented: $confirmSignOut,
                titleVisibility: .visible
            ) {
                Button(AppLocalized("Sign out"), role: .destructive) {
                    Task { await signOut() }
                }
            } message: {
                Text(AppLocalized("This phone's key is revoked and the provider is removed. What is left of the allowance stays with the account."))
            }
        } footer: {
            privacyFooter
        }
    }

    // MARK: - Signed out

    @ViewBuilder
    private var signInSections: some View {
        Section {
            Text(AppLocalized("Sign in with a phone number or an e-mail address and start right away with a starter allowance — no key of your own needed. A provider of your own can be added at any time."))
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }

        Section {
            TextField(AppLocalized("Phone number or e-mail"), text: $identifier)
                .keyboardType(.emailAddress)
                .textContentType(.username)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .disabled(busy || codeSent)
            if codeSent {
                TextField(AppLocalized("Verification code"), text: $code)
                    .keyboardType(.numberPad)
                    .textContentType(.oneTimeCode)
                    .disabled(busy)
            }
        } footer: {
            if let message {
                Text(message).foregroundStyle(failed ? Color.red : Color.secondary)
            }
        }

        Section {
            if codeSent {
                Button {
                    Task { await signIn() }
                } label: {
                    HStack {
                        Text(AppLocalized("Sign in"))
                        if busy { Spacer(); ProgressView() }
                    }
                }
                .disabled(busy || code.trimmingCharacters(in: .whitespaces).isEmpty)
                Button(AppLocalized("Use another number or address")) {
                    codeSent = false
                    code = ""
                    message = nil
                }
                .disabled(busy)
            } else {
                Button {
                    Task { await sendCode() }
                } label: {
                    HStack {
                        Text(AppLocalized("Send code"))
                        if busy { Spacer(); ProgressView() }
                    }
                }
                .disabled(busy || identifier.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        } footer: {
            privacyFooter
        }
    }

    private var privacyFooter: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(AppLocalized("The relay keeps a hashed identifier and token counts. Messages are passed to the model and not stored."))
            Link(AppLocalized("How unibot Cloud works"), destination: URL(string: "https://github.com/unictoai/unibot/blob/main/docs/cloud.md")!)
        }
    }

    // MARK: - Debug

    private var relaySection: some View {
        Section {
            TextField(UnibotCloud.defaultBase, text: $relayBase)
                .keyboardType(.URL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .onSubmit { UnibotCloud.setBaseURL(relayBase) }
                .onChange(of: relayBase) { newValue in UnibotCloud.setBaseURL(newValue) }
        } header: {
            Text("Relay (debug builds only)")
        } footer: {
            Text("Another relay to sign in against, for example one running on a laptop on the same Wi-Fi. Empty means the default.")
        }
    }

    // MARK: - Actions

    private func sendCode() async {
        busy = true
        failed = false
        defer { busy = false }
        do {
            try await UnibotCloud.requestCode(identifier: identifier)
            codeSent = true
            message = AppLocalized("Code sent. Enter it below.")
        } catch {
            failed = true
            message = UnibotCloud.describe(error)
        }
    }

    private func signIn() async {
        busy = true
        failed = false
        defer { busy = false }
        do {
            account = try await UnibotCloud.verify(identifier: identifier, code: code)
            message = nil
            code = ""
            codeSent = false
        } catch {
            failed = true
            message = UnibotCloud.describe(error)
        }
    }

    private func refresh(quiet: Bool) async {
        busy = true
        defer { busy = false }
        do {
            account = try await UnibotCloud.refresh()
            if !quiet {
                failed = false
                message = nil
            }
        } catch {
            if !quiet {
                failed = true
                message = UnibotCloud.describe(error)
            }
        }
    }

    private func signOut() async {
        busy = true
        defer { busy = false }
        await UnibotCloud.signOut()
        account = nil
        message = nil
        failed = false
    }
}

/// The row in the providers list that leads to `UnibotCloudView`.
struct UnibotCloudRow: View {
    @ObservedObject private var store = ProviderConfigStore.shared

    var body: some View {
        _ = store.instances
        let account = UnibotCloud.isSignedIn ? UnibotCloud.account : nil
        return HStack(spacing: 12) {
            Image(systemName: "cloud.fill")
                .font(.title3)
                .foregroundStyle(Color.accentColor)
                .frame(width: 28)
            VStack(alignment: .leading, spacing: 2) {
                Text(UnibotCloud.label)
                if let account {
                    let hint = account.hint
                    let left = account.fraction.formatted(.percent.precision(.fractionLength(0)))
                    Text(AppLocalized("\(hint) · \(left) of the allowance left"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                } else if UnibotCloud.isSignedIn {
                    Text(AppLocalized("Signed in"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                } else {
                    Text(AppLocalized("Phone or e-mail sign-in, no key needed"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
    }
}
