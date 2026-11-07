"""YouTube auto-captions (.vtt) to de-duplicated "m:ss text" lines, for writing a cue sheet.

Usage: vtt_lines.py VIDEO_ID.en-orig.vtt [--cues]   (--cues keeps only lines that sound like a cue)
"""
import re, sys
TS = re.compile(r"^(\d+):(\d\d):(\d\d)\.\d+ -->")
KEY = re.compile(r"\b(\d{2,3}|rpm|cadence|resistance|turn|knob|climb|sprint|recover|recovery|jog|run|flat road|hill|standing|stand|seated|sit|tabata|interval|push|out of the saddle|second|seconds|minute|minutes|warm|cool)\b", re.I)
def lines(path):
    t, seen, out = 0, set(), []
    for raw in open(path, encoding="utf-8"):
        m = TS.match(raw)
        if m:
            h, mi, s = map(int, m.groups()); t = h*3600 + mi*60 + s; continue
        txt = re.sub(r"<[^>]+>", "", raw).strip()
        if not txt or txt.startswith(("WEBVTT", "Kind:", "Language:")) or txt in seen: continue
        seen.add(txt); out.append((t, txt))
    return out
if __name__ == "__main__":
    cues_only = "--cues" in sys.argv
    for t, txt in lines(sys.argv[1]):
        if not cues_only or KEY.search(txt):
            print(f"{t//60}:{t%60:02d} {txt}")
