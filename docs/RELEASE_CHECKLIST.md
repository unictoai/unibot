# unibot website — release checklist

The site is static HTML on GitHub Pages (`docs/index.html`) with no build
step, so the version is **not** centralized: every spot below must be
bumped by hand on each release. (A short reminder of these spots also
lives in the comment at the top of `docs/index.html`.)

The APK URL is deterministic — the release workflow publishes the APK
after tagging:
`https://github.com/unictoai/unibot/releases/download/v<VERSION>/unibot-<VERSION>-arm64.apk`

The release coordinator verifies the URL post-release and fixes it if
the actual filename differs from the pattern.

## Every place to bump on a release

| # | Location in `docs/index.html` | Change |
|---|---|---|
| 1 | Hero pill: `<div class="pill">v1.x — …</div>` | New version + headline feature |
| 2 | Hero "Download APK" button `href` | New APK URL |
| 3 | Download section heading: `Get unibot v1.x` | New version string |
| 4 | "Download for Android" button `href` | New APK URL |
| 5 | FAQ entry: `What's new in v1.x?` | Add/update the entry describing the new release; keep it concise and professional |
| 6 | JSON-LD block in `<head>`: `downloadUrl` | New APK URL (keeps structured data accurate) |

## Quick sed one-liner (after committing the FAQ text by hand)

```sh
cd ~/workspace/uni-muse/upstream
sed -i 's/1\.3\.5/1.3.6/g; s/v1\.3\.5/v1.3.6/g' docs/index.html
```

Then verify with:
```sh
grep -n -o "1\.3\.[0-9]" docs/index.html | sort -t: -k2 -u
```

## Don't forget

- Keep the FAQ "What's new" entry concise and professional — no marketing fluff.
- If the release changes headline features (e.g. a new nav entry or connector),
  consider updating the features section too — not every patch needs this.
- Commit on `main` and push with `~/workspace/bin/ugit push origin main`.
