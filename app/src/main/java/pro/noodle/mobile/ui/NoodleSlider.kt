package pro.noodle.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp

/** Мягкий ползунок: тонкая скруглённая дорожка с градиентной заливкой и круглый «бегунок» с кольцом. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoodleSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
) {
    val scheme = MaterialTheme.colorScheme
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        valueRange = valueRange,
        steps = steps,
        thumb = {
            Box(
                Modifier
                    .size(26.dp)
                    .shadow(4.dp, CircleShape)
                    .background(scheme.surface, CircleShape)
                    .border(BorderStroke(6.dp, scheme.primary), CircleShape),
            )
        },
        track = { state ->
            val span = (state.valueRange.endInclusive - state.valueRange.start).coerceAtLeast(0.0001f)
            val fraction = ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape)
                    .background(scheme.primaryContainer.copy(alpha = .55f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(Brush.horizontalGradient(listOf(scheme.secondary, scheme.primary))),
                )
            }
        },
    )
}
