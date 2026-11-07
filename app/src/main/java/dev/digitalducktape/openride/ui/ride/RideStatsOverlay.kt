package dev.digitalducktape.openride.ui.ride

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.digitalducktape.openride.ui.common.TimeFormat
import dev.digitalducktape.openride.ui.designsystem.OrColors
import dev.digitalducktape.openride.ui.designsystem.OrShapes
import dev.digitalducktape.openride.ui.designsystem.OrSpacing
import dev.digitalducktape.openride.ui.designsystem.OrType
import dev.digitalducktape.openride.ui.designsystem.component.OrEyebrow
import dev.digitalducktape.openride.ui.theme.OpenRideTheme
import kotlinx.coroutines.flow.Flow

/**
 * The ride's live numbers in a small window over another app (Netflix, say), so a rider can
 * watch a show full screen and still see the ride. Drag the window to move it; drag the corner
 * grip to resize it. ✕ goes back to OpenRide, which also hides the window.
 *
 * It's an app overlay ("display over other apps"). Android keeps a process with a visible
 * overlay running, so the ride keeps recording while the other app is in front.
 */
object RideStatsOverlay {
    const val NETFLIX = "com.netflix.mediaclient"

    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null

    fun canShow(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** Android's own switch for "display over other apps", for when [canShow] is false. */
    fun permissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    @SuppressLint("ClickableViewAccessibility")
    fun show(context: Context, uiState: Flow<InRideUiState>, onReturn: () -> Unit) {
        if (view != null || !canShow(context)) return
        val app = context.applicationContext
        val wm = app.getSystemService(WindowManager::class.java)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Not focusable: keys and touches outside the window go to the app underneath.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = START_OFFSET_PX
            y = START_OFFSET_PX
        }
        val dragFrom = IntArray(2)
        val windowOwner = OverlayOwner()
        val composeView = ComposeView(app).apply {
            setViewTreeLifecycleOwner(windowOwner)
            setViewTreeSavedStateRegistryOwner(windowOwner)
        }
        composeView.setContent {
            OpenRideTheme {
                StatsWindow(
                    uiState = uiState,
                    onDrag = { dx, dy, start ->
                        if (start) {
                            dragFrom[0] = params.x
                            dragFrom[1] = params.y
                        }
                        params.x = dragFrom[0] + dx
                        params.y = dragFrom[1] + dy
                        wm.updateViewLayout(composeView, params)
                    },
                    onSize = { w, h ->
                        if (params.width != w || params.height != h) {
                            params.width = w
                            params.height = h
                            wm.updateViewLayout(composeView, params)
                        }
                    },
                    onReturn = {
                        hide()
                        onReturn()
                    },
                )
            }
        }
        wm.addView(composeView, params)
        view = composeView
        owner = windowOwner
    }

    fun hide() {
        val v = view ?: return
        v.context.getSystemService(WindowManager::class.java).removeView(v)
        owner?.destroy()
        view = null
        owner = null
    }

    private const val START_OFFSET_PX = 48
}

/** The window's content at scale 1; the corner grip scales all of it, text and spacing alike. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun StatsWindow(
    uiState: Flow<InRideUiState>,
    /** Offset from where the drag began; `start` on the touch that begins it. */
    onDrag: (dx: Int, dy: Int, start: Boolean) -> Unit,
    onSize: (Int, Int) -> Unit,
    onReturn: () -> Unit,
) {
    val state by uiState.collectAsState(initial = InRideUiState())
    var scale by remember { mutableFloatStateOf(1f) }
    val base = LocalDensity.current
    // Both drags measure from where the finger went down, in raw screen coordinates: the window
    // moves (or grows) under the finger, so a position inside it would chase its own tail, and
    // stepwise deltas over-counted on the bike (a 300 px drag hit the 2.5x cap).
    val down = remember { FloatArray(3) } // raw x, raw y, scale at touch-down

    CompositionLocalProvider(LocalDensity provides Density(base.density * scale, base.fontScale)) {
        // Laid out at its own size, not the window's: Android caps a wrap-content window at the
        // dialog width (580 dp), which clipped a resized window. The window then takes this size.
        Box(
            modifier = Modifier
                .wrapContentSize(align = Alignment.TopStart, unbounded = true)
                .onSizeChanged { onSize(it.width, it.height) }
                .background(OrColors.Background.copy(alpha = 0.88f), OrShapes.Small),
        ) {
            // The stats are the handle for moving the window.
            Column(
                modifier = Modifier
                    .padding(OrSpacing.md)
                    .padding(end = OrSpacing.lg)
                    .pointerInteropFilter { e ->
                        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                            down[0] = e.rawX
                            down[1] = e.rawY
                        }
                        onDrag(
                            (e.rawX - down[0]).toInt(),
                            (e.rawY - down[1]).toInt(),
                            e.actionMasked == MotionEvent.ACTION_DOWN,
                        )
                        true
                    },
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(OrSpacing.lg), verticalAlignment = Alignment.Bottom) {
                    val live = state.sensorsAvailable
                    Stat("TIME", TimeFormat.elapsed(state.elapsedSec))
                    Stat("CADENCE", if (live) "${state.metrics.cadenceRpm}" else "--")
                    Stat("OUTPUT", if (live) "${state.metrics.powerWatts}" else "--")
                    Stat("RESISTANCE", if (live) "${state.metrics.resistancePercent}" else "--")
                    if (state.heartRateConnected) Stat("HEART", "${state.heartRateBpm ?: "--"}")
                }
                if (state.isPaused || state.autoPaused) {
                    OrEyebrow(
                        if (state.autoPaused) "Auto-paused. Pedal to resume" else "Ride paused",
                        color = OrColors.Warning,
                    )
                }
            }
            // Back to OpenRide. Drawn after the stats, so a tap here is the button's alone.
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Back to OpenRide",
                tint = OrColors.OnBackground,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clickable(onClick = onReturn)
                    .padding(OrSpacing.xs)
                    .size(28.dp),
            )
            // Resize grip: dragging right grows the window, dragging left shrinks it.
            Canvas(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(32.dp)
                    .pointerInteropFilter { e ->
                        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                            down[0] = e.rawX
                            down[2] = scale
                        }
                        val grow = (e.rawX - down[0]) / (GRIP_DP_PER_SCALE * base.density)
                        scale = (down[2] + grow).coerceIn(MIN_SCALE, MAX_SCALE)
                        true
                    },
            ) {
                // Three diagonal strokes in the corner, the usual resize grip.
                for (i in 1..3) {
                    val inset = size.width * i / 4
                    drawLine(OrColors.OnSurfaceMuted, Offset(inset, size.height), Offset(size.width, inset), strokeWidth = 2.dp.toPx())
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        OrEyebrow(label)
        Text(text = value, style = OrType.MetricSecondary, color = OrColors.OnBackground, maxLines = 1)
    }
}

// ponytail: scale limits picked for the bike's 1920x1080 screen; widen if a rider wants more.
private const val MIN_SCALE = 0.6f
private const val MAX_SCALE = 2.5f

/** Dragging the grip this many dp changes the scale by 1. */
private const val GRIP_DP_PER_SCALE = 400f

/**
 * Compose needs a lifecycle and saved-state owner on any window it draws in. An overlay has
 * no activity, so this one stays resumed while the window is up; an activity's would stop
 * whenever Netflix is in front, and stop the numbers with it.
 */
private class OverlayOwner : SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry get() = savedState.savedStateRegistry

    init {
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
