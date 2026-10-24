# tidal-tv-focus

A small Android TV accessibility service (no icon, no screen) for a TCL 75QM751G running Google TV 14, built
alongside the [CleanYourTV](https://github.com/Gathin23/cleanyourtv) launcher.

- **Pink focus ring:** a thick electric-pink outline around whatever is selected in apps built from standard
  Android views (Settings, SmartTube, Tidal, VLC, Aerial Views). Apps that draw their own screens (HBO Max,
  Apple TV, Prime Video, Kodi) report no selection, so they get no ring. Skipped on the CleanYourTV home screen,
  which has its own highlight.
- **Tidal:**
  - rings shaped to each button (circles, pills, the quality and Lyrics focus frames painted pink), in the
    player, lyrics and playlists; no ring in the left menu or on playlist rows
  - pink progress marker while the bar is selected, following every frame
  - the ring hides with the player's buttons (about 4.3 s after the last key, or on Back)
  - OK on a hidden player shows the buttons without pausing (Tidal pauses, the service resumes at once)
  - Hold Left jumps to the left menu

Measurements (insets, sizes, timers) are in pixels on this TV's 1920x1080 screen; other TVs or Tidal
versions will need them redone.

## Install

    TV_HOST=192.168.4.44 JAVA_HOME=/path/to/jdk17 ANDROID_HOME=/path/to/sdk ./install.sh

Network/USB debugging must be on while installing. Turn it off afterwards; the service keeps working.

Known issue: on this TCL, the firmware's TclAppBoot blocks the service from starting ("forbid bind service")
because the app has no screen and has never been opened. Not fixed yet; the same code currently runs inside the
CleanYourTV launcher's key helper instead.

## Remove

    TV_HOST=192.168.4.44 tools/venv/bin/python -I tools/tv.py 'pm uninstall local.tvfocus'
