package page.zepto.finto.core.testing

import page.zepto.finto.core.model.ConvertedMoney
import page.zepto.finto.core.model.Money
import page.zepto.finto.core.model.SummaryRow
import page.zepto.finto.core.model.Txn
import page.zepto.finto.core.model.User
import page.zepto.finto.core.model.UserPreferences

object TestData {
    val user = User(id = "demo", username = "demo", email = "demo@finto.app", preferences = UserPreferences(baseCurrency = "HKD"))

    fun row(bucket: String, spend: Long, income: Long = 0, currency: String = "HKD", count: Int = 3, convertible: Boolean = true) =
        SummaryRow(
            bucket = bucket,
            currency = currency,
            txnCount = count,
            net = Money(income - spend, currency),
            spend = Money(spend, currency),
            income = Money(income, currency),
            netConverted = ConvertedMoney(income - spend, "HKD", ok = convertible),
            spendConverted = ConvertedMoney(spend, "HKD", ok = convertible),
            incomeConverted = ConvertedMoney(income, "HKD", ok = convertible),
        )

    fun txn(
        id: String,
        date: String,
        description: String,
        amount: Long,
        account: String = "HSBC Current",
        category: String? = "groceries",
        merchant: String? = null,
        kind: String = "purchase",
    ) = Txn(
        id = id,
        date = date,
        accountId = account.lowercase().replace(' ', '_'),
        accountName = account,
        accountCurrency = "HKD",
        institutionId = "hsbc_hk",
        description = description,
        merchant = merchant,
        booked = Money(amount, "HKD"),
        kind = kind,
        category = category,
    )
}
