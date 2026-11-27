package com.v2ray.ang.colitu.l10n

/**
 * Server names in the app language.
 *
 * The panel sends a node's display name and city in Turkish ("Almanya",
 * "Londra", "Letonya (Köprü)"). They are matched case- and
 * diacritic-insensitively against the Turkish, English and Russian names (and
 * a few aliases) and shown in the app language; text that matches nothing
 * stays exactly as the panel sent it. The tables are shared by all Colitu apps
 * (sunucu-adlari-ceviri.json, 2026-10-12; iOS: lib/colitu/l10n/server_names.dart);
 * country names come from [ColituLoc]'s country table.
 *
 * Without an explicit language the current [ColituLoc.language] is used. That
 * is Compose state, so a screen that calls these while composing is redrawn
 * as soon as the language changes.
 */
object ColituServerNames {
    /** [ru, tr, en] like [ColituLoc.languages] (Chinese is in [zhCities]), then aliases. */
    private val cities: List<List<String>> = listOf(
        listOf("Тирана", "Tiran", "Tirana"),
        listOf("Мельбурн", "Melbourne", "Melbourne"),
        listOf("Сан-Паулу", "São Paulo", "São Paulo", "Sao Paulo"),
        listOf("Франкфурт", "Frankfurt", "Frankfurt"),
        listOf("Берлин", "Berlin", "Berlin"),
        listOf("Таллин", "Tallinn", "Tallinn"),
        listOf("Тарту", "Tartu", "Tartu"),
        listOf("Мадрид", "Madrid", "Madrid"),
        listOf("Хельсинки", "Helsinki", "Helsinki"),
        listOf("Париж", "Paris", "Paris"),
        listOf("Лондон", "Londra", "London"),
        listOf("Ковентри", "Coventry", "Coventry"),
        listOf("Салоники", "Selanik", "Thessaloniki"),
        listOf("Тель-Авив", "Tel Aviv", "Tel Aviv", "Tel-Aviv"),
        listOf("Токио", "Tokyo", "Tokyo"),
        listOf("Алматы", "Almatı", "Almaty"),
        listOf("Вильнюс", "Vilnius", "Vilnius"),
        listOf("Рига", "Riga", "Riga"),
        listOf("Керкраде", "Kerkrade", "Kerkrade"),
        listOf("Дронтен", "Dronten", "Dronten"),
        listOf("Варшава", "Varşova", "Warsaw"),
        listOf("Москва", "Moskova", "Moscow"),
        listOf("Сингапур", "Singapur", "Singapore"),
        listOf("Бурса", "Bursa", "Bursa"),
        listOf("Киев", "Kiev", "Kyiv", "Kyiv"),
        listOf("Даллас", "Dallas", "Dallas"),
        listOf("Амстердам", "Amsterdam", "Amsterdam"),
        listOf("Стамбул", "İstanbul", "Istanbul"),
        listOf("Анкара", "Ankara", "Ankara"),
        listOf("Стокгольм", "Stockholm", "Stockholm"),
        listOf("Вена", "Viyana", "Vienna"),
        listOf("Цюрих", "Zürih", "Zurich"),
        listOf("Прага", "Prag", "Prague"),
        listOf("Бухарест", "Bükreş", "Bucharest"),
        listOf("София", "Sofya", "Sofia"),
        listOf("Будапешт", "Budapeşte", "Budapest"),
        listOf("Афины", "Atina", "Athens"),
        listOf("Белград", "Belgrad", "Belgrade"),
        listOf("Ереван", "Erivan", "Yerevan"),
        listOf("Тбилиси", "Tiflis", "Tbilisi"),
        listOf("Баку", "Bakü", "Baku"),
        listOf("Дубай", "Dubai", "Dubai"),
        listOf("Копенгаген", "Kopenhag", "Copenhagen"),
        listOf("Осло", "Oslo", "Oslo"),
        listOf("Милан", "Milano", "Milan"),
        listOf("Рим", "Roma", "Rome"),
        listOf("Мюнхен", "Münih", "Munich"),
        listOf("Дюссельдорф", "Düsseldorf", "Düsseldorf"),
        listOf("Нюрнберг", "Nürnberg", "Nuremberg"),
        listOf("Нью-Йорк", "New York", "New York"),
        listOf("Лос-Анджелес", "Los Angeles", "Los Angeles"),
        listOf("Майами", "Miami", "Miami"),
        listOf("Торонто", "Toronto", "Toronto"),
        listOf("Монреаль", "Montreal", "Montreal"),
        listOf("Сидней", "Sidney", "Sydney"),
        listOf("Гонконг", "Hong Kong", "Hong Kong"),
        listOf("Сеул", "Seul", "Seoul"),
    )

    /** Simplified Chinese city names, keyed by the English name (third column above). */
    private val zhCities: Map<String, String> = mapOf(
        "Tirana" to "地拉那", "Melbourne" to "墨尔本", "São Paulo" to "圣保罗", "Frankfurt" to "法兰克福",
        "Berlin" to "柏林", "Tallinn" to "塔林", "Tartu" to "塔尔图", "Madrid" to "马德里",
        "Helsinki" to "赫尔辛基", "Paris" to "巴黎", "London" to "伦敦", "Coventry" to "考文垂",
        "Thessaloniki" to "塞萨洛尼基", "Tel Aviv" to "特拉维夫", "Tokyo" to "东京", "Almaty" to "阿拉木图",
        "Vilnius" to "维尔纽斯", "Riga" to "里加", "Kerkrade" to "凯尔克拉德", "Dronten" to "德龙滕",
        "Warsaw" to "华沙", "Moscow" to "莫斯科", "Singapore" to "新加坡", "Bursa" to "布尔萨",
        "Kyiv" to "基辅", "Dallas" to "达拉斯", "Amsterdam" to "阿姆斯特丹", "Istanbul" to "伊斯坦布尔",
        "Ankara" to "安卡拉", "Stockholm" to "斯德哥尔摩", "Vienna" to "维也纳", "Zurich" to "苏黎世",
        "Prague" to "布拉格", "Bucharest" to "布加勒斯特", "Sofia" to "索菲亚", "Budapest" to "布达佩斯",
        "Athens" to "雅典", "Belgrade" to "贝尔格莱德", "Yerevan" to "埃里温", "Tbilisi" to "第比利斯",
        "Baku" to "巴库", "Dubai" to "迪拜", "Copenhagen" to "哥本哈根", "Oslo" to "奥斯陆",
        "Milan" to "米兰", "Rome" to "罗马", "Munich" to "慕尼黑", "Düsseldorf" to "杜塞尔多夫",
        "Nuremberg" to "纽伦堡", "New York" to "纽约", "Los Angeles" to "洛杉矶", "Miami" to "迈阿密",
        "Toronto" to "多伦多", "Montreal" to "蒙特利尔", "Sydney" to "悉尼", "Hong Kong" to "香港",
        "Seoul" to "首尔",
    )

    private val zhSuffixes: Map<String, String> = mapOf("Bridge" to "桥接")

    /** Suffixes in "(...)" after a country name, [ru, tr, en]. */
    private val suffixes: List<List<String>> = listOf(
        listOf("Мост", "Köprü", "Bridge"),
    )

    private val cityIndex: Map<String, List<String>> by lazy { index(cities, zhCities) }
    private val suffixIndex: Map<String, List<String>> by lazy { index(suffixes, zhSuffixes) }

    private fun index(rows: List<List<String>>, zh: Map<String, String>): Map<String, List<String>> =
        buildMap {
            rows.forEach { row ->
                row.forEach { put(normalize(it), row) }
                zh[row[2]]?.let { put(normalize(it), row) }
            }
        }

    /** The [row] ([ru, tr, en, ...aliases]) in [language]; Chinese comes from the zh tables. */
    private fun pick(row: List<String>, zh: Map<String, String>, language: String?): String =
        if (lang(language) == "zh") zh[row[2]] ?: row[2] else row[slot(language)]

    private val folds: Map<Char, String> = mapOf(
        'ı' to "i", 'ş' to "s", 'ğ' to "g", 'ü' to "u", 'ö' to "o", 'ç' to "c",
        'á' to "a", 'à' to "a", 'â' to "a", 'ã' to "a", 'ä' to "a", 'å' to "a",
        'é' to "e", 'è' to "e", 'ê' to "e", 'ë' to "e",
        'í' to "i", 'ì' to "i", 'î' to "i", 'ï' to "i",
        'ó' to "o", 'ò' to "o", 'ô' to "o", 'õ' to "o", 'ø' to "o",
        'ú' to "u", 'ù' to "u", 'û' to "u",
        'ñ' to "n", 'ß' to "ss", 'ё' to "е", 'й' to "и",
    )

    private val spaces = Regex("""\s+""")
    private val suffixed = Regex("""^(.*?)\s*\(([^()]*)\)$""")

    /** Lower case without diacritics and with single spaces: the key names are compared by. */
    fun normalize(text: String): String {
        val lower = text.trim().lowercase(java.util.Locale.ROOT)
        val out = StringBuilder(lower.length)
        for (ch in lower) {
            // The combining dot "İ".lowercase() leaves behind, and other combining marks.
            if (ch in '̀'..'ͯ') continue
            out.append(folds[ch] ?: ch.toString())
        }
        return out.toString().replace(spaces, " ")
    }

    private fun slot(language: String?): Int {
        val index = ColituLoc.languages.indexOf(language ?: ColituLoc.language)
        return if (index < 0) ColituLoc.languages.indexOf("en") else index
    }

    private fun lang(language: String?): String = ColituLoc.languages[slot(language)]

    /** A city in [language] (the app language by default); unknown text as it is. */
    fun city(raw: String?, language: String? = null): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return text
        val row = cityIndex[normalize(text)] ?: return text
        return pick(row, zhCities, language)
    }

    /**
     * A display name: "Letonya (Köprü)" → "Latvia (Bridge)". The part before a
     * trailing "(...)" is shown as the country name when it is [countryCode]
     * or any known country name; the suffix is translated when known.
     * Otherwise the text stays as it is (a city name is translated).
     */
    fun displayName(raw: String?, countryCode: String? = null, language: String? = null): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return text
        val match = suffixed.matchEntire(text)
        val head = (match?.groupValues?.get(1) ?: text).trim()
        val suffix = match?.groupValues?.get(2)?.trim()
        val code = countryCodeOf(head, countryCode)
        val localizedHead = if (code != null) {
            ColituLoc.countryName(code, lang(language))
        } else {
            val cityRow = cityIndex[normalize(head)]
            if (cityRow == null && match == null) return text
            cityRow?.let { pick(it, zhCities, language) } ?: head
        }
        if (suffix == null) return localizedHead
        if (head.isEmpty()) return text
        val localizedSuffix = suffixIndex[normalize(suffix)]?.let { pick(it, zhSuffixes, language) } ?: suffix
        return "$localizedHead ($localizedSuffix)"
    }

    /**
     * The name ends in the bridge suffix ("Finlandiya (Köprü)", "… (Мост)", "… (Bridge)"): a node
     * whose entry address is in Russia and whose traffic leaves abroad (its country is the exit's).
     */
    fun isBridgeName(raw: String?): Boolean {
        val match = suffixed.matchEntire(raw?.trim().orEmpty()) ?: return false
        return suffixIndex.containsKey(normalize(match.groupValues[2].trim()))
    }

    /** A multihop route name, "Varşova → Frankfurt": each end as a city or a display name. */
    fun route(raw: String?, language: String? = null): String {
        val text = raw?.trim().orEmpty()
        if ('→' !in text) return displayName(text, language = language)
        return text.split('→').joinToString(" → ") { displayName(it, language = language) }
    }

    private fun countryCodeOf(name: String, countryCode: String?): String? {
        if (name.isEmpty()) return null
        val code = countryCode?.trim()?.uppercase().orEmpty()
        if (code.length == 2 && normalize(name) == normalize(code)) return code
        if (name.length == 2 && ColituLoc.hasCountry(name)) return name.uppercase()
        return ColituLoc.countryCodeForName(name)
    }
}
