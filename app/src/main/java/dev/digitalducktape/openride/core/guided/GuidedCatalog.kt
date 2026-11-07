package dev.digitalducktape.openride.core.guided

import dev.digitalducktape.openride.core.content.ChannelSection
import dev.digitalducktape.openride.core.content.ContentCategory
import dev.digitalducktape.openride.core.content.FeedFetcher
import dev.digitalducktape.openride.core.content.HttpUrlFeedFetcher
import dev.digitalducktape.openride.core.content.Video
import dev.digitalducktape.openride.core.content.fetchText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Guided classes: instructor rides from YouTube with a cue sheet, i.e. the cadence and resistance
 * targets the instructor calls, timed to the video, the way the Peloton app shows them.
 *
 * YouTube instructors call effort (1-10), the odd cadence and segment types ("climb", "sprint")
 * rather than Peloton resistance numbers, so each sheet's ranges are that intent put on the bike's
 * 0-100 resistance scale when the sheet is written (see tools/guided/README.md), not read off the
 * video at ride time.
 */
@Serializable
data class GuidedCatalog(val version: Int = 1, val classes: List<GuidedClass> = emptyList()) {

    fun classFor(videoId: String): GuidedClass? = classes.firstOrNull { it.id == videoId }

    /** The "Guided classes" shelf at the top of the Classes tab, or `null` when there are none. */
    fun shelf(): ChannelSection? = classes.takeIf { it.isNotEmpty() }?.let { guided ->
        ChannelSection(
            sourceId = SHELF_SOURCE_ID,
            channelId = "guided",
            channelName = "Guided classes",
            category = ContentCategory.Workout,
            videos = guided.map { it.toVideo() },
            refreshFailed = false,
        )
    }

    companion object {
        /** Not a `content_sources` row: the shelf has no creator page to open. */
        const val SHELF_SOURCE_ID = -1L

        private val json = Json { ignoreUnknownKeys = true }
        private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{5,20}$")

        /**
         * Parses a catalog, dropping any class the ride screen couldn't use: an id the embed
         * would refuse, or cues out of time order ([GuidedClass.cueAt] relies on the order).
         * The catalog arrives over the network, so it's checked rather than trusted.
         */
        fun parse(text: String): GuidedCatalog {
            val catalog = json.decodeFromString(serializer(), text)
            return catalog.copy(
                classes = catalog.classes.filter { c ->
                    VIDEO_ID.matches(c.id) && c.cues.zipWithNext().all { (a, b) -> a.t < b.t }
                },
            )
        }
    }
}

@Serializable
data class GuidedClass(
    val id: String,
    val title: String,
    val instructor: String,
    val durationSec: Int,
    /** "HIIT", "Climb", "Classic", "Low impact"... shown on the class card. */
    val type: String,
    val publishedEpochMs: Long = 0,
    val cues: List<Cue>,
) {
    /** The cue in force [videoSec] seconds into the video: the last one starting at or before it. */
    fun cueAt(videoSec: Int): Cue? = cues.lastOrNull { it.t <= videoSec }

    fun nextCue(videoSec: Int): Cue? = cues.firstOrNull { it.t > videoSec }

    fun toVideo(): Video = Video(
        id = id,
        title = title,
        thumbnailUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
        channelName = instructor,
        durationSec = durationSec,
        publishedEpochMs = publishedEpochMs,
        guided = true,
    )
}

/**
 * One segment, from [t] seconds into the video until the next cue. [cadence] (rpm) and
 * [resistance] (the bike's 0-100) are `[low, high]`; either may be absent, e.g. a cool-down
 * with no resistance call.
 */
@Serializable
data class Cue(
    val t: Int,
    val label: String,
    val cadence: List<Int>? = null,
    val resistance: List<Int>? = null,
) {
    val cadenceRange: IntRange? get() = cadence.toRange()
    val resistanceRange: IntRange? get() = resistance.toRange()
}

private fun List<Int>?.toRange(): IntRange? =
    if (this != null && size == 2 && this[0] <= this[1]) this[0]..this[1] else null

/**
 * The catalog the app rides with: the copy bundled in the APK until [refresh] fetches the one on
 * GitHub, so new cue sheets reach the bike without a new build. Offline, the bundled copy stays;
 * classes need the network to play anyway, so there's no on-device cache.
 */
class GuidedRepository(
    bundled: () -> String,
    private val fetcher: FeedFetcher = HttpUrlFeedFetcher(),
    private val url: String = CATALOG_URL,
) {
    @Volatile
    var catalog: GuidedCatalog = runCatching { GuidedCatalog.parse(bundled()) }.getOrDefault(GuidedCatalog())
        private set

    suspend fun refresh(): GuidedCatalog = withContext(Dispatchers.IO) {
        runCatching { GuidedCatalog.parse(fetcher.fetchText(url)) }.onSuccess { catalog = it }
        catalog
    }

    companion object {
        const val ASSET_PATH = "guided/catalog.json"

        // ponytail: one fork, one URL. Make it a build field if anyone else builds this.
        const val CATALOG_URL =
            "https://raw.githubusercontent.com/nimancini/OpenRide/main/app/src/main/assets/guided/catalog.json"
    }
}
