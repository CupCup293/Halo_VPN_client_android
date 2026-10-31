package app.halo.vpn.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

// Размеры макета (телефон 444 px шириной) → dp: геометрия × 0.86, текст 24/26/28/34 px → 14/16/18/26 sp.
object Sz {
    val label = 14.sp
    val body = 16.sp
    val big = 18.sp
    val title = 26.sp
    val pad = 18.dp
    val radius = 20.dp
}

@Composable
fun T(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = Sz.label,
    weight: FontWeight = FontWeight.Normal,
    color: Color = LocalHalo.current.text,
    mono: Boolean = false,
    align: TextAlign? = null,
    lines: Int = 1,
) {
    Text(
        text, modifier, color = color, fontSize = size, fontWeight = weight, fontFamily = if (mono) Mono else Roboto,
        textAlign = align, maxLines = lines, overflow = TextOverflow.Ellipsis,
    )
}

/** Заголовок раздела в настройках: акцентный, полужирный («Режим работы», «Подключение», «Тема»). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    T(text, modifier.padding(top = 4.dp), weight = FontWeight.Bold, color = LocalHalo.current.accent)
}

/** Флаг страны: прямоугольник 3:2 со скруглением и тонкой рамкой, как в макете. */
@Composable
fun Flag(code: String?, width: Dp = 38.dp) {
    val ctx = LocalContext.current
    val img = remember(code) { Flags.image(ctx, code) }
    val shape = RoundedCornerShape(width * (8f / 60f))
    val h = width * (2f / 3f)
    if (img != null) {
        Image(
            img, code, Modifier.size(width, h).clip(shape).border(1.dp, Color(0x59808080), shape),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(Modifier.size(width, h).clip(shape).background(LocalHalo.current.divider), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Public, null, Modifier.size(h * 0.7f), tint = LocalHalo.current.muted)
        }
    }
}

object Flags {
    private val cache = ConcurrentHashMap<String, Bitmap>()

    fun bitmap(ctx: Context, code: String?): Bitmap? {
        val c = code?.lowercase() ?: return null
        cache[c]?.let { return it }
        return runCatching { ctx.assets.open("flags/$c.png").use { BitmapFactory.decodeStream(it) } }.getOrNull()?.also { cache[c] = it }
    }

    fun image(ctx: Context, code: String?): ImageBitmap? = bitmap(ctx, code)?.asImageBitmap()
}

/** Переключатель как в макете: включён — акцентная дорожка; выключен — рамка и маленький бегунок. */
@Composable
fun HaloSwitch(on: Boolean, onClick: (() -> Unit)? = null) {
    val c = LocalHalo.current
    val shape = RoundedCornerShape(50)
    // Бегунок плавно едет и растёт, дорожка плавно меняет цвет — как в One UI.
    val spec = androidx.compose.animation.core.spring<Dp>(dampingRatio = 0.7f, stiffness = 500f)
    // Отступ бегунка слева: включён — у правого края (48 − 22 − 3.5), выключен — маленький у левого.
    val offset by androidx.compose.animation.core.animateDpAsState(if (on) 22.5.dp else 6.dp, spec, label = "thumb")
    val thumb by androidx.compose.animation.core.animateDpAsState(if (on) 22.dp else 14.dp, spec, label = "size")
    val track by androidx.compose.animation.animateColorAsState(if (on) c.accent else c.switchOff, label = "track")
    val dot by androidx.compose.animation.animateColorAsState(if (on) c.onAccent else c.outline, label = "dot")
    val border by androidx.compose.animation.animateColorAsState(if (on) c.accent else c.outline, label = "border")
    Box(
        Modifier.size(48.dp, 29.dp).clip(shape).background(track).border(2.dp, border, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.padding(start = offset).size(thumb).clip(CircleShape).background(dot))
    }
}

/** Радиокнопка как в макете. */
@Composable
fun Radio(on: Boolean) {
    val c = LocalHalo.current
    val ring by androidx.compose.animation.animateColorAsState(if (on) c.accent else c.outline, label = "ring")
    val dot by androidx.compose.animation.core.animateDpAsState(if (on) 12.dp else 0.dp, androidx.compose.animation.core.spring(dampingRatio = 0.55f), label = "dot")
    Box(Modifier.size(24.dp).border(2.dp, ring, CircleShape), contentAlignment = Alignment.Center) {
        Box(Modifier.size(dot).clip(CircleShape).background(c.accent))
    }
}

/** Сегменты в рамке с галочкой у выбранного («✓ Тёмная | Светлая | Авто»). */
@Composable
fun <T> Segmented(items: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    val c = LocalHalo.current
    val shape = RoundedCornerShape(17.dp)
    Row(Modifier.fillMaxWidth().clip(shape).border(1.dp, c.outline, shape)) {
        items.forEachIndexed { i, item ->
            val on = item == selected
            val bg by androidx.compose.animation.animateColorAsState(if (on) c.container else Color.Transparent, label = "seg")
            if (i > 0) Box(Modifier.size(1.dp, 40.dp).background(c.outline))
            Box(
                Modifier.weight(1f).background(bg).clickable { onSelect(item) }.padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                T(if (on) "✓ ${label(item)}" else label(item), weight = if (on) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

/** Фильтр-чип серверов: выбранный — с заливкой и галочкой, остальные — в рамке. */
@Composable
fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    val c = LocalHalo.current
    val shape = RoundedCornerShape(9.dp)
    val bg by androidx.compose.animation.animateColorAsState(if (on) c.container else Color.Transparent, label = "chip")
    val line by androidx.compose.animation.animateColorAsState(if (on) c.container else c.outline, label = "chipLine")
    Box(
        Modifier.clip(shape).background(bg).border(1.dp, line, shape)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 5.dp),
    ) {
        T(if (on) "✓ $text" else text, weight = if (on) FontWeight.Medium else FontWeight.Normal)
    }
}

/** Карточка-группа с разделителями между строками (настройки, приложения). */
@Composable
fun GroupRow(last: Boolean, onClick: (() -> Unit)? = null, padV: Dp = 10.dp, content: @Composable RowScope.() -> Unit) {
    val c = LocalHalo.current
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .drawBehind { if (!last) drawLine(c.divider, androidx.compose.ui.geometry.Offset(0f, size.height), androidx.compose.ui.geometry.Offset(size.width, size.height), 1.dp.toPx()) }
            .padding(horizontal = 16.dp, vertical = padV),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

/** Пунктирная рамка (кнопки «Ссылка» и «QR-код»). */
fun Modifier.dashed(color: Color, radius: Dp) = drawBehind {
    val w = 2.dp.toPx()
    drawRoundRect(
        color, topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
        size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
    )
}

fun pingColor(delay: Int?, c: HaloColors) = when {
    delay == null -> c.muted
    delay >= 100 -> c.warn
    else -> c.accent
}

// ---------- Форматирование, как в версии для Windows ----------

/** «48.2 MB/s», «512 KB/s». */
fun speed(bytesPerSec: Long): String = when {
    bytesPerSec >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB/s", bytesPerSec / 1048576.0)
    bytesPerSec >= 1024 -> "${bytesPerSec / 1024} KB/s"
    else -> "$bytesPerSec B/s"
}

/** «137 GB», «41.9 GB», «512 MB». */
fun gb(n: Long): String = when {
    n >= 100L * 1024 * 1024 * 1024 -> "${n / (1024L * 1024 * 1024)} GB"
    n >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", n / 1073741824.0)
    n >= 1024 * 1024 -> "${n / 1048576} MB"
    else -> "${n / 1024} KB"
}

/** «02:14:37». */
fun duration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.ROOT, "%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
}

/** «3 января». */
fun dayMonth(unixSeconds: Long): String = SimpleDateFormat("d MMMM", Locale.forLanguageTag("ru")).format(Date(unixSeconds * 1000))

val Bold = FontWeight.Bold
val Semi = FontWeight.SemiBold
val Medium = FontWeight.Medium
@Suppress("unused") val MonoFamily: FontFamily get() = Mono
