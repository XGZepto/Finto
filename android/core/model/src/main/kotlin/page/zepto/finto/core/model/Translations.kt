package page.zepto.finto.core.model

/**
 * The web app translates its navigation and settings into Traditional Chinese
 * and leaves the rest in English; this is the same list, keyed by the English.
 */
object Translations {
    const val ENGLISH = "en"
    const val TRADITIONAL_CHINESE = "zh-Hant"

    val languages = listOf(ENGLISH to "English", TRADITIONAL_CHINESE to "繁體中文")

    private val zhHant = mapOf(
        "Summary" to "總覽",
        "Activity" to "帳目",
        "Reports" to "報表",
        "Accounts" to "帳戶",
        "Recurring" to "定期",
        "Ask" to "查詢",
        "More" to "更多",
        "Money" to "財務",
        "Settings" to "設定",
        "Sign out" to "登出",
        "Preferences" to "偏好設定",
        "Appearance" to "外觀",
        "System" to "跟隨系統",
        "Dark" to "深色",
        "Light" to "淺色",
        "Language" to "語言",
        "Reporting currency" to "報表貨幣",
    )

    fun text(english: String, language: String?): String =
        if (language == TRADITIONAL_CHINESE) zhHant[english] ?: english else english
}
