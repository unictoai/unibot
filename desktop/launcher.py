"""PyInstaller entry point: the package's main, imported as a package."""

import sys

from unibot_desktop.__main__ import main

if __name__ == "__main__":
    sys.exit(main())
