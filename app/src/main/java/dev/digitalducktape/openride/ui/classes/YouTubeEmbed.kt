package dev.digitalducktape.openride.ui.classes

import android.webkit.WebResourceResponse
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Builds the host page for the in-app class player (superseding the earlier intent-based
 * handoff): a full-viewport YouTube embed on the privacy-enhanced
 * `youtube-nocookie.com` domain, driven through YouTube's official IFrame Player API so the
 * app can keep the video in step with the ride:
 *
 * - `openridePause()` / `openrideResume()` (called from the WebView via `evaluateJavascript`)
 *   pause/resume playback when the *ride* pauses — whether from the Pause button or the
 *   freewheel auto-pause.
 * - When the player reaches ENDED, the page notifies the app through the injected
 *   `OpenRideBridge` JavaScript interface so the workout can finish automatically.
 * - Every half second it reports the playback position (`OpenRideBridge.onTime`), which a
 *   guided class's cue sheet is timed against.
 *
 * ToS constraints (PRD Non-Goal #5) still shape the parameters: YouTube's own player chrome
 * stays enabled (`"controls": 1`), playback control goes through the official player API
 * only, and nothing is ever downloaded or cached — the WebView streams from YouTube's player
 * exactly like a browser would.
 */
object YouTubeEmbed {

    /** Base URL handed to `WebView.loadDataWithBaseURL` so the embed runs in a real
     *  https origin (required for YouTube's player to allow playback). */
    const val BASE_URL = "https://www.youtube-nocookie.com"

    /** Name of the JavaScript interface the hosting WebView injects (see VideoRideScreen);
     *  the page calls `OpenRideBridge.onVideoEnded()` when playback completes. */
    const val BRIDGE_NAME = "OpenRideBridge"

    /** YouTube video ids are URL-safe by construction; anything else is rejected outright
     *  rather than escaped, since a non-matching id can only be a bug or an injection. */
    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{5,20}$")

    fun html(videoId: String): String {
        require(VIDEO_ID.matches(videoId)) { "Not a YouTube video id: $videoId" }
        return """
            <!doctype html>
            <html>
            <head>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
              html, body { margin: 0; height: 100%; background: #000; overflow: hidden; }
              #player { position: absolute; inset: 0; width: 100%; height: 100%; border: 0; }
            </style>
            </head>
            <body>
            <div id="player"></div>
            <script>
              var player = null;
              // The ride can pause before the player finishes bootstrapping; remember the
              // latest requested state and apply it in onReady.
              var wantPaused = false;
              var wantVolume = 100;

              function onYouTubeIframeAPIReady() {
                player = new YT.Player('player', {
                  host: '$BASE_URL',
                  videoId: '$videoId',
                  playerVars: { "autoplay": 1, "playsinline": 1, "controls": 1, "rel": 0, "modestbranding": 1 },
                  events: {
                    onReady: function (e) {
                      if (wantPaused) { e.target.pauseVideo(); } else { e.target.playVideo(); }
                      e.target.setVolume(wantVolume);
                      // Guided classes time their cues to the video, not the ride clock, so a
                      // scrub or a buffering stall keeps the targets in step.
                      setInterval(function () {
                        if (window.$BRIDGE_NAME) { window.$BRIDGE_NAME.onTime(e.target.getCurrentTime()); }
                      }, 500);
                    },
                    onStateChange: function (e) {
                      if (e.data === YT.PlayerState.ENDED && window.$BRIDGE_NAME) {
                        window.$BRIDGE_NAME.onVideoEnded();
                      }
                    }
                  }
                });
              }

              function openridePause() {
                wantPaused = true;
                if (player && player.pauseVideo) { player.pauseVideo(); }
              }

              function openrideResume() {
                wantPaused = false;
                if (player && player.playVideo) { player.playVideo(); }
              }

              // The class's own level (0-100) in the volume mixer, under the device volume.
              function openrideVolume(v) {
                wantVolume = v;
                if (player && player.setVolume) { player.setVolume(v); }
              }

              var tag = document.createElement('script');
              tag.src = 'https://www.youtube.com/iframe_api';
              document.body.appendChild(tag);
            </script>
            </body>
            </html>
        """.trimIndent()
    }

    /**
     * The bike's MT8173 decodes H.264 and VP9 in hardware but AV1 only in software, and YouTube
     * serves AV1 to any player that says it can play it. On the bike that took over half the CPU
     * (three libgav1 threads), dropped 82% of frames and made YouTube fall back to low quality
     * (upstream #73). This runs first in the embed page and says no to AV1, so YouTube picks a
     * codec the chip decodes itself.
     */
    internal const val CODEC_PATCH = """<script>(function () {
  var no = /av01/i;
  if (window.MediaSource && MediaSource.isTypeSupported) {
    var its = MediaSource.isTypeSupported.bind(MediaSource);
    MediaSource.isTypeSupported = function (t) { return no.test(t) ? false : its(t); };
  }
  var cpt = HTMLMediaElement.prototype.canPlayType;
  HTMLMediaElement.prototype.canPlayType = function (t) { return no.test(t) ? '' : cpt.call(this, t); };
  var mc = navigator.mediaCapabilities;
  if (mc && mc.decodingInfo) {
    var di = mc.decodingInfo.bind(mc);
    mc.decodingInfo = function (c) {
      var t = (c && c.video && c.video.contentType) || '';
      return no.test(t) ? Promise.resolve({ supported: false, smooth: false, powerEfficient: false }) : di(c);
    };
  }
})();</script>"""

    /** [CODEC_PATCH] at the very start of the page's head, ahead of the player's own scripts. */
    fun withCodecPatch(html: String): String {
        val head = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html) ?: return CODEC_PATCH + html
        return html.substring(0, head.range.last + 1) + CODEC_PATCH + html.substring(head.range.last + 1)
    }

    /**
     * Serves the player frame (`/embed/…`) with [CODEC_PATCH] in it, for the WebView's
     * `shouldInterceptRequest`. The frame comes from YouTube's origin, so the host page can't
     * reach into it in time; fetching it here is the one way in that works on the bike's old
     * WebView (91). Anything else, or a failed fetch, returns null: the WebView loads it as usual.
     */
    fun interceptEmbed(url: String, method: String, headers: Map<String, String>, userAgent: String): WebResourceResponse? {
        if (method != "GET" || !url.startsWith("$BASE_URL/embed/")) return null
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", userAgent)
            // Not Accept-Encoding: HttpURLConnection only unzips what it asked for itself.
            headers["Referer"]?.let { connection.setRequestProperty("Referer", it) }
            headers["Accept-Language"]?.let { connection.setRequestProperty("Accept-Language", it) }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val html = connection.inputStream.bufferedReader().use { it.readText() }
            WebResourceResponse("text/html", "utf-8", withCodecPatch(html).byteInputStream())
        } catch (e: IOException) {
            null
        }
    }

    private const val TIMEOUT_MS = 15_000
}
