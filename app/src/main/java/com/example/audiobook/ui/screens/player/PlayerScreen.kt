package com.example.audiobook.ui.screens.player

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.audiobook.data.Chapter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    bookId: String,
    onBack: () -> Unit,
    viewModel: PlayerViewModel = viewModel(),
) {
    LaunchedEffect(bookId) { viewModel.loadBook(bookId) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.savePositionNow() }

    val book by viewModel.book.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val positionMs by viewModel.positionMs.collectAsState()
    val durationMs by viewModel.durationMs.collectAsState()
    val speed by viewModel.speed.collectAsState()

    var chaptersOpen by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (book?.chapters?.isNotEmpty() == true) {
                        IconButton(onClick = { chaptersOpen = true }) {
                            Icon(Icons.AutoMirrored.Outlined.List, contentDescription = "Chapters")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        val b = book
        if (b == null) {
            Box(modifier = Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Loading...", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))

            CoverArt(coverBytes = b.coverBytes, modifier = Modifier
                .fillMaxWidth(0.78f)
                .aspectRatio(1f))

            Spacer(Modifier.height(24.dp))

            Text(
                text = b.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
            if (!b.author.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = b.author,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }

            Spacer(Modifier.height(20.dp))

            CurrentChapterLabel(book = b, positionMs = positionMs)

            Spacer(Modifier.height(8.dp))

            Scrubber(
                positionMs = positionMs,
                durationMs = if (durationMs > 0L) durationMs else b.durationMs,
                onSeek = viewModel::seekTo,
            )

            Spacer(Modifier.height(20.dp))

            ControlsRow(
                isPlaying = isPlaying,
                onPlayPause = viewModel::playPause,
                onBack10 = viewModel::seekBack,
                onForward10 = viewModel::seekForward,
            )

            Spacer(Modifier.height(20.dp))

            SpeedPill(speed = speed, onTap = viewModel::cycleSpeed)
        }

        if (chaptersOpen && b.chapters.isNotEmpty()) {
            ModalBottomSheet(
                onDismissRequest = { chaptersOpen = false },
                sheetState = sheetState,
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                ChapterList(
                    chapters = b.chapters,
                    currentMs = positionMs,
                    onPick = { ch ->
                        viewModel.jumpToChapter(ch.startMs)
                        chaptersOpen = false
                    },
                )
            }
        }
    }
}

@Composable
private fun CoverArt(coverBytes: ByteArray?, modifier: Modifier) {
    val bmp = remember(coverBytes) {
        coverBytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                Icons.Outlined.AutoStories,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(72.dp),
            )
        }
    }
}

@Composable
private fun CurrentChapterLabel(book: com.example.audiobook.data.Audiobook, positionMs: Long) {
    val current = remember(book.id, positionMs) {
        book.chapters.lastOrNull { it.startMs <= positionMs }
    }
    if (current != null) {
        Text(
            text = current.title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    } else {
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun Scrubber(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit) {
    val safeDur = if (durationMs > 0L) durationMs else 1L
    var dragging by remember { mutableStateOf(false) }
    var draftMs by remember { mutableStateOf(0L) }
    val displayedMs = if (dragging) draftMs else positionMs

    Slider(
        value = (displayedMs.toFloat()).coerceIn(0f, safeDur.toFloat()),
        onValueChange = {
            dragging = true
            draftMs = it.toLong()
        },
        onValueChangeFinished = {
            dragging = false
            onSeek(draftMs)
        },
        valueRange = 0f..safeDur.toFloat(),
        colors = SliderDefaults.colors(
            activeTrackColor = MaterialTheme.colorScheme.primary,
            inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            thumbColor = MaterialTheme.colorScheme.primary,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = formatTime(displayedMs),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "-${formatTime((durationMs - displayedMs).coerceAtLeast(0L))}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ControlsRow(
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onBack10: () -> Unit,
    onForward10: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack10, modifier = Modifier.size(56.dp)) {
            Icon(
                Icons.Outlined.Replay10,
                contentDescription = "Back 10s",
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(40.dp),
            )
        }
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            onClick = onPlayPause,
            modifier = Modifier.size(72.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
        IconButton(onClick = onForward10, modifier = Modifier.size(56.dp)) {
            Icon(
                Icons.Outlined.Forward10,
                contentDescription = "Forward 10s",
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(40.dp),
            )
        }
    }
}

@Composable
private fun SpeedPill(speed: Float, onTap: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        onClick = onTap,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Icon(
                Icons.Outlined.Speed,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(6.dp))
            Text(
                text = formatSpeed(speed),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ChapterList(
    chapters: List<Chapter>,
    currentMs: Long,
    onPick: (Chapter) -> Unit,
) {
    val current = remember(chapters, currentMs) {
        chapters.lastOrNull { it.startMs <= currentMs }
    }
    LazyColumn(
        contentPadding = PaddingValues(vertical = 12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(chapters, key = { it.index }) { chapter ->
            val active = chapter.index == current?.index
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(chapter) }
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = chapter.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 2,
                    )
                    Text(
                        text = formatTime(chapter.startMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (active) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun formatSpeed(s: Float): String {
    return if (s == s.toInt().toFloat()) "${s.toInt()}×" else "${"%.2f".format(s).trimEnd('0').trimEnd('.')}×"
}
