package app.brokeometer.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Back goes to the page you were on before, then leaves the screen. [onLeave] runs for each page you
 * leave, so it can start at the top when you come back to it.
 */
@Composable
internal fun PageHistory(pager: PagerState, enabled: Boolean = true, onLeave: suspend (Int) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var history by rememberSaveable { mutableStateOf(listOf<Int>()) }
    var goingBack by remember { mutableStateOf(false) }
    val leave by rememberUpdatedState(onLeave)
    LaunchedEffect(pager) {
        var last = pager.settledPage
        snapshotFlow { pager.settledPage }.collect { page ->
            if (page != last) {
                if (!goingBack) history = (history + last).takeLast(20)
                goingBack = false
                leave(last)
                last = page
            }
        }
    }
    BackHandler(enabled = enabled && history.isNotEmpty()) {
        val target = history.last()
        history = history.dropLast(1)
        goingBack = true
        scope.launch { pager.animateScrollToPage(target) }
    }
}

/**
 * On the first page there is nothing to the left, so swiping right opens the menu, as it does on
 * screens without pages. Put it on the pager with Modifier.nestedScroll.
 */
@Composable
internal fun rememberMenuPull(pager: PagerState, onMenu: () -> Unit): NestedScrollConnection {
    val density = LocalDensity.current
    val open by rememberUpdatedState(onMenu)
    return remember(pager, density) {
        val needed = with(density) { 56.dp.toPx() }
        object : NestedScrollConnection {
            var started = false
            var armed = false
            var pulled = 0f
            var opened = false

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && !started) {
                    // Only a swipe that starts on the first page, resting, counts; not one coming back to it.
                    started = true
                    armed = pager.currentPage == 0 && abs(pager.currentPageOffsetFraction) < 0.01f
                    pulled = 0f
                    opened = false
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (!armed || source != NestedScrollSource.UserInput || available.x <= 0f) return Offset.Zero
                pulled += available.x
                if (!opened && pulled >= needed) {
                    opened = true
                    open()
                }
                return Offset(available.x, 0f)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                // A quick flick to the right opens it too.
                if (armed && !opened && pulled > needed / 4 && available.x > 1_200f) {
                    opened = true
                    open()
                }
                started = false
                armed = false
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                started = false
                armed = false
                return Velocity.Zero
            }
        }
    }
}
