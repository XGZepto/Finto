package page.zepto.finto.feature.reports

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import page.zepto.finto.core.designsystem.theme.SeriesColors
import page.zepto.finto.core.model.Labels
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * Eight band colours, as the web's `--c1`…`--c8`: the design system's series
 * up to its closing grey (a neutral that would read as "other"), then the
 * web's last two.
 */
internal val BandColors: List<Color> = SeriesColors.take(6) + listOf(Color(0xFFD97BA8), Color(0xFF8FA4B8))

internal fun bandColor(index: Int): Color = BandColors[index % BandColors.size]

/** A chip that shows the current choice and opens a menu of the others. */
@Composable
internal fun <T> OptionChip(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        FilterChip(
            selected = false,
            onClick = { expanded = true },
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp)) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
            modifier = Modifier.semantics { contentDescription = "$description: $label" },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                    trailingIcon = if (option == selected) {
                        { Icon(Icons.Filled.Check, contentDescription = "Selected") }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/** How a bucket of [dimension] reads: account ids become their names, kinds their labels. */
internal fun bucketLabel(dimension: String, bucket: String, accountNames: Map<String, String> = emptyMap()): String = when (dimension) {
    "account" -> accountNames[bucket] ?: Labels.humanize(bucket)
    "kind" -> Labels.kind(bucket)
    else -> Labels.humanize(bucket)
}

private val monthYear = DateTimeFormatter.ofPattern("MMM yyyy")

/** `Sep 2026` for a `2026-09` bucket. */
internal fun monthLabel(bucket: String): String =
    runCatching { YearMonth.parse(bucket).format(monthYear) }.getOrDefault(bucket)
