package page.zepto.finto.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** A chip that shows the current choice and opens a menu of the others. */
@Composable
fun <T> DropdownChip(
    selected: T,
    options: List<T>,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box(modifier) {
        FilterChip(
            selected = false,
            onClick = { open = true },
            label = { Text(label(selected)) },
            trailingIcon = { Icon(Icons.Outlined.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp)) },
            modifier = if (description != null) Modifier.semantics { contentDescription = "$description: ${label(selected)}" } else Modifier,
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(label(option)) },
                    trailingIcon = { if (option == selected) Icon(Icons.Outlined.Check, contentDescription = null) },
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}
