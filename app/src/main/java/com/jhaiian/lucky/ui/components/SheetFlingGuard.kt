package com.jhaiian.lucky.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

private const val SHEET_SETTLE_WAIT_MS = 1000L

internal val LuckySheetWindowInsets: WindowInsets
    @Composable get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)

@Composable
internal fun rememberSheetMaxHeight(): Dp {
    val configuration = LocalConfiguration.current
    return if (configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
        (configuration.screenHeightDp.dp * 0.85f).coerceAtLeast(320.dp)
    } else {
        (configuration.screenHeightDp.dp - 96.dp).coerceAtLeast(320.dp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberSheetFlingConnection(
    sheetState: SheetState,
    consumeFling: Boolean = true
): NestedScrollConnection = remember(sheetState, consumeFling) {
    object : NestedScrollConnection {
        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            withTimeoutOrNull(SHEET_SETTLE_WAIT_MS) {
                snapshotFlow {
                    sheetState.currentValue == SheetValue.Hidden && sheetState.targetValue != SheetValue.Hidden
                }.first { opening -> !opening }
            }
            return if (consumeFling) available else Velocity.Zero
        }
    }
}
