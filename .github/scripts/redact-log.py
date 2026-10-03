#!/usr/bin/env python3
"""Redact secret-looking values from CI log tails before they are published
as public GitHub issues (see the "Report * failure" steps).

Reads stdin, writes the redacted text to stdout. Patterns are deliberately
broad — a false positive in a failure log is harmless, a leaked key is not.
"""
import re
import sys

PATTERNS = [
    # api_key=..., "apiKey": "...", 'api-key'='...'
    (r'''(?i)(api[_-]?key["']?\s*[:=]\s*["']?)[^"'\s,}]+''', r"\1<redacted>"),
    # password=..., "password": "..."
    (r'''(?i)(password["']?\s*[:=]\s*["']?)[^"'\s,}]+''', r"\1<redacted>"),
    # Bearer tokens in headers / URLs
    (r"""(?i)(bearer\s+)[A-Za-z0-9._~+\-/=]+""", r"\1<redacted>"),
    # token=..., "token": "..."  (but not harmless words like "tokens_used")
    (r'''(?i)(?<![\w-])(token["']?\s*[:=]\s*["']?)[^"'\s,}]+''', r"\1<redacted>"),
    # secret=..., "client_secret": "..."
    (r'''(?i)(secret["']?\s*[:=]\s*["']?)[^"'\s,}]+''', r"\1<redacted>"),
    # private key blocks
    (r"""-----BEGIN [A-Z ]*PRIVATE KEY-----""", "-----BEGIN PRIVATE KEY-----<redacted>"),
    # ?token=... / &token=... query params
    (r"""(?i)([?&](?:api[_-]?key|token|auth)[=])[^&\s]+""", r"\1<redacted>"),
]


def main() -> None:
    text = sys.stdin.read()
    for pattern, replacement in PATTERNS:
        text = re.sub(pattern, replacement, text)
    sys.stdout.write(text)


if __name__ == "__main__":
    main()
