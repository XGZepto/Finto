package page.zepto.finto.core.designsystem.component

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import page.zepto.finto.core.designsystem.theme.FintoThemeTokens
import page.zepto.finto.core.designsystem.theme.tabular
import page.zepto.finto.core.model.Money
import page.zepto.finto.core.model.MoneyFormat

/**
 * A figure in tabular digits. Money in is green and signed `+`; money out keeps
 * the body colour, because most rows are spending and a column of red reads as
 * alarm rather than information.
 */
@Composable
fun MoneyText(
    money: Money,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    signed: Boolean = false,
    colorize: Boolean = signed,
    whole: Boolean = false,
    absolute: Boolean = false,
    fontWeight: FontWeight? = null,
    color: Color = Color.Unspecified,
) {
    val text = when {
        whole -> MoneyFormat.formatWhole(money, absolute = absolute)
        else -> MoneyFormat.format(money, signed = signed, absolute = absolute)
    }
    val tint = when {
        color != Color.Unspecified -> color
        colorize && money.amount > 0 -> FintoThemeTokens.money.positive
        else -> MaterialTheme.colorScheme.onSurface
    }
    Text(
        text = text,
        modifier = modifier,
        style = style.tabular(),
        color = tint,
        fontWeight = fontWeight,
        maxLines = 1,
    )
}
