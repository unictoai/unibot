"""This computer as a device: its screen as a picture, and — when Hands is on — as a hand.

:mod:`unibot.computer.screen` takes the picture (``mss`` and Pillow, or the platform's own
tool) and names the active window; :mod:`unibot.computer.hands` moves the mouse and types
(``pyautogui``, or ``xdotool`` on X11); :mod:`unibot.computer.link` is the object the
operator loop and the ``computer_*`` tools talk to, shaped like the phone's
:class:`~unibot.phone.link.PhoneLink` so one loop serves both.
"""

from unibot.computer.screen import active_window, capture, take_screenshot

__all__ = ["active_window", "capture", "take_screenshot"]
