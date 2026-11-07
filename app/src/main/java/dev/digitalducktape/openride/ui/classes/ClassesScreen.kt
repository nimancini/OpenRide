package dev.digitalducktape.openride.ui.classes

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.digitalducktape.openride.core.content.ChannelSection
import dev.digitalducktape.openride.core.content.ContentCategory
import dev.digitalducktape.openride.core.content.Video
import dev.digitalducktape.openride.core.guided.GuidedCatalog
import dev.digitalducktape.openride.ui.designsystem.BikePreview
import dev.digitalducktape.openride.ui.designsystem.OrSpacing
import dev.digitalducktape.openride.ui.designsystem.component.OrBanner
import dev.digitalducktape.openride.ui.designsystem.component.OrBannerTone
import dev.digitalducktape.openride.ui.designsystem.component.OrChip
import dev.digitalducktape.openride.ui.designsystem.component.OrDropdownChip
import dev.digitalducktape.openride.ui.designsystem.component.OrEmptyState
import dev.digitalducktape.openride.ui.designsystem.component.OrLoading
import dev.digitalducktape.openride.ui.theme.OpenRideTheme
import kotlinx.coroutines.launch

/**
 * Classes tab, connected to its [ClassesViewModel] (the Route half of the Route/Screen split):
 * refreshes the catalog on entry, shows the view model's one-off messages as toasts, and only
 * navigates to a video ride when the view model actually started one. Random ride lives in the
 * shared top bar (`MainScaffold`).
 */
@Composable
fun ClassesRoute(
    viewModel: ClassesViewModel,
    onStartVideoRide: (videoId: String) -> Unit,
    onOpenCreator: (sourceId: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val takenVideos by viewModel.takenVideos.collectAsStateWithLifecycle()
    val filters by viewModel.filters.collectAsStateWithLifecycle()
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val gridVideos by viewModel.gridVideos.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { viewModel.refresh() }

    val context = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    ClassesScreen(
        state = ClassesScreenState.from(uiState, filters, rows, gridVideos, takenVideos),
        onCategorySelected = viewModel::setCategory,
        onSortSelected = viewModel::setSort,
        onLengthSelected = viewModel::setLength,
        onHideTakenToggled = viewModel::setHideTaken,
        onVideoSelected = { video -> if (viewModel.startRideForVideo(video)) onStartVideoRide(video.id) },
        onOpenCreator = onOpenCreator,
        onRetry = { scope.launch { viewModel.refresh() } },
        modifier = modifier,
    )
}

/**
 * Classes tab, stateless: browse by creator, or filter the whole catalog at once.
 *
 * Two layouts, one screen. With the default sort and no length filter the tab shows one shelf
 * per configured creator (tap a creator's name for their page). Choosing a sort or a length is
 * a question about the entire catalog ("a 30-minute class, any creator"), so those switch to a
 * flat grid. Category chips and Hide completed narrow both.
 */
@Composable
fun ClassesScreen(
    state: ClassesScreenState,
    onCategorySelected: (CategoryFilter) -> Unit,
    onSortSelected: (ClassSort) -> Unit,
    onLengthSelected: (LengthFilter) -> Unit,
    onHideTakenToggled: (Boolean) -> Unit,
    onVideoSelected: (Video) -> Unit,
    onOpenCreator: (sourceId: Long) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.body is ClassesBody.Loading) {
        OrLoading(modifier = modifier)
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(start = OrSpacing.ScreenGutter, end = OrSpacing.ScreenGutter, top = OrSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(OrSpacing.md),
    ) {
        FilterBar(
            filters = state.filters,
            lengthLabel = state.lengthLabel,
            onCategorySelected = onCategorySelected,
            onSortSelected = onSortSelected,
            onLengthSelected = onLengthSelected,
            onHideTakenToggled = onHideTakenToggled,
        )

        if (state.refreshFailed) {
            OrBanner(text = ClassesScreenState.REFRESH_FAILED_MESSAGE, tone = OrBannerTone.Warning)
        }

        when (val body = state.body) {
            is ClassesBody.Empty -> OrEmptyState(
                message = body.message,
                actionText = if (body.showRetry) "Retry" else null,
                onAction = onRetry,
            )
            is ClassesBody.Grid -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = CardWidth),
                horizontalArrangement = Arrangement.spacedBy(OrSpacing.md),
                verticalArrangement = Arrangement.spacedBy(OrSpacing.lg),
                contentPadding = PaddingValues(bottom = OrSpacing.lg),
            ) {
                items(body.videos, key = { it.id }) { video ->
                    VideoCard(
                        video = video,
                        takenEpochMs = state.takenVideos[video.id],
                        onClick = { onVideoSelected(video) },
                    )
                }
            }
            is ClassesBody.Shelves -> LazyColumn(contentPadding = PaddingValues(bottom = OrSpacing.lg)) {
                items(body.sections, key = { it.sourceId }) { section ->
                    VideoShelf(
                        title = section.channelName,
                        videos = section.videos,
                        takenVideos = state.takenVideos,
                        onVideoSelected = onVideoSelected,
                        // The creator's own page: their full recent catalog and playlists. The
                        // guided shelf spans creators, so it has none.
                        onTitleClick = { onOpenCreator(section.sourceId) }
                            .takeIf { section.sourceId != GuidedCatalog.SHELF_SOURCE_ID },
                        onTitleClickLabel = "Open ${section.channelName}",
                    )
                }
            }
            ClassesBody.Loading -> Unit
        }
    }
}

@Composable
private fun FilterBar(
    filters: ClassFilters,
    lengthLabel: String,
    onCategorySelected: (CategoryFilter) -> Unit,
    onSortSelected: (ClassSort) -> Unit,
    onLengthSelected: (LengthFilter) -> Unit,
    onHideTakenToggled: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(OrSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryFilter.entries.forEach { category ->
            OrChip(
                text = category.label,
                selected = filters.category == category,
                onClick = { onCategorySelected(category) },
            )
        }
        Spacer(modifier = Modifier.width(OrSpacing.md))
        OrDropdownChip(
            label = filters.sort.label,
            options = ClassSort.entries.map { it to it.label },
            onSelected = onSortSelected,
            selectedValue = filters.sort,
        )
        OrDropdownChip(
            label = lengthLabel,
            options = LengthFilter.entries.map { it to ClassesScreenState.lengthChipLabel(it) },
            onSelected = onLengthSelected,
            selectedValue = filters.length,
        )
        Spacer(modifier = Modifier.weight(1f))
        OrChip(
            text = "Hide completed",
            selected = filters.hideTaken,
            onClick = { onHideTakenToggled(!filters.hideTaken) },
        )
    }
}

@BikePreview
@Composable
private fun ClassesScreenPreview() {
    val sections = listOf(
        ChannelSection(1, "c1", "Hill Club", ContentCategory.Workout, previewVideos, refreshFailed = false),
        ChannelSection(2, "c2", "Lakeside Rides", ContentCategory.Scenic, previewVideos.reversed(), refreshFailed = true),
    )
    OpenRideTheme {
        ClassesScreen(
            state = ClassesScreenState(
                body = ClassesBody.Shelves(sections),
                refreshFailed = true,
                takenVideos = mapOf("a" to 1_760_000_000_000L),
            ),
            onCategorySelected = {},
            onSortSelected = {},
            onLengthSelected = {},
            onHideTakenToggled = {},
            onVideoSelected = {},
            onOpenCreator = {},
            onRetry = {},
        )
    }
}
