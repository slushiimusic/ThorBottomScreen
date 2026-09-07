# Thor Bottom Screen

Turns the AYN Thor's bottom screen off automatically for the apps and emulators you choose,
and back on when you leave them. It costs nothing while idle and it does not fight the
hardware dual-screen button.

Built and verified against **AYN Thor, `Thor_V1.0.0.377`, Android 13 (SDK 33)**.

---

## What it does

Pick apps in the list. When one of them comes to the front on the main screen, the bottom
screen goes off. When you leave, it comes back.

Unticking an app always brings the bottom screen back immediately, even if "turn the bottom
screen back on when you leave" is switched off. That switch governs *leaving* a selected app,
not removing one from the list.

Two levels of "off", and two ways to get there. The app picks the best available on its own,
with no setting to choose:

| | What happens | Needs |
|---|---|---|
| **Vendor backend** | Exactly what the built-in dual-screen menu's "top display only" does: the vendor covers the bottom screen and disables touch on it. The menu stays in sync. | Shizuku |
| **Cover backend** | We put up our own black, touch-swallowing window on the bottom screen. Same end result, the vendor's own "off" *is* an overlay. | "Display over other apps" only |
| **Deep off** (opt-in) | Either of the above **plus** the panel genuinely powered down at the compositor. | Shizuku |

### Without Shizuku

Everything except deep off works with no Shizuku and no PC:

- **Turning the bottom screen off**: grant *Display over other apps*, and the cover backend
  takes over automatically whenever the shell helper is not connected.
- **Hiding the rotation-suggestion button**: this one is `Settings.Secure`, which is not
  key-restricted; it only wants `WRITE_SECURE_SETTINGS`. That can be granted **once** over adb
  and then survives reboots, which Shizuku does not:

  ```bash
  adb shell pm grant dev.slushii.thorbottom android.permission.WRITE_SECURE_SETTINGS
  ```

**Deep off cannot work without Shizuku**, and this is not a matter of asking nicely. It needs
`setprop display.power.state` (shell-only SELinux context) and
`SurfaceControl.setDisplayPowerMode` (`ACCESS_SURFACE_FLINGER`, signature-level, `pm grant`
refuses it outright).

What you give up on the cover backend:

- The vendor menu keeps reading "dual screen", because the vendor's state genuinely is dual.
  We are only covering it.
- No per-display confirmation. The display-0 check below needs the shell helper, so an app on
  the bottom screen can in principle be mistaken for the foreground app. Less likely here than
  on the vendor backend, since our cover is opaque and eats the touches that would provoke it.

## Cost

This was the whole design constraint, so to be precise about it:

- **Idle: nothing.** No polling, no timers, no alarms, no `WorkManager`, no foreground
  service, no notification, no wake locks, no overlay of our own, nothing drawn. The
  process sits at 0% CPU between events.
- **Per app switch: one string comparison.** The system wakes the accessibility service only
  when a window actually changes. If the comparison says the screen state is already right,
  that is the end of it.
- **Per actual switch: two calls.** One query for the real foreground app, one settings
  write. That happens a handful of times per session, not per frame.

The app requests only `typeWindowStateChanged`. It deliberately does **not** request
`canRetrieveWindowContent` or `flagRetrieveInteractiveWindows`, those are the expensive
accessibility flags, and the app never reads screen content.

---

## Setup

Minimum, no PC and nothing to restart after a reboot:

1. Open Thor Bottom Screen, tap **Allow display over other apps**.
2. Tap **Open Accessibility settings** and enable *Thor Bottom Screen*.
3. Tick the apps you want. **Select emulators** ticks the obvious ones for you.

For the vendor backend and deep off, additionally install **Shizuku**, start it, and tap
**Connect Shizuku**. Shizuku has to be restarted after every reboot on an unrooted device,
which is doable from the Thor itself over wireless debugging (no PC after the first pairing).
If you skip it, or simply do not restart it after a reboot, the app falls back to the cover
backend on its own.

---

## Why the vendor backend needs Shizuku

This is not a design choice, it is a platform wall.

The Thor's screen mode lives in `Settings.System/dual_screen_display_mode`. The vendor app
`com.odin.dualscreen.assistant` (which runs as `android.uid.system`) watches that key with a
`ContentObserver` and does the actual work, so writing the key is the entire switch.

A sideloaded app cannot write it. AOSP's `SettingsProvider` rejects any `Settings.System`
key that is not in its own `PUBLIC_SETTINGS` / `PRIVATE_SETTINGS` lists:

```
java.lang.IllegalArgumentException: You cannot keep your settings in the secure settings.
```

The check is on the **calling uid**, not on a permission. System, shell and root are exempt
and nothing else is. Measured on this device:

| Attempt | Result |
|---|---|
| `WRITE_SETTINGS` granted (appop allowed) | rejected |
| `WRITE_SECURE_SETTINGS` also granted via `pm grant` | **still rejected** |
| Same write from `adb shell` (uid 2000) | works |

The vendor app exposes no way in either: `TccServices` is `exported="false"` and its custom
permission is `signature`.

So the app borrows the shell identity through a Shizuku user service ([`ShellService`](app/src/main/java/dev/slushii/thorbottom/ShellService.java))
and does the write from there, or, with no helper, skips the vendor's switch entirely and
puts up its own cover ([`ScreenCover`](app/src/main/java/dev/slushii/thorbottom/ScreenCover.java)).

---

## The vendor's "off" is a cover, not a power-off

Worth knowing, because it is the reason deep off exists.

Decompiling `DualScreenAssistant.apk` shows `ScreenUtil.closeSecondaryScreen` does two things:

1. `windowManager.addView(...)` adds an opaque window titled `primaryScreenTopLayout` on
   display 4, and
2. sets the system property `sys.temp.disabled.touch` to `0,1` to kill touch there.

The panel stays powered at 120 Hz and whatever is behind the cover keeps rendering. So the
stock button does not save what you would assume it saves.

### Deep off: how the block was lifted

A plain `SurfaceControl.setDisplayPowerMode(bottomPanel, OFF)` from shell, which *does*
hold `ACCESS_SURFACE_FLINGER`, silently did nothing. SurfaceFlinger's own log said why:

```
D SurfaceFlinger: Setting power mode 0 on display 4630946482288158084
D SurfaceFlinger: disable Screen-2 off cause it should be turn on
```

AYN patched SurfaceFlinger to refuse it. Disassembling `/system/bin/surfaceflinger`
(the guard is at `0x1bfb28`) shows the condition is:

```c
if (mode == POWER_MODE_OFF && display != primary
        && property_get_int32("display.power.state", -1) == 1) {
    ALOGD("disable Screen-2 off cause it should be turn on");
    return;                     // refused
}
```

It is gated on an ordinary system property, which the shell user may set. Deep off drops
`display.power.state` to `0` for the duration of the call and puts it straight back, the
guard is only consulted while the power mode is being applied, so the panel stays off
afterwards. Powering back **on** is never gated.

Verified: `powerMode=Off` on display `4630946482288158084` while `display.power.state` reads
its normal `1`.

---

## Living with the hardware button

The dual-screen button keeps working, and its menu stays truthful, because the app drives the
vendor's own setting rather than inventing a parallel state. Three specific things make that
hold:

- **The menu stays in sync.** `ScreenModeControlPanel` observes the same key the app writes.
- **The app yields to you.** Any change to the mode that the app did not make itself is
  treated as a manual override: it stands down for as long as you stay in that app. So if you
  press the button and choose "dual screen" inside a game, it stays dual. Leaving and
  re-entering the app resumes automation. (Toggleable.)
- **Opening the menu does not look like leaving the game.** The menu is a window owned by
  `com.odin.dualscreen.assistant`, so its events are ignored rather than read as an app switch.

Under **deep off** there is one extra wrinkle: the vendor draws its menu *on the bottom
screen*, which is powered down. The app watches for that window appearing and powers the
panel back on so you can see the menu. The vendor's cover window fires an event from the same
package a millisecond or two after our own write, so events within 3 s of our own change are
ignored as an echo. A human pressing the button is always seconds away, never milliseconds.

Confirmed on device: with deep off active inside a game, pressing the dual-screen button
brings the panel back and shows the menu normally.

If the menu ever fails to appear, a screen off/on always brings the panel back.

---

## Two failure modes that were designed around

**The bottom screen has its own activity stack.** Display 4 runs real activities (Daijishō
sits there). A window event from an app down there otherwise reads as "the user left their
game", and the bottom screen would flick back on mid-session. Accessibility events do not
carry a display id, and the flag that would let us read window info is exactly the expensive
one being avoided, so before the app actually flips anything it asks the system which
package is really on top of display 0. Confirmed catching it in practice:

```
event said com.magneticchen.daijishou but display 0 holds com.github.stenzek.duckstation
```

**Waking the device resets panel power.** DisplayManager re-asserts power on both panels as
part of its wake sequence, and it finishes *after* `ACTION_SCREEN_ON` is broadcast, so
re-applying immediately gets undone. Deep off re-applies once, 1.5 s after the screen comes
on. This also means you can never be stranded with a dead bottom screen: a screen off/on
always brings it back.

---

## Layout

| File | Role |
|---|---|
| `AppMonitorService` | The whole runtime. Accessibility events in, decisions out. |
| `ScreenModeController` | Vendor key semantics: `0` dual, `1` top only, `2` bottom only. |
| `ShellService` | Runs as shell via Shizuku. The settings write and the panel power-off. |
| `ScreenCover` | The no-Shizuku fallback: our own black window on the bottom screen. |
| `RotationSuggestions` | Hides SystemUI's rotate button via `Settings.Secure`. |
| `ShizukuManager` | Owns the helper connection. |
| `MainActivity` | App picker and setup. |
| `Prefs` | SharedPreferences. |

## Build

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ANDROID_HOME=~/Library/Android/sdk ./gradlew assembleDebug
```

No AndroidX UI stack, no Compose, plain framework views. The only dependency is the Shizuku
API. The APK is about 200 KB.

## Limits

- **Thor-specific.** It keys on a vendor setting and this panel's physical display id
  (`4630946482288158084`). Other AYN dual-screen devices may work; nothing else will. The app
  says so if the key is missing.
- **Firmware-specific.** The SurfaceFlinger guard and the property gating it could change in
  a firmware update. If deep off stops working, check `logcat -s SurfaceFlinger` for the
  `disable Screen-2 off` line.
- **Not measured in mA.** The panel is genuinely off; that is established. How much battery
  that actually saves was not measured, because the device was on USB throughout (a full
  battery reports `current_now = 0`). Measuring it needs a run on battery over wireless adb.
