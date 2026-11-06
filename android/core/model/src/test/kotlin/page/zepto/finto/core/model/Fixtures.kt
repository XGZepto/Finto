package page.zepto.finto.core.model

/** Responses captured from the public demo server, which holds only invented data. */
internal object Fixtures {
    fun read(name: String): String =
        requireNotNull(javaClass.classLoader.getResource("fixtures/$name.json")) { "missing fixture $name" }
            .readText()

    inline fun <reified T> decode(name: String): T = FintoJson.decodeFromString(read(name))
}
