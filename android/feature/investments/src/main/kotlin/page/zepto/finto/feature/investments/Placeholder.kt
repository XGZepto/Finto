package page.zepto.finto.feature.investments

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import page.zepto.finto.core.designsystem.component.FintoTopBar
import page.zepto.finto.core.designsystem.component.MessageState

@Composable
internal fun Placeholder(title: String, onBack: (() -> Unit)?) {
    Scaffold(topBar = { FintoTopBar(title, onBack = onBack) }) { padding ->
        MessageState(Icons.Outlined.Construction, title, "", Modifier.padding(padding))
    }
}
