package pro.noodle.mobile.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import pro.noodle.mobile.core.media.SourceIcons

/** Иконка сайта-источника (favicon / apple-touch-icon). Пока грузится или если её нет — первая буква названия. */
@Composable
fun SourceIcon(baseUrl: String, name: String, size: Dp = 42.dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, baseUrl) {
        value = runCatching { SourceIcons.load(context, baseUrl)?.asImageBitmap() }.getOrNull()
    }
    Surface(
        modifier = modifier.size(size),
        shape = RoundedCornerShape(size * 0.28f),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val bmp = icon
            if (bmp != null) {
                Image(bmp, contentDescription = name, contentScale = ContentScale.Fit, modifier = Modifier.size(size * 0.72f))
            } else {
                Text(
                    name.trim().firstOrNull()?.uppercase().orEmpty().ifBlank { "•" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
