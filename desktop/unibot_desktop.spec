# -*- mode: python ; coding: utf-8 -*-
"""PyInstaller recipe for unibot Desktop: one file, one binary, no runtime to install.

    pyinstaller unibot_desktop.spec

The package is standard-library Python; `mss` and Pillow are picked up when present so the
binary can take screenshots without helper programs. Icons come from ../assets/brand when the
platform-specific files exist (see scripts/build-desktop.py, which generates them).
"""

import os
import sys

block_cipher = None
here = os.path.abspath(SPECPATH)

icon = None
if sys.platform == "win32" and os.path.exists(os.path.join(here, "build", "icon.ico")):
    icon = os.path.join(here, "build", "icon.ico")
elif sys.platform == "darwin" and os.path.exists(os.path.join(here, "build", "icon.icns")):
    icon = os.path.join(here, "build", "icon.icns")

hidden = []
for optional in ("mss", "PIL.Image"):
    try:
        __import__(optional)
        hidden.append(optional)
    except Exception:
        pass

a = Analysis(
    ["launcher.py"],
    pathex=[here],
    binaries=[],
    datas=[],
    hiddenimports=hidden,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=["tkinter", "unittest", "pydoc", "doctest", "xmlrpc", "lib2to3"],
    win_no_prefer_redirects=False,
    win_private_assemblies=False,
    cipher=block_cipher,
    noarchive=False,
)
pyz = PYZ(a.pure, a.zipped_data, cipher=block_cipher)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.zipfiles,
    a.datas,
    [],
    name="unibot-desktop",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=True,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    icon=icon,
)
