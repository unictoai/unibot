# unibot <version> · <Codename>

<!-- The first line is the GitHub release title (scripts/release-apk.sh reads it).
     English first; the Chinese version of the same notes goes in the <details> block at the end.
     Codenames so far: Foundation, Identity, Home, Guardrails, Memory, Avatar, Welcome, Polish,
     Portrait, Motion, Hatch, Hands, Reach, Home, Stage, Palette, Open, Ensemble, Presence,
     Footing, Commons, Welcome, Signal, Mirror (CHANGELOG.md has the list). One word, capitalised.

     The shape follows the releases people know (nanobot's): a short story, then Highlights,
     Upgrade Notes, Community; What's Changed, New Contributors, Contributors and the Full
     Changelog link are generated from the merged pull requests by scripts/release_notes.py
     where the marker below stands, when the release is published. -->

🐉 **unibot `<version>` “<Codename>”** <one sentence: what this version is about>.

<One or two short paragraphs: the headline, and the other story — in plain words, what it means for the person using it.>

## Highlights

- **<Thing>.** <What it is, where to find it, what it replaces.>
- …

## Upgrade Notes

- **Android installs over <previous> and keeps your data.** `unibot-<version>-arm64.apk` (Android 8.0+, arm64; versionCode <n>) is signed with the same key as every version before it. Verify with `sha256sum -c unibot-<version>-arm64.apk.sha256`.
- **The desktop installers are not notarised.** On macOS open the app once from *System Settings → Privacy & Security → Open Anyway* (or right-click → *Open*); on Windows click *Run anyway*. They arrive a little after the APK — CI builds them from the tag — and `SHA256SUMS-desktop-app.txt` / `SHA256SUMS-desktop-terminal.txt` list every checksum.
- <Anything a person upgrading must know: a setting that moved, a relay version an operator needs, a platform that needs a newer build.>
- **Where to get it.**

  | | |
  |---|---|
  | Browser | not shipped — run `unibot serve` on your own machine |
  | Android 8.0+, arm64 | `unibot-<version>-arm64.apk` |
  | Windows 10+ | `unibot-Desktop-<version>-win-x64.exe` (the app) · `unibot-desktop-terminal-<version>-windows-x64-setup.exe` (terminal) |
  | macOS 12+ | `unibot-Desktop-<version>-mac-arm64.zip` / `-mac-x64.zip` (unzip, drag to Applications) or the `.dmg` (the app) · `unibot-desktop-terminal-<version>-macos-arm64.pkg` / `-x64.pkg` (terminal) |
  | Linux x64 | `unibot-Desktop-<version>-linux-x64.AppImage` / `.deb` (the app) · `unibot-desktop-terminal-<version>-linux-x64.deb` / `.tar.gz` (terminal) |

  GitHub's own downloads are the fastest source from China too in our measurements; if they fail where you are, try a GitHub mirror or proxy in your region.

## Community

**Free, open source, non-profit — open source, built together: a personal agent for all.** Sign in with a phone number or an e-mail and the model comes with a free allowance, paid by the developer — the account page says how much is left and how it grows; when it is gone, bring your own key, with a step-by-step guide. Messages are not stored unless you choose to contribute them; nothing is sold; delete the account whenever you like.

Thank you to everyone who tried a build, reported what broke and asked for what was missing — every issue, idea and pull request brings a personal agent within everyone's reach. [Open an issue](https://github.com/unictoai/unibot/issues/new/choose) · [send a pull request](https://github.com/unictoai/unibot/blob/main/CONTRIBUTING.md) · [Discussions](https://github.com/unictoai/unibot/discussions) · [star the repo](https://github.com/unictoai/unibot).

Based on [OpenMinis](https://github.com/OpenMinis/OpenMinis) 1.13 (GPL-3.0), modified since 2026-09-24. The complete corresponding source of this build is tag `v<version>` plus the `android/deps/proot` submodule ([nano-muse/proot](https://github.com/nano-muse/proot)). The whole repository is GPL-3.0-or-later. unibot is not affiliated with Meta; Muse is a trademark of Meta Platforms, Inc.

<!-- pull requests -->

<details>
<summary>简体中文</summary>

🐉 **unibot `<version>`「<Codename>」**：<一句话：这个版本是关于什么的>。

<一两段话：主线，以及另一条线——用平实的话说清对用的人意味着什么。>

### 亮点

- **<什么>。** <是什么、在哪里、替代了什么。>
- …

### 升级说明

- **Android 覆盖安装 <previous>，数据保留。** `unibot-<version>-arm64.apk`（Android 8.0+，arm64；versionCode <n>）和之前每个版本用同一把签名。校验：`sha256sum -c unibot-<version>-arm64.apk.sha256`。
- **桌面安装包没有经过公证。** macOS 第一次在「系统设置 → 隐私与安全性」里点「仍要打开」（或右键 → 「打开」），Windows 点「仍要运行」。安装包会比 APK 晚一点到——CI 从 tag 构建它们；`SHA256SUMS-desktop-app.txt` 和 `SHA256SUMS-desktop-terminal.txt` 列出了所有校验值。
- <升级的人必须知道的事：挪了位置的设置、运维者需要的中转版本、需要新构建的平台。>
- **去哪下载。**

  | | |
  |---|---|
  | 浏览器 | 不提供——在自己的机器上运行 `unibot serve` |
  | Android 8.0+，arm64 | `unibot-<version>-arm64.apk` |
  | Windows 10+ | `unibot-Desktop-<version>-win-x64.exe`（App）· `unibot-desktop-terminal-<version>-windows-x64-setup.exe`（终端） |
  | macOS 12+ | `unibot-Desktop-<version>-mac-arm64.zip` / `-mac-x64.zip`（解压后拖进「应用程序」）或 `.dmg`（App）· `unibot-desktop-terminal-<version>-macos-arm64.pkg` / `-x64.pkg`（终端） |
  | Linux x64 | `unibot-Desktop-<version>-linux-x64.AppImage` / `.deb`（App）· `unibot-desktop-terminal-<version>-linux-x64.deb` / `.tar.gz`（终端） |

  实测从国内直接下 GitHub 也是最快的；万一下不动，可以试试你所在地区的 GitHub 镜像或代理。

### 社区

**免费 · 开源 · 非盈利 —— 开源共建，做属于所有人的个人智能体。** 用手机号或邮箱登录，模型自带一份免费额度，费用由开发者承担——还剩多少、怎么增加，账号页里写得清楚；用完可以换自己的 key，有一步步的教程。默认不保存你的消息，数据不会出售，随时可以删除账号。

谢谢每一位装过试过、报过问题、提过需求的人——每一个 issue、想法和 PR，都在让个人智能体离所有人更近一步。[提 issue](https://github.com/unictoai/unibot/issues/new/choose) · [发 PR](https://github.com/unictoai/unibot/blob/main/CONTRIBUTING.md) · [Discussions](https://github.com/unictoai/unibot/discussions) · [点个 Star](https://github.com/unictoai/unibot)。

基于 [OpenMinis](https://github.com/OpenMinis/OpenMinis) 1.13（GPL-3.0），自 2026-09-24 起修改；本版本的完整对应源码是 tag `v<version>` 加子模块 `android/deps/proot`（[nano-muse/proot](https://github.com/nano-muse/proot)）。整个仓库以 GPL-3.0-or-later 发布。unibot 与 Meta 无关，Muse 是 Meta Platforms, Inc. 的商标。

</details>
