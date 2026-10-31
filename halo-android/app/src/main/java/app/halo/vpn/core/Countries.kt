package app.halo.vpn.core

/** Страна сервера по имени — как в версии для Windows (src/util.ts). */
object Countries {
    private val WORDS = listOf(
        Regex("япон|japan|tokyo|токио|osaka|осака", RegexOption.IGNORE_CASE) to "jp",
        Regex("казах|kazakh|almaty|алмат|astana|астан", RegexOption.IGNORE_CASE) to "kz",
        Regex("сингап|singapore", RegexOption.IGNORE_CASE) to "sg",
        Regex("нидерл|netherl|amsterdam|амстер|holland|голланд", RegexOption.IGNORE_CASE) to "nl",
        Regex("герман|german|frankfurt|франкф|berlin|берлин", RegexOption.IGNORE_CASE) to "de",
        Regex("финл|finland|helsinki|хельс", RegexOption.IGNORE_CASE) to "fi",
        Regex("турц|turk|istanbul|стамб", RegexOption.IGNORE_CASE) to "tr",
        Regex("сша|usa|united states|new york|нью-йорк|los angeles|лос-андж|america|америк", RegexOption.IGNORE_CASE) to "us",
        Regex("гонконг|hong ?kong", RegexOption.IGNORE_CASE) to "hk",
        Regex("британ|united kingdom|london|лондон|england|англи", RegexOption.IGNORE_CASE) to "gb",
        Regex("франц|france|paris|париж", RegexOption.IGNORE_CASE) to "fr",
        Regex("польш|poland|warsaw|варш", RegexOption.IGNORE_CASE) to "pl",
        Regex("швец|sweden|stockholm|стокгольм", RegexOption.IGNORE_CASE) to "se",
        Regex("латв|latvia|riga|рига", RegexOption.IGNORE_CASE) to "lv",
        Regex("эстон|estonia|tallinn|таллин", RegexOption.IGNORE_CASE) to "ee",
        Regex("литв|lithuania|vilnius|вильнюс", RegexOption.IGNORE_CASE) to "lt",
        Regex("швейц|switzerland|zurich|цюрих", RegexOption.IGNORE_CASE) to "ch",
        Regex("австри|austria|vienna|вена", RegexOption.IGNORE_CASE) to "at",
        Regex("канад|canada|toronto|торонто", RegexOption.IGNORE_CASE) to "ca",
        Regex("корея|korea|seoul|сеул", RegexOption.IGNORE_CASE) to "kr",
        Regex("индия|india|mumbai|мумбаи", RegexOption.IGNORE_CASE) to "in",
        Regex("испан|spain|madrid|мадрид", RegexOption.IGNORE_CASE) to "es",
        Regex("итал|italy|milan|милан", RegexOption.IGNORE_CASE) to "it",
        Regex("грузи|georgia|tbilisi|тбилиси", RegexOption.IGNORE_CASE) to "ge",
        Regex("армени|armenia|yerevan|ереван", RegexOption.IGNORE_CASE) to "am",
        Regex("молдов|moldova|chisinau|кишин", RegexOption.IGNORE_CASE) to "md",
        Regex("сербия|serbia|belgrade|белград", RegexOption.IGNORE_CASE) to "rs",
        Regex("оаэ|uae|dubai|дубай|emirates", RegexOption.IGNORE_CASE) to "ae",
        Regex("росси|russia|moscow|москв", RegexOption.IGNORE_CASE) to "ru",
    )

    private val EUROPE = setOf(
        "nl", "de", "fi", "gb", "fr", "pl", "se", "lv", "ee", "lt", "ch", "at", "es", "it", "rs", "md", "no", "dk", "be", "cz",
        "sk", "hu", "ro", "bg", "gr", "pt", "ie", "is", "lu", "ua", "by", "si", "hr", "al", "ba", "me", "mk", "cy", "mt",
    )
    private val ASIA = setOf(
        "jp", "kz", "sg", "hk", "kr", "in", "tr", "ae", "tw", "th", "vn", "my", "id", "ph", "cn", "uz", "kg", "tj", "ge", "am",
        "az", "il", "sa", "qa", "mn", "pk", "bd", "lk", "kh",
    )

    /** Двухбуквенный код (строчными) или null. */
    fun code(name: String): String? {
        val cps = name.codePoints().toArray().filter { it in 0x1F1E6..0x1F1FF }
        if (cps.size >= 2) return cps.take(2).map { 'a' + (it - 0x1F1E6) }.joinToString("")
        WORDS.firstOrNull { it.first.containsMatchIn(name) }?.let { return it.second }
        return Regex("\\b([A-Z]{2})\\b").find(name)?.groupValues?.get(1)?.lowercase()?.replace("uk", "gb")
    }

    /** Имя без флага-эмодзи. */
    fun clean(name: String): String =
        name.codePoints().toArray().filterNot { it in 0x1F1E6..0x1F1FF }.toIntArray()
            .let { String(it, 0, it.size) }.trim().ifEmpty { name }

    fun isEurope(code: String?) = code in EUROPE
    fun isAsia(code: String?) = code in ASIA

    fun protocol(type: String): String = when (type.lowercase()) {
        "vless" -> "VLESS"
        "vmess" -> "VMess"
        "trojan" -> "Trojan"
        "shadowsocks", "ss" -> "Shadowsocks"
        "shadowsocksr", "ssr" -> "ShadowsocksR"
        "hysteria2" -> "Hysteria2"
        "hysteria" -> "Hysteria"
        "tuic" -> "TUIC v5"
        "wireguard" -> "WireGuard"
        "anytls" -> "AnyTLS"
        "socks5" -> "SOCKS5"
        "http" -> "HTTP"
        else -> type
    }
}
