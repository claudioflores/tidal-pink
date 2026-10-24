# Tidal Pink

An add-on for Tidal's Android TV app: a small accessibility service that draws on top of Tidal and reacts to the
remote. Tidal itself is not modified, and the add-on does nothing without it. Made for a TCL 75QM751G running
Google TV 14, alongside the [CleanYourTV](https://github.com/Gathin23/cleanyourtv) launcher, but it does not
need that launcher.

It has no icon, so Google TV's app list does not show it. It appears as "Tidal Pink" under Settings > System >
Accessibility.

Tidal TV app only; every other app is left alone.

- Pink focus ring, shaped to each button (circles, pills, the quality and Lyrics focus frames painted pink), in
  the player, lyrics and playlists; no ring in the left menu or on playlist rows
- Pink progress marker while the bar is selected, following every frame
- The ring hides with the player's buttons (about 4.3 s after the last key, or on Back)
- OK on a hidden player shows the buttons without pausing (Tidal pauses, the service resumes at once)
- Hold Left jumps to the left menu

## TV profiles

Everything that depends on the TV lives in `app/src/main/assets/profiles/*.json`: the ring colour and
thickness, timings, the progress-marker sizes and each button's shape (insets from its view's bounds, in screen
pixels). Tidal's view ids and screens stay in the code, since they follow the Tidal version, not the TV.

At start the service picks the profile whose `match.device` or `match.model` equals the TV's `ro.product.device`
or `ro.product.model`. With no match it uses the first profile, its pixel values scaled from `measuredWidthPx`
to the screen's width, a starting point that still needs checking.

To add a TV, copy `tcl-75qm751g.json`, set `match` to the TV's values (`tools/tv.py --probe` prints them), and
re-measure on screenshots:

| Key | What to measure |
| --- | --- |
| `shapes[].insetPx` | `[left, top, right, bottom]` from the focused view's bounds to the visible button edge |
| `shapes[].cornerPx`, `strokePx` | the button's corner radius; a ring thickness for buttons with a focus band to cover |
| `shapes[].innerInsetPx`, `innerCornerPx` | `frame` shapes only: the hole inside the focus band |
| `thumb.insetPx`, `widthPx` | where the bar starts inside the SeekBar, and the width of Tidal's white marker |
| `timing.playerIdleMs` | how long after the last key the player's buttons start to fade |
| `timing.enterQuietMs`, `fromTidalMinMs` | screen slide-in time; how long a Tidal screen must be up to count as visited |

Kinds: `circle`, `pill`, `rounded`, `frame`. Screens: `player`, `lyrics`, `playlist`, `any`.

## Install

    TV_HOST=192.168.4.44 JAVA_HOME=/path/to/jdk17 ANDROID_HOME=/path/to/sdk ./install.sh

Network/USB debugging must be on while installing. Turn it off afterwards; the service keeps working.
Tested on the TCL 75QM751G on 23 October 2026: the service starts and the ring shows in Tidal.

On TCL TVs install.sh also gives the app TCL's AUTO_START permission; without it the firmware (TclAppBoot)
refuses to start the service ("forbid bind service" in logcat).

## Remove

    TV_HOST=192.168.4.44 tools/venv/bin/python -I tools/tv.py 'pm uninstall local.tidalpink'
