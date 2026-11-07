package dev.digitalducktape.openride.ui.ride

import android.annotation.SuppressLint
import android.media.AudioManager
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import dev.digitalducktape.openride.core.guided.Cue
import dev.digitalducktape.openride.core.guided.GuidedClass
import dev.digitalducktape.openride.ui.classes.YouTubeEmbed
import dev.digitalducktape.openride.ui.common.TimeFormat
import dev.digitalducktape.openride.ui.designsystem.OrColors
import dev.digitalducktape.openride.ui.designsystem.OrShapes
import dev.digitalducktape.openride.ui.designsystem.OrSpacing
import dev.digitalducktape.openride.ui.designsystem.OrType
import dev.digitalducktape.openride.ui.designsystem.component.OrAccentButton
import dev.digitalducktape.openride.ui.designsystem.component.OrConfirmDialog
import dev.digitalducktape.openride.ui.designsystem.component.OrEyebrow
import dev.digitalducktape.openride.ui.designsystem.component.OrSecondaryButton
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * The in-app class player (v2 spec, superseding the T10 YouTube-app handoff): a full-bleed
 * WebView streaming the class through YouTube's own embedded player, with live ride metrics
 * overlaid — timer and ride controls along the top scrim, [RideMetricsBar] along the bottom.
 *
 * The overlay is dismissible (tap the video, or bring it back via the "Metrics" pill) so
 * YouTube's native player controls are always reachable and never permanently covered —
 * part of the app's deliberate YouTube ToS stance.
 *
 * Ride state comes from the same [InRideViewModel] as the plain in-ride screen, and the
 * video tracks it: pausing the ride (Pause button or freewheel auto-pause) pauses the video
 * through the player API, resuming resumes it, and the video reaching its end finishes the
 * workout automatically.
 */
@Composable
fun VideoRideScreen(
    viewModel: InRideViewModel,
    videoId: String,
    onRideEnded: (rideId: Long) -> Unit,
    modifier: Modifier = Modifier,
    /** This class's cue sheet, when it's a guided class: drives the targets and segment line. */
    guidedClass: GuidedClass? = null,
) {
    val uiState by viewModel.uiState.collectAsState(initial = InRideUiState())
    val scope = rememberCoroutineScope()
    var overlayVisible by remember { mutableStateOf(true) }
    var showEndConfirmation by remember { mutableStateOf(false) }
    var videoSec by remember { mutableIntStateOf(0) }
    var classVolume by rememberSaveable { mutableIntStateOf(100) }
    var showMixer by remember { mutableStateOf(false) }
    val cue = guidedClass?.cueAt(videoSec)
    val nextCue = guidedClass?.nextCue(videoSec)

    // Shared by the End Ride dialog and the video-completion auto-end. endRide() returns
    // null if the ride is already over, so the two paths can't double-fire navigation.
    val endRideAndShowSummary: () -> Unit = {
        scope.launch {
            val ride = viewModel.endRide()
            if (ride != null) onRideEnded(ride.id)
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        ClassPlayer(
            videoId = videoId,
            paused = uiState.isPaused,
            onVideoEnded = endRideAndShowSummary,
            onTime = { videoSec = it },
            classVolume = classVolume,
            modifier = Modifier.fillMaxSize(),
        )

        if (overlayVisible) {
            // Full-screen tap catcher: any tap on the video area tucks the overlay away so
            // YouTube's own controls become reachable. Sits *under* the overlay rows so the
            // ride controls and metrics stay tappable themselves.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { overlayVisible = false },
            )

            TopScrimRow(
                uiState = uiState,
                segment = cue?.let { segmentLine(it.label, nextCue, videoSec) },
                onVolume = { showMixer = !showMixer },
                onPauseResume = { if (uiState.isPaused) viewModel.resume() else viewModel.pause() },
                onEndRide = { showEndConfirmation = true },
                modifier = Modifier.align(Alignment.TopCenter),
            )

            if (showMixer) {
                VolumeMixer(
                    classVolume = classVolume,
                    onClassVolume = { classVolume = it },
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 104.dp, end = OrSpacing.lg),
                )
            }

            RideMetricsBar(
                uiState = uiState,
                translucent = true,
                targets = cue?.let { RideTargets(cadence = it.cadenceRange, resistance = it.resistanceRange) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        } else {
            MetricsPill(
                onClick = { overlayVisible = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(OrSpacing.md),
            )
        }
    }

    if (showEndConfirmation) {
        OrConfirmDialog(
            title = "End ride?",
            text = "This will stop and save your ride.",
            confirmText = "End ride",
            // The rider's colour, not red (rider's ask, 2026-10-21).
            destructive = false,
            onConfirm = {
                showEndConfirmation = false
                endRideAndShowSummary()
            },
            onDismiss = { showEndConfirmation = false },
        )
    }
}

/**
 * The WebView hosting YouTube's embedded player. Created once per [videoId]. [paused]
 * mirrors the *ride's* paused state into the player (see [YouTubeEmbed] for the page-side
 * functions), and [onVideoEnded] fires — already on the UI thread — when playback completes.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ClassPlayer(
    videoId: String,
    paused: Boolean,
    onVideoEnded: () -> Unit,
    onTime: (videoSec: Int) -> Unit,
    classVolume: Int,
    modifier: Modifier = Modifier,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    val currentOnVideoEnded by rememberUpdatedState(onVideoEnded)
    val currentOnTime by rememberUpdatedState(onTime)

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setBackgroundColor(android.graphics.Color.BLACK)
                settings.javaScriptEnabled = true
                // The class should start without the rider reaching for the screen mid-mount.
                settings.mediaPlaybackRequiresUserGesture = false
                // Read on the UI thread: the interceptor below runs on a WebView worker thread.
                val userAgent = settings.userAgentString
                webViewClient = object : WebViewClient() {
                    // The player frame gets the bike's codec preference (YouTubeEmbed.CODEC_PATCH).
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                        YouTubeEmbed.interceptEmbed(request.url.toString(), request.method, request.requestHeaders, userAgent)
                }
                webChromeClient = WebChromeClient()
                addJavascriptInterface(
                    // JS-interface methods arrive on a WebView-internal thread; hop to the
                    // UI thread before touching Compose/navigation state.
                    PlayerBridge(
                        ended = { post { currentOnVideoEnded() } },
                        time = { sec -> post { currentOnTime(sec.toInt()) } },
                    ),
                    YouTubeEmbed.BRIDGE_NAME,
                )
                loadDataWithBaseURL(
                    YouTubeEmbed.BASE_URL,
                    YouTubeEmbed.html(videoId),
                    "text/html",
                    "utf-8",
                    null,
                )
                webView = this
            }
        },
    )

    LaunchedEffect(webView, paused) {
        webView?.evaluateJavascript(if (paused) "openridePause()" else "openrideResume()", null)
    }

    LaunchedEffect(webView, classVolume) {
        webView?.evaluateJavascript("openrideVolume($classVolume)", null)
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                // Stop audio immediately on navigation away (End Ride → summary) rather
                // than whenever the view happens to be garbage collected.
                loadUrl("about:blank")
                destroy()
            }
        }
    }
}

/** Page → app callback surface injected as `window.OpenRideBridge` (see [YouTubeEmbed]). */
private class PlayerBridge(private val ended: () -> Unit, private val time: (Double) -> Unit) {
    @JavascriptInterface
    fun onVideoEnded() = ended()

    @JavascriptInterface
    fun onTime(videoSec: Double) = time(videoSec)
}

/** "CLIMB · Sprint in 0:12": the segment now, and what's next with its countdown. */
internal fun segmentLine(label: String, next: Cue?, videoSec: Int): String =
    if (next == null) label else "$label · ${next.label} in ${TimeFormat.elapsed(next.t - videoSec)}"

@Composable
private fun TopScrimRow(
    uiState: InRideUiState,
    segment: String?,
    onVolume: () -> Unit,
    onPauseResume: () -> Unit,
    onEndRide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // Goal progress runs across the very top edge, the way the class player runs the
        // class timeline there.
        uiState.goalProgress?.let { GoalHairline(it) }
        TopStripContent(
            uiState = uiState,
            segment = segment,
            onVolume = onVolume,
            onPauseResume = onPauseResume,
            onEndRide = onEndRide,
        )
    }
}

@Composable
private fun TopStripContent(
    uiState: InRideUiState,
    segment: String?,
    onVolume: () -> Unit,
    onPauseResume: () -> Unit,
    onEndRide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(0f to VideoScrimTop, VideoScrimHoldEnd to VideoScrimTop, 1f to Color.Transparent),
            )
            .padding(horizontal = OrSpacing.lg, vertical = OrSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                text = TimeFormat.elapsed(uiState.elapsedSec),
                style = OrType.MetricTimerCompact,
                color = OrColors.OnBackground,
            )
            OrEyebrow("Elapsed")
        }

        val statusText = when {
            !uiState.sensorsAvailable -> "Sensors not detected"
            uiState.autoPaused -> "Auto-paused. Pedal to resume"
            uiState.isPaused -> "Ride paused"
            // A guided class's segment, when nothing needs the rider's attention first.
            else -> segment
        }
        if (statusText != null) {
            Text(
                text = statusText,
                style = OrType.Subtitle,
                color = if (uiState.sensorsAvailable) OrColors.OnBackground else OrColors.Warning,
                maxLines = 1,
                // A guided segment line can run long: it gives way to the ride buttons.
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).padding(horizontal = OrSpacing.md),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(OrSpacing.md)) {
            OrSecondaryButton(
                text = "Volume",
                onClick = onVolume,
                modifier = Modifier.background(OrColors.SurfaceRaised, OrShapes.Small),
            )
            // Raised fill behind the outlined button: it sits on video.
            OrSecondaryButton(
                text = if (uiState.isPaused) "Resume" else "Pause",
                onClick = onPauseResume,
                modifier = Modifier.background(OrColors.SurfaceRaised, OrShapes.Small),
            )
            OrAccentButton(text = "End ride", onClick = onEndRide)
        }
    }
}

/**
 * The strip over video holds [VideoScrimTop] down past the ELAPSED label before it fades, so the
 * label stays readable over bright cloud (bike test, 2026-10-27).
 */
private val VideoScrimTop = Color(0xCC000000)
private const val VideoScrimHoldEnd = 0.8f

/** Brings the overlay back once it's been tucked away. Raised fill: it sits on video. */
@Composable
private fun MetricsPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    OrSecondaryButton(
        text = "Metrics",
        onClick = onClick,
        modifier = modifier.background(OrColors.SurfaceRaised, OrShapes.Small),
    )
}

/**
 * The volume mixer: the class video's own level, over the device's media volume. Android has no
 * per-app volume for other apps, and Spotify, Netflix and the class all play through the device
 * volume, so "music against the instructor" is the class bar against the device bar.
 */
@Composable
private fun VolumeMixer(classVolume: Int, onClassVolume: (Int) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    val max = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    // Read when the panel opens, so the bike's own volume buttons are reflected here.
    var device by remember { mutableIntStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC)) }

    Column(
        modifier = modifier
            .width(MixerWidth)
            .background(OrColors.SurfaceRaised, OrShapes.Small)
            .padding(OrSpacing.md),
    ) {
        OrEyebrow("Class")
        Slider(value = classVolume / 100f, onValueChange = { onClassVolume((it * 100).roundToInt()) })
        OrEyebrow("Music and apps (Spotify, Netflix, all sound)")
        Slider(
            value = device.toFloat() / max,
            onValueChange = {
                device = (it * max).roundToInt()
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, device, 0)
            },
        )
    }
}

private val MixerWidth = 420.dp
