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
    /** [ru, tr, en] like [ColituLoc.languages], then aliases. */
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

    /** Suffixes in "(...)" after a country name, [ru, tr, en]. */
    private val suffixes: List<List<String>> = listOf(
        listOf("Мост", "Köprü", "Bridge"),
    )

    private val cityIndex: Map<String, List<String>> by lazy { index(cities) }
    private val suffixIndex: Map<String, List<String>> by lazy { index(suffixes) }

    private fun index(rows: List<List<String>>): Map<String, List<String>> =
        buildMap { rows.forEach { row -> row.forEach { put(normalize(it), row) } } }

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
        return row[slot(language)]
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
            cityRow?.get(slot(language)) ?: head
        }
        if (suffix == null) return localizedHead
        if (head.isEmpty()) return text
        val localizedSuffix = suffixIndex[normalize(suffix)]?.get(slot(language)) ?: suffix
        return "$localizedHead ($localizedSuffix)"
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
