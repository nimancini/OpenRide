package dev.digitalducktape.openride.ui.classes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.digitalducktape.openride.core.content.Video
import dev.digitalducktape.openride.ui.common.TimeFormat
import dev.digitalducktape.openride.ui.designsystem.ComponentPreview
import dev.digitalducktape.openride.ui.designsystem.OrColors
import dev.digitalducktape.openride.ui.designsystem.OrShapes
import dev.digitalducktape.openride.ui.designsystem.OrSpacing
import dev.digitalducktape.openride.ui.designsystem.OrType
import dev.digitalducktape.openride.ui.designsystem.component.OrOverlayBadge
import dev.digitalducktape.openride.ui.designsystem.component.OrShelfHeader
import dev.digitalducktape.openride.ui.theme.OpenRideTheme

val CardWidth = 280.dp
private val CardThumbnailHeight = 158.dp

/**
 * A class: its thumbnail (Coil, at display size) with the TAKEN, duration and Unavailable
 * badges laid over it, and its title in two lines under it. The whole card is one touch target.
 */
@Composable
fun VideoCard(
    video: Video,
    takenEpochMs: Long?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(CardWidth)
            .clip(OrShapes.Small)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Box {
            AsyncImage(
                model = video.thumbnailUrl,
                contentDescription = video.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(CardThumbnailHeight)
                    .clip(OrShapes.Small)
                    .background(OrColors.SurfaceRaised)
                    // A feed-fallback class can't be verified as public, so it can't be started
                    // (ClassesViewModel.startRideForVideo refuses it). Dim it and badge it so it
                    // reads as unavailable rather than looking like a normal, tappable class.
                    .alpha(if (video.startable) 1f else 0.4f),
            )
            // "Already ridden" (v2): when the active rider last took this class.
            takenEpochMs?.let { epochMs ->
                OrOverlayBadge(
                    text = TakenLabel.format(epochMs),
                    icon = Icons.Filled.Check,
                    iconTint = OrColors.Success,
                    modifier = Modifier.align(Alignment.TopStart).padding(OrSpacing.sm),
                )
            }
            // Has a cue sheet: the ride shows the instructor's cadence and resistance targets.
            if (video.guided) {
                OrOverlayBadge(
                    text = "GUIDED",
                    textColor = OrColors.OnAccent,
                    background = OrColors.Success,
                    modifier = Modifier.align(Alignment.TopEnd).padding(OrSpacing.sm),
                )
            }
            // Duration only when known: the RSS feed never has it and oEmbed not reliably, so
            // most fallback videos simply have no badge rather than a fake "0:00".
            video.durationSec?.let { seconds ->
                OrOverlayBadge(
                    text = TimeFormat.elapsed(seconds),
                    style = OrType.Caption,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(OrSpacing.sm),
                )
            }
            if (!video.startable) {
                OrOverlayBadge(
                    text = "Unavailable, refresh",
                    style = OrType.Caption,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
        Text(
            text = video.title,
            style = OrType.Body,
            color = if (video.startable) OrColors.OnBackground else OrColors.OnSurfaceMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = OrSpacing.sm),
        )
    }
}

/**
 * A titled horizontal row of [VideoCard]s: a creator on the Classes tab (the title opens their
 * page via [onTitleClick]) or LATEST and each playlist on a creator's page.
 */
@Composable
fun VideoShelf(
    title: String,
    videos: List<Video>,
    takenVideos: Map<String, Long>,
    onVideoSelected: (Video) -> Unit,
    modifier: Modifier = Modifier,
    onTitleClick: (() -> Unit)? = null,
    onTitleClickLabel: String? = null,
    body: (@Composable () -> Unit)? = null,
) {
    Column(modifier = modifier.padding(top = OrSpacing.md)) {
        OrShelfHeader(title = title, onClick = onTitleClick, onClickLabel = onTitleClickLabel)
        if (body != null) {
            body()
        } else {
            LazyRow(
                modifier = Modifier.padding(top = OrSpacing.sm),
                horizontalArrangement = Arrangement.spacedBy(OrSpacing.md),
            ) {
                items(videos, key = { it.id }) { video ->
                    VideoCard(
                        video = video,
                        takenEpochMs = takenVideos[video.id],
                        onClick = { onVideoSelected(video) },
                    )
                }
            }
        }
    }
}

internal val previewVideos = listOf(
    Video(id = "a", title = "45 min climb, steady tempo", thumbnailUrl = "", channelName = "Hill Club", durationSec = 45 * 60, publishedEpochMs = 3),
    Video(id = "b", title = "20 min intervals", thumbnailUrl = "", channelName = "Hill Club", durationSec = 20 * 60, publishedEpochMs = 2),
    Video(id = "c", title = "Lakeside scenic ride", thumbnailUrl = "", channelName = "Hill Club", durationSec = null, publishedEpochMs = 1, startable = false),
)

@ComponentPreview
@Composable
private fun VideoShelfPreview() {
    OpenRideTheme {
        VideoShelf(
            title = "Hill Club",
            videos = previewVideos,
            takenVideos = mapOf("a" to 1_760_000_000_000L),
            onVideoSelected = {},
            onTitleClick = {},
            modifier = Modifier.width(960.dp).padding(OrSpacing.lg),
        )
    }
}
