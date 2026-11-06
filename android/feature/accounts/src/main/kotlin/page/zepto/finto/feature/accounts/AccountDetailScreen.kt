package page.zepto.finto.feature.accounts

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import page.zepto.finto.core.designsystem.component.FintoTopBar
import page.zepto.finto.core.designsystem.component.MessageState
import page.zepto.finto.core.model.OpenLedger

@Composable
fun AccountDetailRoute(onBack: () -> Unit, onOpenLedger: OpenLedger, onOpenAccount: (String) -> Unit) {
    Scaffold(topBar = { FintoTopBar("Account", onBack = onBack) }) { padding ->
        MessageState(Icons.Outlined.AccountBalance, "Account", "", Modifier.padding(padding))
    }
}
