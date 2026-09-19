package com.drawlots.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.drawlots.app.domain.DrawMode
import com.drawlots.app.domain.MAX_QUANTITY

/** 圆形的小 “−” / “+” 按钮。 */
@Composable
fun StepperButton(
    symbol: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    size: Dp = 30.dp,
) {
    val background = if (enabled) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    }
    val foreground = if (enabled) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.30f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = symbol, style = MaterialTheme.typography.titleMedium, color = foreground)
    }
}

/** 数量选择器：− N + ，用于“每个签的数量”和“每次抽取数量”。 */
@Composable
fun QuantityStepper(
    value: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    min: Int = 1,
    max: Int = MAX_QUANTITY,
    enabled: Boolean = true,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        StepperButton(
            symbol = "−",
            onClick = { onChange((value - 1).coerceAtLeast(min)) },
            enabled = enabled && value > min,
        )
        Text(
            text = "$value",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.defaultMinSize(minWidth = 40.dp),
        )
        StepperButton(
            symbol = "+",
            onClick = { onChange((value + 1).coerceAtMost(max)) },
            enabled = enabled && value < max,
        )
    }
}

/** 放回 / 不放回 的选择按钮。 */
@Composable
fun ModeChip(
    mode: DrawMode,
    selectedMode: DrawMode,
    onSelect: (DrawMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = mode == selectedMode
    val shape = CircleShape
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
            .border(
                width = 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                shape = shape,
            )
            .clickable { onSelect(mode) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = mode.label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 带图标的小操作按钮。 */
@Composable
fun ActionButton(
    icon: Painter?,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        if (icon != null) {
            Icon(painter = icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}
