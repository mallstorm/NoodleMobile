package pro.noodle.mobile.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pro.noodle.mobile.core.audio.AudioEq
import pro.noodle.mobile.core.audio.ChannelMode
import pro.noodle.mobile.core.audio.EQ_BAND_FREQS
import pro.noodle.mobile.core.audio.EQ_MAX_DB
import pro.noodle.mobile.core.audio.EqMath
import pro.noodle.mobile.core.audio.EqPresets
import pro.noodle.mobile.core.audio.EqSettings
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

private val BAND_LABELS = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")

private fun db(v: Float) = (if (v > 0.04f) "+" else "") + String.format(java.util.Locale.US, "%.1f дБ", v)
private fun pct(v: Float) = "${(v * 100).roundToInt()}%"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerSheet(onDismiss: () -> Unit) {
    val s by AudioEq.flow.collectAsState()
    val user by AudioEq.userPresets.collectAsState()
    var saveDialog by remember { mutableStateOf(false) }
    var saveName by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Эквалайзер", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Пресет: ${s.presetName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { AudioEq.reset() }) { Text("Сброс") }
                Switch(checked = s.enabled, onCheckedChange = { AudioEq.setEnabled(it) })
            }

            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EqPresets.builtin.forEach { p ->
                    FilterChip(selected = s.presetName == p.name, onClick = { AudioEq.apply(p) }, label = { Text(p.name) })
                }
                user.keys.forEach { name ->
                    FilterChip(selected = s.presetName == name, onClick = { AudioEq.applyUser(name) }, label = { Text("★ $name") })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { saveName = if (s.presetName == "Свой") "" else s.presetName; saveDialog = true }) { Text("Сохранить пресет") }
                if (s.presetName in user) TextButton(onClick = { AudioEq.deleteUserPreset(s.presetName) }) { Text("Удалить «${s.presetName}»") }
            }

            EqCurve(s)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                for (i in EQ_BAND_FREQS.indices) {
                    VBand(s.bands.getOrElse(i) { 0f }, BAND_LABELS[i]) { g ->
                        AudioEq.edit { cur -> cur.copy(bands = cur.bands.toMutableList().also { it[i] = g }) }
                    }
                }
            }
            Text("Тяните точки на графике или ползунки; двойной тап по ползунку — в 0.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Section("Усиление и тембр") {
                EqSlider("Предусиление", s.preamp, -12f..12f, db(s.preamp)) { v -> AudioEq.edit { it.copy(preamp = v) } }
                EqSlider("Бас (до 120 Гц)", s.bass, -12f..12f, db(s.bass)) { v -> AudioEq.edit { it.copy(bass = v) } }
                EqSlider("Высокие (от 8 кГц)", s.treble, -12f..12f, db(s.treble)) { v -> AudioEq.edit { it.copy(treble = v) } }
                EqSlider("Добротность полос (Q)", s.q, 0.4f..4f, String.format(java.util.Locale.US, "%.2f", s.q)) { v -> AudioEq.edit { it.copy(q = v) } }
                EqSlider("Общая громкость", s.outputGain, -12f..12f, db(s.outputGain)) { v -> AudioEq.edit { it.copy(outputGain = v) } }
                SwitchRow("Мягкий ограничитель (без перегруза)", s.limiter) { v -> AudioEq.edit { it.copy(limiter = v) } }
            }

            Section("Каналы") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChannelMode.values().forEach { m ->
                        FilterChip(selected = s.channelMode == m, onClick = { AudioEq.edit { it.copy(channelMode = m) } }, label = { Text(m.title) })
                    }
                }
                SwitchRow("Инверсия фазы (правый канал)", s.invertPhase) { v -> AudioEq.edit { it.copy(invertPhase = v) } }
                EqSlider("Баланс", s.balance, -1f..1f, when { abs(s.balance) < 0.02f -> "центр"; s.balance < 0 -> "Л ${pct(-s.balance)}"; else -> "П ${pct(s.balance)}" }) { v ->
                    AudioEq.edit { it.copy(balance = if (abs(v) < 0.03f) 0f else v) }
                }
            }

            Section("Объёмный звук") {
                EqSlider("Ширина стерео", s.width, 0f..2f, pct(s.width)) { v -> AudioEq.edit { it.copy(width = if (abs(v - 1f) < 0.04f) 1f else v) } }
                EqSlider("Виртуальный объём (surround)", s.surround, 0f..1f, pct(s.surround)) { v -> AudioEq.edit { it.copy(surround = v) } }
                EqSlider("Кроссфид для наушников", s.crossfeed, 0f..1f, pct(s.crossfeed)) { v -> AudioEq.edit { it.copy(crossfeed = v) } }
            }

            Section("Динамика и пространство") {
                EqSlider("Компрессор (ровнее громкость)", s.compressor, 0f..1f, pct(s.compressor)) { v -> AudioEq.edit { it.copy(compressor = v) } }
                EqSlider("Реверберация", s.reverb, 0f..1f, pct(s.reverb)) { v -> AudioEq.edit { it.copy(reverb = v) } }
                EqSlider("Размер помещения", s.reverbRoom, 0f..1f, pct(s.reverbRoom)) { v -> AudioEq.edit { it.copy(reverbRoom = v) } }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (saveDialog) {
        AlertDialog(
            onDismissRequest = { saveDialog = false },
            title = { Text("Сохранить пресет") },
            text = { OutlinedTextField(value = saveName, onValueChange = { saveName = it.take(24) }, singleLine = true, label = { Text("Название") }) },
            confirmButton = { TextButton(onClick = { AudioEq.saveUserPreset(saveName); saveDialog = false }, enabled = saveName.isNotBlank()) { Text("Сохранить") } },
            dismissButton = { TextButton(onClick = { saveDialog = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun EqSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, text: String, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun VBand(gain: Float, label: String, onChange: (Float) -> Unit) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val prim = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    val cb = rememberUpdatedState(onChange)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(String.format(java.util.Locale.US, "%.0f", gain), style = MaterialTheme.typography.labelSmall)
        Canvas(
            Modifier.height(140.dp).width(28.dp)
                .pointerInput(Unit) {
                    fun setFrom(y: Float) {
                        val h = size.height.toFloat()
                        val g = ((h / 2f - y) / (h / 2f) * EQ_MAX_DB).coerceIn(-EQ_MAX_DB, EQ_MAX_DB)
                        cb.value((g * 2f).roundToInt() / 2f)
                    }
                    detectVerticalDragGestures(onDragStart = { setFrom(it.y) }, onVerticalDrag = { ch, _ -> setFrom(ch.position.y); ch.consume() })
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { cb.value(0f) }, onTap = { o ->
                        val h = size.height.toFloat()
                        cb.value((((h / 2f - o.y) / (h / 2f) * EQ_MAX_DB).coerceIn(-EQ_MAX_DB, EQ_MAX_DB) * 2f).roundToInt() / 2f)
                    })
                },
        ) {
            val cx = size.width / 2f
            drawLine(track, Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 6.dp.toPx())
            drawLine(outline, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), strokeWidth = 1.dp.toPx())
            val y = size.height / 2f - gain / EQ_MAX_DB * (size.height / 2f)
            drawLine(prim, Offset(cx, size.height / 2f), Offset(cx, y), strokeWidth = 6.dp.toPx())
            drawCircle(prim, radius = 8.dp.toPx(), center = Offset(cx, y))
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EqCurve(s: EqSettings) {
    val prim = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val txt = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = txt)
    var active by remember { mutableIntStateOf(0) }
    Canvas(
        Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .pointerInput(Unit) {
                fun setBand(o: Offset, pick: Boolean) {
                    val w = size.width.toFloat(); val h = size.height.toFloat()
                    if (pick) {
                        var best = 0; var bd = Float.MAX_VALUE
                        for (i in EQ_BAND_FREQS.indices) {
                            val x = ((ln(EQ_BAND_FREQS[i].toDouble()) - ln(20.0)) / ln(1000.0) * w).toFloat()
                            if (abs(x - o.x) < bd) { bd = abs(x - o.x); best = i }
                        }
                        active = best
                    }
                    val g = ((h / 2f - o.y) / (h / 2f) * 15f).coerceIn(-EQ_MAX_DB, EQ_MAX_DB)
                    val gs = (g * 2f).roundToInt() / 2f
                    val idx = active
                    AudioEq.edit { cur -> cur.copy(bands = cur.bands.toMutableList().also { it[idx] = gs }) }
                }
                detectDragGestures(onDragStart = { setBand(it, true) }, onDrag = { ch, _ -> setBand(ch.position, false); ch.consume() })
            },
    ) {
        val w = size.width; val h = size.height
        fun fx(f: Double) = ((ln(f) - ln(20.0)) / ln(1000.0) * w).toFloat()
        fun dy(d: Double) = (h / 2f - d / 15.0 * (h / 2f)).toFloat()
        for (v in listOf(-12, -6, 0, 6, 12)) drawLine(grid, Offset(0f, dy(v.toDouble())), Offset(w, dy(v.toDouble())), strokeWidth = if (v == 0) 2f else 1f)
        for (f in listOf(50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0)) drawLine(grid, Offset(fx(f), 0f), Offset(fx(f), h), strokeWidth = 1f)
        for ((f, t) in listOf(100.0 to "100", 1000.0 to "1k", 10000.0 to "10k")) drawText(measurer, t, Offset(fx(f) + 3f, h - 14.dp.toPx()), labelStyle)
        drawText(measurer, "+12", Offset(3f, dy(12.0)), labelStyle); drawText(measurer, "0", Offset(3f, dy(0.0) - 12.dp.toPx()), labelStyle); drawText(measurer, "−12", Offset(3f, dy(-12.0) - 12.dp.toPx()), labelStyle)

        val filters = EqMath.filters(s, 48000.0)
        val line = Path()
        val n = 160
        for (i in 0..n) {
            val f = 20.0 * 1000.0.pow(i / n.toDouble())
            val d = EqMath.responseDb(s, f, 48000.0, filters).coerceIn(-15.0, 15.0)
            if (i == 0) line.moveTo(fx(f), dy(d)) else line.lineTo(fx(f), dy(d))
        }
        val fill = Path().apply { addPath(line); lineTo(w, dy(0.0)); lineTo(0f, dy(0.0)); close() }
        drawPath(fill, prim.copy(alpha = 0.18f))
        drawPath(line, prim, style = Stroke(width = 2.5.dp.toPx()))
        for (i in EQ_BAND_FREQS.indices) {
            val f = EQ_BAND_FREQS[i].toDouble()
            val d = EqMath.responseDb(s, f, 48000.0, filters).coerceIn(-15.0, 15.0)
            drawCircle(prim, radius = if (i == active) 7.dp.toPx() else 4.dp.toPx(), center = Offset(fx(f), dy(d)))
        }
    }
}
