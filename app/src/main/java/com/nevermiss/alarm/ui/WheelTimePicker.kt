package com.nevermiss.alarm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs

private val ITEM_HEIGHT = 48.dp
private const val VISIBLE_COUNT = 5 // must be odd
private const val LOOP_REPEAT = 1000

/** iOS-style scroll-wheel time picker: hour | minute | AM/PM (12h) or hour | minute (24h). */
@Composable
fun WheelTimePicker(
    hour: Int,
    minute: Int,
    is24Hour: Boolean,
    onChange: (hour: Int, minute: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current by rememberUpdatedState(hour to minute)
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // Selection band behind the centre row.
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .height(ITEM_HEIGHT)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
        )
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if (is24Hour) {
                Wheel(
                    items = (0..23).map { "%02d".format(it) },
                    selected = hour,
                    looping = true,
                    width = 80.dp,
                    onSelected = { onChange(it, current.second) },
                )
            } else {
                Wheel(
                    items = (1..12).map { it.toString() },
                    selected = (if (hour % 12 == 0) 12 else hour % 12) - 1,
                    looping = true,
                    width = 80.dp,
                    onSelected = { i ->
                        val h12 = (i + 1) % 12
                        onChange(h12 + if (current.first >= 12) 12 else 0, current.second)
                    },
                )
            }
            Text(":", fontSize = 30.sp, fontWeight = FontWeight.Medium)
            Wheel(
                items = (0..59).map { "%02d".format(it) },
                selected = minute,
                looping = true,
                width = 80.dp,
                onSelected = { onChange(current.first, it) },
            )
            if (!is24Hour) {
                Wheel(
                    items = listOf("AM", "PM"),
                    selected = if (hour >= 12) 1 else 0,
                    looping = false,
                    width = 72.dp,
                    onSelected = { i -> onChange(current.first % 12 + i * 12, current.second) },
                )
            }
        }
    }
}

@Composable
private fun Wheel(
    items: List<String>,
    selected: Int,
    looping: Boolean,
    width: Dp,
    onSelected: (Int) -> Unit,
) {
    val half = VISIBLE_COUNT / 2
    // Looping wheels repeat the items many times and start in the middle; the fixed wheel
    // gets blank rows above and below so its first/last items can reach the centre.
    val count = if (looping) items.size * LOOP_REPEAT else items.size + 2 * half
    val initialFirst = if (looping) items.size * (LOOP_REPEAT / 2) + selected - half else selected
    val state = rememberLazyListState(initialFirstVisibleItemIndex = initialFirst)
    val itemHeightPx = with(LocalDensity.current) { ITEM_HEIGHT.toPx() }
    val haptics = LocalHapticFeedback.current
    val onSelectedLatest by rememberUpdatedState(onSelected)

    val centerIndex by remember {
        derivedStateOf {
            val first = state.firstVisibleItemIndex +
                if (state.firstVisibleItemScrollOffset > itemHeightPx / 2) 1 else 0
            first + half
        }
    }

    LaunchedEffect(state) {
        var firstEmission = true
        snapshotFlow { centerIndex }
            .distinctUntilChanged()
            .collect { idx ->
                if (!firstEmission) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                firstEmission = false
                val itemIndex = if (looping) idx % items.size else (idx - half).coerceIn(0, items.lastIndex)
                onSelectedLatest(itemIndex)
            }
    }

    LazyColumn(
        state = state,
        flingBehavior = rememberSnapFlingBehavior(state),
        modifier = Modifier.width(width).height(ITEM_HEIGHT * VISIBLE_COUNT),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items(count) { index ->
            val label = if (looping) items[index % items.size]
            else items.getOrNull(index - half) ?: ""
            Box(
                Modifier
                    .height(ITEM_HEIGHT)
                    .fillMaxWidth()
                    .graphicsLayer {
                        // Distance from centre in rows (fractional while scrolling) -> 3D drum look.
                        val offsetRows = (index - state.firstVisibleItemIndex) -
                            state.firstVisibleItemScrollOffset / itemHeightPx - half
                        val d = abs(offsetRows).coerceAtMost(half + 0.5f)
                        alpha = 1f - 0.35f * d
                        rotationX = -offsetRows.coerceIn(-3f, 3f) * 18f
                        val s = 1f - 0.08f * d
                        scaleX = s
                        scaleY = s
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    fontSize = 28.sp,
                    fontWeight = if (index == centerIndex) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (index == centerIndex) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
