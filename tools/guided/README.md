# Guided classes: writing cue sheets

A guided class is a YouTube instructor ride with a cue sheet: the cadence and resistance
targets the instructor calls, timed to the video. The app reads them from
`app/src/main/assets/guided/catalog.json`, the copy in the APK, and refreshes that file from
`main` on GitHub. A sheet pushed to `main` reaches the bike the next time the Classes tab
loads. Changing the app itself still needs a new build.

## Making one

1. Get the class's captions and details. `en-orig` is YouTube's own automatic transcript:

   ```sh
   yt-dlp --skip-download --no-simulate --write-auto-subs --sub-langs en-orig --sub-format vtt \
     -o "%(id)s.%(ext)s" --print "%(id)s|%(duration)s|%(timestamp)s|%(channel)s|%(title)s" \
     "https://www.youtube.com/watch?v=VIDEO_ID"
   python3 tools/guided/vtt_lines.py VIDEO_ID.en-orig.vtt > VIDEO_ID.txt
   ```

   If YouTube answers 429, wait a minute and try again. A members-only class can't be used:
   yt-dlp says so, and the app couldn't play it either.
2. Read the transcript and write the class into `catalog.json`, following the rules below.
3. Run `./gradlew :app:testDebugUnitTest --tests '*GuidedCatalogTest*'`. It checks every sheet
   for order, ranges and timing.

## The class entry

```json
{
  "id": "q5ZjSzl6sL0",
  "title": "30 min CLASSIC Indoor Cycling Workout",
  "instructor": "Kaleigh Cohen Cycling",
  "durationSec": 1920,
  "type": "Classic",
  "publishedEpochMs": 1793005224000,
  "cues": [ { "t": 35, "label": "Warm up", "cadence": [80, 90], "resistance": [35, 45] } ]
}
```

- `instructor` is the channel name exactly as in `ChannelConfig.kt`, so it matches that
  instructor's shelf.
- `type` is one word or two: `Classic`, `HIIT`, `Climb`, `Low impact`, `Intervals`, `Rhythm`
  or `Endurance`.
- `publishedEpochMs` is yt-dlp's `timestamp` times 1000.

## Cues

- `t` is seconds into the video, when the instructor *starts* the segment. Captions lag the
  voice by a second or two, so use the caption's time as is. Cues must be in time order.
- One cue per real change: a new segment, effort level or cadence. Don't add a cue for
  every pep talk. A 30-minute class usually needs 25–45 cues.
- `label` is 28 characters at most (the ride's top bar shows the current and next one) and in
  the Peloton app's style: `Warm up`, `Flat road`, `Speed push`,
  `Sprint`, `Recover`, `Climb`, `Standing climb`, `Jumps`, `Tabata`, `Intervals`, `Heavy hold`,
  `Final push` or `Cool down`. Add the instructor's own word for a move if they have one.
- Leave out `cadence` or `resistance` when the instructor gives no direction for it. Talk
  before the ride, and the stretch at the end, get a cue with no targets at all, e.g.
  `{ "t": 0, "label": "Get ready" }`.
- The first cue should be `t: 0`, so the screen never shows an old target.

### Resistance: effort to the bike's 0–100

Instructors call effort, not Peloton numbers. Map their 1–10 to a 10-point band:

| Effort | Resistance | Feels like |
| --- | --- | --- |
| 1–3 | 20–30 | recovery, spinning out the legs |
| 4 | 25–35 | easy, warm-up |
| 5 | 30–40 | flat road |
| 6 | 35–45 | moderate, sprint load |
| 7 | 40–50 | working, sprint load |
| 8 | 45–55 | hard, rolling hill |
| 9 | 50–60 | climb |
| 10 | 55–65 | heavy climb, standing |

With no number given, go by the segment: recovery 25–35, flat 30–40, sprint 35–50, climb
50–60, standing climb 55–65, cool-down 20–30. "Add one", "a quarter turn" and "add two" move
the band up one or two efforts from wherever it was.

### Cadence (rpm)

Use the instructor's number when they say one ("90", "find 80s", "110"), as a band of about
10 around it. Otherwise go by the segment:

| Segment | Cadence |
| --- | --- |
| Warm up, flat road | 80–90 |
| Speed push, intervals | 90–105 |
| Sprint | 100–120 |
| Seated climb | 65–80 |
| Standing climb ("third", "out of the saddle") | 60–75 |
| Jumps | 75–90 |
| Recover | 75–85 |
| Cool down | 70–85 |
