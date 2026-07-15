package com.example.music_app

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.music_app.ui.theme.AppBlack
import com.example.music_app.ui.theme.MutedText
import com.example.music_app.ui.theme.NeonGreen
import com.example.music_app.ui.theme.Panel
import com.example.music_app.ui.theme.PanelSoft
import com.example.music_app.ui.theme.SoftLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

@Composable
fun TopBar(title: String, onMenuClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(104.dp)
            .background(AppBlack.copy(alpha = 0.98f))
            .border(width = 1.dp, color = SoftLine.copy(alpha = 0.65f))
            .padding(start = 22.dp, end = 18.dp, top = 26.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = onMenuClick,
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PanelSoft, contentColor = Color.White),
            contentPadding = ButtonDefaults.ContentPadding,
            modifier = Modifier.size(58.dp)
        ) {
            Text("☰", style = MaterialTheme.typography.headlineMedium)
        }
        Spacer(Modifier.width(18.dp))
        Text(
            title,
            color = Color.White,
            style = MaterialTheme.typography.headlineMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun SideNavigation(activeTab: AppTab, user: User, onTabChange: (AppTab) -> Unit, onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .width(304.dp)
            .fillMaxHeight()
            .background(Panel)
            .padding(horizontal = 18.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(NeonGreen.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Text("♪", color = NeonGreen, style = MaterialTheme.typography.headlineMedium)
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("DanceDeck", color = Color.White, style = MaterialTheme.typography.titleLarge)
                Text("Music", color = MutedText, style = MaterialTheme.typography.bodyLarge)
            }
            Button(
                onClick = onClose,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = PanelSoft, contentColor = Color.White),
                modifier = Modifier.size(46.dp)
            ) {
                Text("×", style = MaterialTheme.typography.titleLarge)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(PanelSoft.copy(alpha = 0.72f), RoundedCornerShape(24.dp))
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(NeonGreen),
                contentAlignment = Alignment.Center
            ) {
                Text(user.name.take(1).ifBlank { "D" }.uppercase(), color = Color.Black, style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(user.name.ifBlank { "naumenko" }, color = Color.White, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                Text(stringResource(R.string.free_account), color = MutedText, style = MaterialTheme.typography.bodyLarge)
            }
            Text("›", color = MutedText, style = MaterialTheme.typography.headlineMedium)
        }

        AppTab.entries.forEach { tab ->
            val active = activeTab == tab
            Button(
                onClick = { onTabChange(tab) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (active) NeonGreen else Color.Transparent,
                    contentColor = if (active) Color.Black else Color.White
                ),
                contentPadding = ButtonDefaults.ContentPadding
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(tab.icon, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.width(14.dp))
                    Text(stringResource(tab.labelRes), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (active) {
                        Spacer(Modifier.weight(1f))
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(Color.Black)
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(listOf(NeonGreen.copy(alpha = 0.22f), Color(0xFF0B2219))),
                    RoundedCornerShape(24.dp)
                )
                .border(1.dp, NeonGreen.copy(alpha = 0.16f), RoundedCornerShape(24.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(stringResource(R.string.upgrade_premium), color = Color.White, style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.premium_access), color = MutedText, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
fun MenuButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PanelSoft, contentColor = Color.White),
        modifier = Modifier
            .padding(12.dp)
            .height(42.dp)
    ) {
        Text("☰")
    }
}

@Composable
fun ScreenLayout(title: String, subtitle: String = "", content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBlack)
            .verticalScroll(rememberScrollState())
            .padding(start = 22.dp, end = 22.dp, top = 128.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        if (title.isNotBlank()) {
            Text(title, style = MaterialTheme.typography.displayLarge, color = Color.White)
        }
        if (subtitle.isNotBlank()) {
            Text(subtitle, style = MaterialTheme.typography.titleLarge, color = MutedText)
        }
        content()
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun AuthLayout(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBlack)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        BrandHeader(compact = false)
        Text(text = title, style = MaterialTheme.typography.headlineMedium, color = NeonGreen)
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = MutedText,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            content = content
        )
    }
}

@Composable
fun BrandHeader(compact: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            painter = painterResource(id = R.drawable.logo_dark_bg),
            contentDescription = "DanceDeckMusic",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .height(if (compact) 76.dp else 128.dp)
        )
    }
}

@Composable
fun PrimaryAction(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = NeonGreen, contentColor = Color(0xFF001B11)),
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
    ) {
        Text(text, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun RowScope.SocialButton(mark: String, text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .weight(1f)
            .height(64.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
        contentPadding = ButtonDefaults.ContentPadding
    ) {
        Text(mark, color = NeonGreen, style = MaterialTheme.typography.titleLarge)
        if (text.isNotBlank()) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun SongRow(
    song: Song,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
    onDownload: () -> Unit,
    selectable: Boolean = false,
    selected: Boolean = false,
    onSelectedChange: ((Boolean) -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    extraContent: @Composable ColumnScope.() -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selectable) {
                Button(
                    onClick = { onSelectedChange?.invoke(!selected) },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selected) NeonGreen else PanelSoft,
                        contentColor = if (selected) Color.Black else Color.White
                    ),
                    modifier = Modifier.size(42.dp),
                    contentPadding = ButtonDefaults.ContentPadding
                ) {
                    Text(if (selected) "✓" else "")
                }
                Spacer(modifier = Modifier.width(10.dp))
            }
            AlbumTile(size = 54, index = null, coverUrl = song.coverUrl)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(song.title, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist, color = MutedText, maxLines = 1)
                Text("↗ ${formatTime(song.durationSeconds)}", color = NeonGreen, style = MaterialTheme.typography.labelLarge)
            }
            Button(
                onClick = onPlay,
                colors = ButtonDefaults.buttonColors(containerColor = NeonGreen, contentColor = Color(0xFF001B11)),
                shape = RoundedCornerShape(8.dp)
            ) { Text("▶") }
            if (onAddToPlaylist != null || onDelete != null) {
                Box {
                    TextButton(onClick = { menuOpen = true }) {
                        Text("⋯", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        containerColor = PanelSoft
                    ) {
                        onAddToPlaylist?.let { action ->
                            DropdownMenuItem(
                                text = { Text("+ ${stringResource(R.string.add_to_playlist)}", color = Color.White) },
                                onClick = {
                                    menuOpen = false
                                    action()
                                }
                            )
                        }
                        onDelete?.let { action ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete_from_library), color = Color(0xFFFF5A66)) },
                                onClick = {
                                    menuOpen = false
                                    action()
                                }
                            )
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onFavorite,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (song.favorite) Color(0xFFFF3B4A) else Color.White,
                    containerColor = if (song.favorite) Color(0xFF2A1418) else Color.Transparent
                )
            ) {
                Text(if (song.favorite) "♥ ${stringResource(R.string.favorite_on)}" else "♡ ${stringResource(R.string.favorite_add)}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DarkButton(text = if (song.downloaded) "✓ ${stringResource(R.string.downloaded)}" else "↓ ${stringResource(R.string.download)}", modifier = Modifier.weight(1f), onClick = onDownload)
        }
        extraContent()
    }
}

@Composable
fun AlbumTile(size: Int, index: Int?, coverUrl: String = "") {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF255842), Color(0xFF16191F)))),
        contentAlignment = Alignment.Center
    ) {
        if (coverUrl.isNotBlank()) {
            RemoteCoverImage(coverUrl = coverUrl)
        } else {
            Text("♫", color = NeonGreen, style = MaterialTheme.typography.headlineMedium)
        }
        if (index != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .background(NeonGreen, CircleShape)
                    .padding(horizontal = 7.dp, vertical = 4.dp)
            ) {
                Text("#$index", color = Color.Black, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
fun RemoteCoverImage(coverUrl: String) {
    var image by remember(coverUrl) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(coverUrl) {
        image = withContext(Dispatchers.IO) {
            runCatching {
                URL(coverUrl).openStream().use { BitmapFactory.decodeStream(it).asImageBitmap() }
            }.getOrNull()
        }
    }
    if (image != null) {
        Image(
            bitmap = image!!,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    } else {
        Text("♫", color = NeonGreen, style = MaterialTheme.typography.headlineMedium)
    }
}

@Composable
fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, SoftLine, RoundedCornerShape(8.dp))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineMedium, color = Color.White)
}

@Composable
fun GenreRow(active: String, onChange: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        listOf(
            "all" to stringResource(R.string.genre_all),
            "pop" to stringResource(R.string.genre_pop),
            "rock" to stringResource(R.string.genre_rock),
            "electronic" to stringResource(R.string.genre_electronic),
            "jazz" to stringResource(R.string.genre_jazz),
            "classical" to stringResource(R.string.genre_classical),
            "hip-hop" to stringResource(R.string.genre_hiphop),
            "funk" to stringResource(R.string.genre_funk),
        ).forEach { (id, label) ->
            SmallChip(label, active == id) { onChange(id) }
        }
    }
}

@Composable
fun SmallChip(text: String, active: Boolean, onClick: () -> Unit) {
    val colors = if (active) {
        ButtonDefaults.buttonColors(containerColor = NeonGreen, contentColor = Color(0xFF001B11))
    } else {
        ButtonDefaults.buttonColors(containerColor = PanelSoft, contentColor = Color.White)
    }
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        colors = colors,
        modifier = Modifier.height(44.dp)
    ) {
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun DarkButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
        contentPadding = ButtonDefaults.ContentPadding
    ) { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

@Composable
fun ToggleRow(text: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleLarge, color = Color.White)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
fun Visualizer(active: Boolean, values: List<Int>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .background(Panel, RoundedCornerShape(8.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        repeat(30) { index ->
            val eq = values[index % values.size]
            val height = (28 + ((index * 17) % 52) + eq * 3 + if (active) (index % 4) * 7 else 0).coerceIn(14, 96)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(height.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (index % 2 == 0) NeonGreen.copy(alpha = 0.75f) else Color(0xFF155B43))
            )
        }
    }
}

@Composable
fun EmptyState(title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("♫", style = MaterialTheme.typography.displayLarge, color = MutedText)
        Text(title, style = MaterialTheme.typography.titleLarge, color = MutedText, textAlign = TextAlign.Center)
        Text(subtitle, color = MutedText, textAlign = TextAlign.Center)
    }
}

@Composable
fun StatRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MutedText)
        Text(value, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

fun formatTime(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    return "${safe / 60}:${(safe % 60).toString().padStart(2, '0')}"
}

fun Float.percent(): String = "${(this * 100).toInt()}%"
