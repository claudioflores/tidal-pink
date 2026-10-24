#!/usr/bin/env bash
# Build the app, install it on the TV over network ADB and switch its accessibility service on, keeping any
# other services already on (the launcher's key helper).
# Usage: TV_HOST=192.168.4.44 ./install.sh      (needs JAVA_HOME = JDK 17 and ANDROID_HOME with platform 34)
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address}"
[ -x tools/venv/bin/python ] || { python3 -m venv tools/venv && tools/venv/bin/pip install -q -r tools/requirements.txt; }
PY=tools/venv/bin/python
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleRelease -q --console=plain
APK=app/build/outputs/apk/release/app-release.apk
"$PY" -I tools/b64push.py "$APK" /data/local/tmp/tidalpink.apk
OUT=$("$PY" -I tools/tv.py 'pm install -r /data/local/tmp/tidalpink.apk; rm -f /data/local/tmp/tidalpink.apk')
echo "$OUT"
case "$OUT" in *Success*) ;; *) echo "install failed" >&2; exit 1 ;; esac
# TCL firmware (TclAppBoot) refuses to start the service of an app that is not running, unless the app has
# TCL's AUTO_START app-op. Other TVs do not know the op; the error is ignored there.
"$PY" -I tools/tv.py 'appops set local.tidalpink AUTO_START allow 2>/dev/null; true'
# Switch the service on, keeping other services. If it was already listed (a reinstall), take it out and put it
# back so Android binds the new APK.
SVC=local.tidalpink/.FocusService
"$PY" -I tools/tv.py "s=\$(settings get secure enabled_accessibility_services); case \"\$s\" in null) s='' ;; esac; rest=\$(echo \"\$s\" | tr ':' '\\n' | grep -v '^$SVC\$' | grep -v '^\$' | paste -sd: -); settings put secure enabled_accessibility_services \"\$rest\"; sleep 1; if [ -n \"\$rest\" ]; then settings put secure enabled_accessibility_services \"\$rest:$SVC\"; else settings put secure enabled_accessibility_services $SVC; fi; settings put secure accessibility_enabled 1; settings get secure enabled_accessibility_services"
