import io
p = r"app\src\main\java\org\music\infiniteplayer\playback\PlaybackService.kt"
raw = open(p, "rb").read()
try:
    txt = raw.decode("utf-8")
    print("UTF-8 OK, lines:", txt.count("\n"))
except UnicodeDecodeError as e:
    print("BAD UTF-8:", e)
    txt = raw.decode("utf-8", "replace")
lines = txt.split("\n")
for i, l in enumerate(lines, 1):
    if "findNearestOrderIndex" in l or "anchor" in l or "무한재생" in l or "고르므로" in l:
        print(i, "|", l)
