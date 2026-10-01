# Troubleshooting

Start with `unibot doctor`: it prints the config file in use, the data directory, the model and endpoint (and whether a key is set), the tools and connectors, and makes one call to the model. Most of the problems below show up there first.

**The phone cannot open the URL.** Start with `--host 0.0.0.0` (the default binds to localhost only), make sure both devices are on the same network, and allow the port through the machine's firewall. The URL shown uses the LAN address the server could detect; if it is wrong, use the machine's address from `ip addr` / `ipconfig` with the same `?token=`.

**"web app not built" on start.** You are running from a checkout without the built front-end. `cd web && npm install && npm run build`, or install the package instead.

**Replies come back in the wrong language.** With `agent.language = "auto"` the system prompt names the language of your latest message (detected by script). If a model still drifts, set a fixed language in *You → Reply language* or `agent.language = "English"`.

**The beginning of a streamed reply is missing.** Some proxies that inline `<think>…</think>` into the content drop the first tokens after `</think>` on the server side. Non-streaming responses are complete; set `stream = false` under `[llm]`.

**The model never calls tools.** The endpoint probably ignores the `tools` field without an error (an endpoint that *rejects* it is handled by the default `tool_mode = "auto"`). Set `tool_mode = "prompt"`; tools are then described in the system prompt and parsed from `<tool_call>` blocks.

**"does not support tools" in the log, then the agent carries on.** That is `auto` doing its job: Ollama refused function calling for this model, so tools are described in the prompt from then on. Pick a model with a tool template (`qwen3:8b`, `llama3.1:8b`) for better results on long tasks.

**Empty replies, or Ideas that stay on the starter list, with a reasoning model.** DeepSeek's thinking variants and the OpenAI o-series count their reasoning against `max_tokens`; on a hard prompt they can spend the whole budget thinking and return nothing (`finish_reason = "length"`). unibot asks once more with four times the budget when that happens. If it keeps happening, raise `max_tokens` under `[llm]` (16384 is a sensible value for these models).

**429 / rate limits.** Requests retry with exponential back-off (`max_retries`, default 5). Lower `agent.max_steps`, or add `web_fetch` to `always_ask_tools` to slow the loop down.

**An approval card never appears in the terminal.** `unibot daemon` and `--auto` run in Sentinel `auto` mode by design. Use `unibot chat` or the app for interactive approvals.

**"Sentinel blocked" for something you wanted.** Check `unibot audit -n 20` for the reason: a deny rule, `deny_tools`, or taint (private data read earlier in the session plus a new network destination). Add the host to `egress_allowlist` or approve once.

**Email tool returns nothing useful.** With `scrub_secrets = true` one-time codes and reset links are removed before the model reads a mail; that is intentional. Check IMAP settings with `unibot config show` and the vault entries with `unibot vault list`.

**Playwright fails to install Chromium.** Older distributions are not supported by recent Playwright builds. Use the Docker image or leave `browser.enabled = false`; `web_fetch` covers most reading tasks.

**The desktop app shows "unibot could not start", or a blank window.** The shell starts `unibot serve` on `127.0.0.1:8787` and reads its log at `~/.unibot/desktop-app.log` (`UNIBOT_HOME` if you set it); the dialog quotes the last lines and names the usual causes — the port taken by another program or another unibot (`UNIBOT_PORT` picks a different one), a data folder that is not writable, a `config.toml` that does not parse. If the runtime stops later, the shell restarts it once and asks before trying again; *Open the log* in that dialog and the tray's *Open the log folder* lead to the same file. Updates are not installed on their own: the tray's *Check for updates* looks at GitHub Releases.

**"The data folder … is not writable" / "cannot write to the data directory" right after installing (Windows, 0.1.20–0.1.21).** The runtime created its `workspace` folder relative to wherever the launcher started it — `C:\Windows\System32` when the installer opened the app — and stopped when that failed. Since 0.1.22 the workspace defaults to `<data dir>\workspace` and the shell starts the runtime inside its data folder; update, or set `UNIBOT_WORKSPACE` for an older version.

**macOS: Finder "can't complete the operation … error code -36" while dragging unibot.app out of the dmg (0.1.20–0.1.21).** The image was HFS+; since 0.1.22 the dmg is APFS and the release also carries a `.zip` of the app — download it, double-click to unpack, drag `unibot.app` to Applications.

**macOS: "unibot.app is damaged and can't be opened" or "Apple could not verify…".** There is no Apple developer certificate behind the project, so the bundle is ad-hoc signed and macOS asks once. Open it, dismiss the dialog, then System Settings → Privacy & Security → *Open Anyway* (or, from a terminal, `xattr -dr com.apple.quarantine /Applications/unibot.app`). Windows shows "Windows protected your PC" for the same reason: *More info* → *Run anyway*.

**The Android app says "Refused by the relay — sign in again" under Devices.** The relay no longer accepts this phone's key (signed out everywhere, or the account was deleted). Sign out and in again on the Account screen; the hub joins again on its own.

**Reset everything.** Stop the server and delete `~/.unibot` (or your `data_dir`). The vault key lives there too, so export secrets first if you need them.
