#!/bin/bash
# Drives the EMULATOR screen by text, for checking screens from a headless box. Never the phone.
# Usage: tools/ui.sh dump | tap "Text" | pin 123456 | shot name | type "text". Files go to $NWCRING_UI_DIR (default /tmp/nwcring-ui).
# Drive the emulator's screen by text. Usage: ui.sh tap "Text" | ui.sh dump | ui.sh shot name | ui.sh type "text"
A=~/android-sdk/platform-tools/adb
E="-s emulator-5554"
S=${NWCRING_UI_DIR:-/tmp/nwcring-ui}; mkdir -p $S
case "$1" in
  dump)
    $A $E shell uiautomator dump /sdcard/ui.xml >/dev/null && $A $E pull /sdcard/ui.xml $S/ui.xml >/dev/null 2>&1
    python3 - <<'PY'
import re
s=open("'"$S"'/ui.xml").read()
for m in re.finditer(r'<node[^>]*?text="([^"]*)"[^>]*?content-desc="([^"]*)"[^>]*?bounds="(\[[^"]*\])"',s):
    t,d,b=m.groups()
    if t or d: print(repr(t or d), b)
PY
    ;;
  tap)
    $A $E shell uiautomator dump /sdcard/ui.xml >/dev/null && $A $E pull /sdcard/ui.xml $S/ui.xml >/dev/null 2>&1
    XY=$(python3 - "$2" <<'PY'
import re,sys
want=sys.argv[1]
s=open("'"$S"'/ui.xml").read()
nodes=[m.groups() for m in re.finditer(r'<node[^>]*?text="([^"]*)"[^>]*?content-desc="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',s)]
hit=next((n for n in nodes if want in (n[0],n[1])), None) or next((n for n in nodes if want in n[0] and len(n[0])<len(want)+20), None)
if hit:
    t,d,x1,y1,x2,y2=hit; print((int(x1)+int(x2))//2,(int(y1)+int(y2))//2)
else: print("NOTFOUND")
PY
)
    if [ "$XY" = "NOTFOUND" ]; then echo "not found: $2"; exit 1; fi
    $A $E shell input tap $XY; sleep 1.2; echo "tapped $2 at $XY"
    ;;
  shot) $A $E exec-out screencap -p > $S/shot-$2.png; echo "$S/shot-$2.png";;
  type) $A $E shell input text "$2"; sleep 0.5;;
  pin) for d in $(echo "$2" | sed 's/./& /g'); do $A $E shell input tap $($A $E shell uiautomator dump /sdcard/ui.xml >/dev/null; $A $E pull /sdcard/ui.xml $S/ui.xml >/dev/null 2>&1; python3 -c "
import re;s=open('$S/ui.xml').read()
for m in re.finditer(r'<node[^>]*?text=\"$d\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"',s):
    x1,y1,x2,y2=map(int,m.groups()); print((x1+x2)//2,(y1+y2)//2); break"); sleep 0.4; done; echo "typed pin";;
esac
