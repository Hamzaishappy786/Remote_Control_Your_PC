#!/usr/bin/env python3
"""
WiFi Remote Control — PC Server
Listens on UDP port 5005 and executes mouse/keyboard commands sent from the Android app.

Setup:
    pip install pyautogui

Run:
    python server.py

FAILSAFE: slam the mouse into the top-left corner of your screen to abort instantly.
"""

import socket
import sys
import pyautogui

# ── Configuration ──────────────────────────────────────────────────────────────
HOST   = ""     # Empty string = listen on all network interfaces
PORT   = 5005
BUFLEN = 1024

# Zero pause between pyautogui calls — critical for low-latency feel
pyautogui.PAUSE    = 0.0
pyautogui.FAILSAFE = True   # Move mouse to top-left corner to kill the server

# ── Special key name → pyautogui key string mapping ───────────────────────────
SPECIAL = {
    "ENTER":       "enter",
    "BACKSPACE":   "backspace",
    "SPACE":       "space",
    "ESCAPE":      "escape",
    "ESC":         "escape",
    "TAB":         "tab",
    "ARROW_UP":    "up",
    "ARROW_DOWN":  "down",
    "ARROW_LEFT":  "left",
    "ARROW_RIGHT": "right",
    "DELETE":      "delete",
    "HOME":        "home",
    "END":         "end",
    "PAGEUP":      "pageup",
    "PAGEDOWN":    "pagedown",
    "WIN":         "win",
    "CAPSLOCK":    "capslock",
    "PRINTSCREEN": "printscreen",
    "F1":  "f1",  "F2":  "f2",  "F3":  "f3",  "F4":  "f4",
    "F5":  "f5",  "F6":  "f6",  "F7":  "f7",  "F8":  "f8",
    "F9":  "f9",  "F10": "f10", "F11": "f11", "F12": "f12",
}


def handle(data: str) -> None:
    """Parse one UDP command string and execute it."""
    parts = data.split(",")
    if not parts:
        return

    cmd = parts[0].upper()

    # ── MOVE,dx,dy ─────────────────────────────────────────────────────────────
    if cmd == "MOVE" and len(parts) == 3:
        try:
            dx = int(float(parts[1]))
            dy = int(float(parts[2]))
            pyautogui.moveRel(dx, dy, duration=0)
        except ValueError:
            pass

    # ── CLICK,L | CLICK,R | CLICK,M ────────────────────────────────────────────
    elif cmd == "CLICK" and len(parts) == 2:
        btn = parts[1].upper()
        if   btn == "L": pyautogui.click()
        elif btn == "R": pyautogui.rightClick()
        elif btn == "M": pyautogui.middleClick()

    # ── DBLCLICK ────────────────────────────────────────────────────────────────
    elif cmd == "DBLCLICK":
        pyautogui.doubleClick()

    # ── SCROLL,n  (positive = up, negative = down) ─────────────────────────────
    elif cmd == "SCROLL" and len(parts) == 2:
        try:
            pyautogui.scroll(int(parts[1]))
        except ValueError:
            pass

    # ── KEY,value ──────────────────────────────────────────────────────────────
    elif cmd == "KEY" and len(parts) >= 2:
        value = ",".join(parts[1:])  # re-join in case value itself had a comma

        pyautogui_key = SPECIAL.get(value.upper())
        if pyautogui_key:
            # Named special key
            pyautogui.press(pyautogui_key)

        elif len(value) == 1:
            if value == ' ':
                # Space must go through press() — write(' ') is unreliable on Windows
                pyautogui.press('space')
            else:
                try:
                    pyautogui.write(value, interval=0)
                except Exception:
                    try:
                        pyautogui.press(value)
                    except Exception:
                        pass

        else:
            # Unknown multi-char key name — attempt a direct press as a last resort
            try:
                pyautogui.press(value.lower())
            except Exception:
                pass

    # ── HOTKEY,key1,key2[,...] — multi-key combos e.g. alt+tab, win+d ────────────
    elif cmd == "HOTKEY" and len(parts) >= 3:
        keys = [SPECIAL.get(k.upper(), k.lower()) for k in parts[1:]]
        try:
            pyautogui.hotkey(*keys)
        except Exception:
            pass

    # ── MOUSEDOWN,L|R  /  MOUSEUP,L|R  (for drag-and-drop) ────────────────────
    elif cmd == "MOUSEDOWN" and len(parts) == 2:
        btn = "left" if parts[1].upper() == "L" else "right"
        pyautogui.mouseDown(button=btn)

    elif cmd == "MOUSEUP" and len(parts) == 2:
        btn = "left" if parts[1].upper() == "L" else "right"
        pyautogui.mouseUp(button=btn)


def main() -> None:
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.bind((HOST, PORT))
        print(f"[WiFi Remote] Listening on UDP :{PORT}")
        print(f"[WiFi Remote] FAILSAFE active  — move mouse to TOP-LEFT corner to stop")
        print(f"[WiFi Remote] Press Ctrl+C to stop manually\n")
        try:
            while True:
                raw, addr = sock.recvfrom(BUFLEN)
                data = raw.decode("utf-8", errors="ignore").strip()
                if data:
                    handle(data)
        except KeyboardInterrupt:
            print("\n[WiFi Remote] Stopped by user.")
        except pyautogui.FailSafeException:
            print("\n[WiFi Remote] FAILSAFE triggered — server stopped.")
            sys.exit(0)


if __name__ == "__main__":
    main()
