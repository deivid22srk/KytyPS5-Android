package dev.kytyps5.android.ui.screens

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kytyps5.android.MainActivity
import dev.kytyps5.android.R
import dev.kytyps5.android.Screen
import dev.kytyps5.android.data.GameInfo
import dev.kytyps5.android.emu.RuntimeInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Game library: real scans of installed games with real param.sfo metadata,
 * real import via SAF and real storage accounting.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(activity: MainActivity, onNavigate: (Screen) -> Unit) {
    var games by remember { mutableStateOf(activity.repository.scan()) }
    var deleteTarget by remember { mutableStateOf<GameInfo?>(null) }
    var deleting by remember { mutableStateOf(false) }

    // rescan when returning from import or emulation (off the UI thread:
    // size accounting walks every file of multi-GB game trees)
    LaunchedEffect(activity.importStatus, activity.screen) {
        games = withContext(Dispatchers.IO) {
            activity.repository.scan()
        }
    }

    val storageUsed = remember(games) { games.sumOf { it.sizeBytes } }
    val total = activity.filesDir.totalSpace

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library_title)) },
                actions = {
                    IconButton(onClick = { onNavigate(Screen.Settings) }) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (activity.importStatus != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(activity.importStatus ?: "", style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (games.isEmpty()) {
                EmptyLibrary(
                    Modifier.weight(1f),
                    onImport = { activity.pickGameFolder() },
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(300.dp),
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(games, key = { it.titleId + it.installDir.path }) { game ->
                        GameCard(
                            game = game,
                            onPlay = { activity.playGame(game) },
                            onDelete = { deleteTarget = game },
                        )
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(
                        R.string.storage_used,
                        Formatter.formatFileSize(activity, storageUsed),
                        Formatter.formatFileSize(activity, total),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { activity.pickGameFolder() }) {
                    Text(stringResource(R.string.import_game))
                }
            }
        }
    }

    deleteTarget?.let { game ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.delete)) },
            text = { Text(stringResource(R.string.delete_game_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = true
                    /* multi-GB deleteRecursively + full rescan must stay off the
                     * main thread — this onClick runs in the input dispatcher */
                    CoroutineScope(Dispatchers.IO).launch {
                        activity.deleteGame(game)
                        val refreshed = activity.repository.scan()
                        withContext(Dispatchers.Main) {
                            games = refreshed
                            deleting = false
                            deleteTarget = null
                        }
                    }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun EmptyLibrary(modifier: Modifier, onImport: () -> Unit) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.VideogameAsset,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.library_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GameCard(game: GameInfo, onPlay: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(84.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    // real game cover from sce_sys/icon0.png when present
                    val cover = remember(game.installDir.path) {
                        val icon = java.io.File(game.installDir, "sce_sys/icon0.png")
                        if (icon.exists()) {
                            android.graphics.BitmapFactory.decodeFile(
                                icon.absolutePath,
                                android.graphics.BitmapFactory.Options().apply {
                                    inSampleSize = 4
                                },
                            )
                        } else {
                            null
                        }
                    }
                    if (cover != null) {
                        androidx.compose.foundation.Image(
                            bitmap = cover.asImageBitmap(),
                            contentDescription = game.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        )
                    } else {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.linearGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.surfaceVariant,
                                            MaterialTheme.colorScheme.secondaryContainer,
                                        )
                                    )
                                ),
                        )
                        Icon(
                            Icons.Filled.VideogameAsset,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp).align(Alignment.Center),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        game.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        game.titleId,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "v${game.appVersion} • ${Formatter.formatFileSize(LocalContext.current, game.sizeBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // informational label (not a button)
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        game.category.ifEmpty { "gd" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                Row {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete))
                    }
                    FilledIconButton(onClick = onPlay) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.play))
                    }
                }
            }
        }
    }
}
