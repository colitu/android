package com.v2ray.ang.colitu.l10n

import androidx.compose.runtime.mutableStateOf
import com.tencent.mmkv.MMKV
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * App strings in Russian, Turkish and English, shared word for word with the
 * iOS app (colitu_loc.dart), the website and the Windows app. The language is
 * Compose state, so every screen that reads a string recomposes the moment the
 * user picks another language.
 */
object ColituLoc {
    val languages = listOf("ru", "tr", "en")

    private const val KEY_LANGUAGE = "language"
    private val store by lazy { MMKV.mmkvWithID("COLITU_SETTINGS", MMKV.MULTI_PROCESS_MODE) }

    private val languageState = mutableStateOf(defaultLanguage())

    /** Current language; Compose state, so readers recompose on change. */
    val language: String get() = languageState.value

    fun load() {
        val saved = runCatching { store.decodeString(KEY_LANGUAGE) }.getOrNull()
        if (!saved.isNullOrBlank()) languageState.value = normalize(saved)
    }

    fun setLanguage(value: String?) {
        languageState.value = normalize(value)
        runCatching { store.encode(KEY_LANGUAGE, language) }
    }

    fun normalize(value: String?): String {
        val lower = value.orEmpty().trim().lowercase()
        return if (lower in languages) lower else defaultLanguage()
    }

    /** Russian unless the phone runs in Turkish or English, like the website. */
    fun defaultLanguage(): String = when (Locale.getDefault().language.lowercase()) {
        "tr" -> "tr"
        "en" -> "en"
        else -> "ru"
    }

    operator fun get(key: String): String {
        val values = strings[key] ?: return key
        return values[languages.indexOf(language).coerceAtLeast(0)]
    }

    fun format(key: String, vararg args: Pair<String, Any?>): String {
        var text = get(key)
        args.forEach { (name, value) -> text = text.replace("{$name}", value.toString()) }
        return text
    }

    /** Counted noun with the right plural form ("3 устройства", "5 дней"). */
    fun count(noun: String, n: Int): String {
        val form = when (language) {
            "ru" -> russianForm(n)
            "en" -> if (n == 1) "one" else "many"
            else -> "one"
        }
        return format("$noun.$form", "n" to groupThousands(n.toLong()))
    }

    private fun russianForm(n: Int): String {
        val mod10 = Math.abs(n) % 10
        val mod100 = Math.abs(n) % 100
        return when {
            mod10 == 1 && mod100 != 11 -> "one"
            mod10 in 2..4 && (mod100 < 12 || mod100 > 14) -> "few"
            else -> "many"
        }
    }

    private fun groupThousands(n: Long): String {
        val digits = Math.abs(n).toString()
        val out = StringBuilder()
        digits.forEachIndexed { i, c ->
            if (i > 0 && (digits.length - i) % 3 == 0) out.append(if (language == "en") ',' else ' ')
            out.append(c)
        }
        return if (n < 0) "-$out" else out.toString()
    }

    fun countryName(code: String?): String {
        val upper = code.orEmpty().uppercase()
        val values = countries[upper] ?: return upper
        return values[languages.indexOf(language).coerceAtLeast(0)]
    }

    /** "24 сентября 2026" / "24 Eylül 2026" / "24 September 2026". */
    fun date(value: Instant): String {
        val local = value.atZone(ZoneId.systemDefault())
        val names = months[language] ?: months.getValue("en")
        return "${local.dayOfMonth} ${names[local.monthValue - 1]} ${local.year}"
    }

    fun bytes(bytes: Long): String {
        val units = if (language == "ru") listOf("Б", "КБ", "МБ", "ГБ", "ТБ") else listOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.size - 1) {
            value /= 1024
            unit++
        }
        val text = if (unit == 0) "%.0f".format(Locale.US, value)
        else "%.1f".format(Locale.US, value).removeSuffix(".0")
        return "${if (language == "en") text else text.replace('.', ',')} ${units[unit]}"
    }

    /** Speed in decimal units ("1,2 MB/s"), like the iOS home screen. */
    fun speed(bps: Double): String {
        val units = if (language == "ru") listOf("Б/с", "КБ/с", "МБ/с", "ГБ/с") else listOf("B/s", "KB/s", "MB/s", "GB/s")
        var value = bps
        var unit = 0
        while (value >= 1000 && unit < units.size - 1) {
            value /= 1000
            unit++
        }
        val text = if (unit == 0 || value >= 100) "%.0f".format(Locale.US, value)
        else "%.1f".format(Locale.US, value).removeSuffix(".0")
        return "${if (language == "en") text else text.replace('.', ',')} ${units[unit]}"
    }

    /** Rubles from minor units, formatted like the website ("1 290 ₽"). */
    fun money(minor: Long, currency: String = "RUB"): String {
        val sign = if (minor < 0) "−" else ""
        val absolute = Math.abs(minor)
        val whole = groupThousands(absolute / 100)
        val fraction = absolute % 100
        val symbol = when (currency.uppercase()) {
            "RUB" -> "₽"
            "USD" -> "$"
            "EUR" -> "€"
            "TRY" -> "₺"
            else -> currency.uppercase()
        }
        if (fraction == 0L) return "$sign$whole $symbol"
        val separator = if (language == "en") "." else ","
        return "$sign$whole$separator${fraction.toString().padStart(2, '0')} $symbol"
    }

    fun percent(bps: Int): String {
        val whole = bps / 100
        val fraction = Math.abs(bps) % 100
        if (fraction == 0) return "$whole"
        val text = "%02d".format(fraction).removeSuffix("0")
        return "$whole${if (language == "en") "." else ","}$text"
    }

    fun has(key: String): Boolean = strings.containsKey(key)

    private val months = mapOf(
        "ru" to listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря"),
        "tr" to listOf("Ocak", "Şubat", "Mart", "Nisan", "Mayıs", "Haziran", "Temmuz", "Ağustos", "Eylül", "Ekim", "Kasım", "Aralık"),
        "en" to listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"),
    )

    // key → [ru, tr, en]; generated from the iOS table with Android wording.
    private val strings: Map<String, Array<String>> = hashMapOf(
        "onb.skip" to arrayOf("Пропустить", "Atla", "Skip"),
        "onb.next" to arrayOf("Далее", "İleri", "Next"),
        "onb.start" to arrayOf("Начать", "Başla", "Get started"),
        "onb.badge" to arrayOf("VPN без журналов", "Kayıt tutmayan VPN", "No-logs VPN"),
        "onb.1.title" to arrayOf("Добро пожаловать в Colitu", "Colitu’ya hoş geldiniz", "Welcome to Colitu"),
        "onb.1.sub" to arrayOf("Шифрование трафика, скрытый IP и никаких журналов. Всё, что нужно для свободного интернета.", "Şifreli trafik, gizli IP ve sıfır kayıt. Özgür internet için gereken her şey.", "Encrypted traffic, a hidden IP and no logs. Everything you need for the open internet."),
        "onb.2.title" to arrayOf("Одно касание — и вы под защитой", "Tek dokunuşla koruma", "One tap and you’re protected"),
        "onb.2.sub" to arrayOf("Нажмите кнопку на главном экране. Colitu сам выберет самый быстрый протокол и сервер.", "Ana ekrandaki düğmeye basın. Colitu en hızlı protokolü ve sunucuyu kendisi seçer.", "Press the button on the home screen. Colitu picks the fastest protocol and server for you."),
        "onb.3.title" to arrayOf("Выбирайте локацию", "Konumunuzu seçin", "Pick your location"),
        "onb.3.sub" to arrayOf("Во вкладке «Локации» выберите страну — подключение переключится автоматически.", "“Konumlar” sekmesinden bir ülke seçin; bağlantı otomatik olarak geçer.", "Choose a country in the Locations tab and the connection moves over automatically."),
        "onb.4.title" to arrayOf("Всегда на связи", "Her zaman korumada", "Always protected"),
        "onb.4.sub" to arrayOf("Включите постоянную защиту в разделе «Аккаунт» — Android будет держать VPN включённым сам.", "“Hesap” bölümünden sürekli korumayı açın; Android VPN’i kendi kendine açık tutar.", "Turn on always-on protection under Account and Android keeps the VPN on by itself."),
        "onb.create" to arrayOf("Создать аккаунт", "Hesap oluştur", "Create account"),
        "onb.signIn" to arrayOf("У меня есть аккаунт", "Hesabım var", "I have an account"),
        "home.state.off" to arrayOf("Не подключено", "Bağlı değil", "Not connected"),
        "home.state.on" to arrayOf("Подключено", "Bağlı", "Connected"),
        "home.state.connecting" to arrayOf("Подключение", "Bağlanıyor", "Connecting"),
        "home.state.disconnecting" to arrayOf("Отключение", "Kesiliyor", "Disconnecting"),
        "home.tap" to arrayOf("Нажмите, чтобы подключиться", "Bağlanmak için dokunun", "Tap to connect"),
        "home.tapOff" to arrayOf("Нажмите, чтобы отключить", "Kesmek için dokunun", "Tap to disconnect"),
        "home.upload" to arrayOf("Отправка", "Yükleme", "Upload"),
        "home.download" to arrayOf("Загрузка", "İndirme", "Download"),
        "home.phase.preparing" to arrayOf("Получаем конфигурацию…", "Yapılandırma alınıyor…", "Fetching configuration…"),
        "home.phase.probing" to arrayOf("Выбираем самый быстрый протокол…", "En hızlı protokol seçiliyor…", "Picking the fastest protocol…"),
        "home.phase.starting" to arrayOf("Запускаем туннель…", "Tünel başlatılıyor…", "Starting the tunnel…"),
        "home.phase.verifying" to arrayOf("Проверяем трафик…", "Trafik doğrulanıyor…", "Verifying traffic…"),
        "home.phase.switching" to arrayOf("Пробуем другой протокол…", "Başka protokol deneniyor…", "Trying another protocol…"),
        "home.switchingTransport" to arrayOf("Протокол не отвечает, пробуем другой…", "Protokol yanıt vermiyor, başkası deneniyor…", "Protocol isn’t responding, trying another…"),
        "home.fastest" to arrayOf("Самый быстрый сервер", "En hızlı sunucu", "Fastest server"),
        "home.autoPicked" to arrayOf("Выбран автоматически", "Otomatik seçildi", "Auto-selected"),
        "home.changeServer" to arrayOf("Сменить сервер", "Sunucuyu değiştir", "Change server"),
        "home.protocol" to arrayOf("Протокол", "Protokol", "Protocol"),
        "locations.all" to arrayOf("Все", "Tümü", "All"),
        "locations.ai" to arrayOf("Нейросети", "Yapay zeka", "AI"),
        "locations.streaming" to arrayOf("Кино и сериалы", "Dizi & film", "Movies & TV"),
        "locations.fast" to arrayOf("Быстрые", "Hızlı", "Fast"),
        "locations.free" to arrayOf("Бесплатные", "Ücretsiz", "Free"),
        "locations.pro" to arrayOf("Pro", "Pro", "Pro"),
        "locations.promo" to arrayOf("Серверы по всему миру", "Dünya çapında sunucular", "Get worldwide coverage"),
        "locations.promoSub" to arrayOf("с Colitu Pro", "Colitu Pro ile", "with Colitu Pro"),
        "locations.online" to arrayOf("Онлайн: {n}", "Çevrimiçi: {n}", "{n} online"),
        "plan.heroTitle" to arrayOf("Усильте защиту", "Gizliliğinizi yükseltin", "Upgrade your privacy"),
        "plan.heroSub" to arrayOf("Все серверы, максимальная скорость и постоянная защита с Colitu Pro.", "Colitu Pro ile tüm sunucular, en yüksek hız ve sürekli koruma.", "Every server, top speed and always-on protection with Colitu Pro."),
        "account.features" to arrayOf("Функции", "Özellikler", "Features"),
        "account.connection" to arrayOf("Подключение", "Bağlantı", "Connection"),
        "account.general" to arrayOf("Общие", "Genel", "General"),
        "account.protocol" to arrayOf("Протокол", "Protokol", "Protocol"),
        "account.protocolAuto" to arrayOf("Умный (авто)", "Akıllı (otomatik)", "Smart (auto)"),
        "account.howItWorks" to arrayOf("Как это работает", "Nasıl çalışır", "How it works"),
        "account.on" to arrayOf("Вкл.", "Açık", "On"),
        "account.off" to arrayOf("Выкл.", "Kapalı", "Off"),
        "nav.home" to arrayOf("Главная", "Ana sayfa", "Home"),
        "nav.locations" to arrayOf("Локации", "Konumlar", "Locations"),
        "nav.plan" to arrayOf("Тариф", "Paket", "Plan"),
        "nav.account" to arrayOf("Аккаунт", "Hesap", "Account"),
        "nav.settings" to arrayOf("Настройки", "Ayarlar", "Settings"),
        "loading.session" to arrayOf("Восстанавливаем сеанс…", "Oturumunuz açılıyor…", "Restoring your session…"),
        "status.protected" to arrayOf("Защищено", "Korunuyor", "Protected"),
        "status.unprotected" to arrayOf("Не защищено", "Korunmuyor", "Not protected"),
        "status.connecting" to arrayOf("Подключение…", "Bağlanıyor…", "Connecting…"),
        "status.reconnecting" to arrayOf("Переподключение…", "Yeniden bağlanıyor…", "Reconnecting…"),
        "status.disconnecting" to arrayOf("Отключение…", "Bağlantı kesiliyor…", "Disconnecting…"),
        "home.kicker" to arrayOf("COLITU VPN · Android", "COLITU VPN · Android", "COLITU VPN · Android"),
        "home.title.off" to arrayOf("Соединение не защищено", "Bağlantınız korunmuyor", "Your connection isn’t protected"),
        "home.title.on" to arrayOf("Вы под защитой", "Korunuyorsunuz", "You’re protected"),
        "home.title.connecting" to arrayOf("Защищаем соединение", "Bağlantınız güvenceye alınıyor", "Securing your connection"),
        "home.title.noplan" to arrayOf("Выберите тариф, чтобы начать", "Başlamak için bir paket seçin", "Choose a plan to get started"),
        "home.sub.off" to arrayOf("Нажмите кнопку — трафик будет зашифрован, а IP-адрес скрыт.", "Düğmeye basın; trafiğiniz şifrelenir, IP adresiniz gizlenir.", "Press the button to encrypt your traffic and hide your IP address."),
        "home.sub.on" to arrayOf("Трафик зашифрован и идёт через {server}.", "Trafiğiniz şifreli ve {server} üzerinden geçiyor.", "Your traffic is encrypted and routed through {server}."),
        "home.sub.connecting" to arrayOf("Готовим зашифрованный туннель…", "Şifreli tünel hazırlanıyor…", "Setting up the encrypted tunnel…"),
        "home.sub.noplan" to arrayOf("Все серверы, безлимитный трафик и защита от утечек — в одном тарифе.", "Tüm sunucular, sınırsız trafik ve sızıntı koruması tek pakette.", "Every server, unlimited traffic and leak protection in one plan."),
        "home.connect" to arrayOf("Подключить", "Bağlan", "Connect"),
        "home.disconnect" to arrayOf("Отключить", "Bağlantıyı kes", "Disconnect"),
        "home.cancel" to arrayOf("Отменить", "İptal", "Cancel"),
        "home.session" to arrayOf("Время сеанса", "Oturum süresi", "Session time"),
        "home.publicIp" to arrayOf("Ваш IP", "IP adresiniz", "Your IP"),
        "home.location" to arrayOf("ЛОКАЦИЯ", "KONUM", "LOCATION"),
        "home.change" to arrayOf("Изменить", "Değiştir", "Change"),
        "home.plan" to arrayOf("ТАРИФ", "PAKET", "PLAN"),
        "home.quick" to arrayOf("БЫСТРЫЕ НАСТРОЙКИ", "HIZLI AYARLAR", "QUICK SETTINGS"),
        "home.traffic" to arrayOf("Трафик за период", "Bu dönemki trafik", "Traffic this period"),
        "home.trafficOf" to arrayOf("{used} из {limit}", "{used} / {limit}", "{used} of {limit}"),
        "home.unlimited" to arrayOf("Безлимитный трафик", "Sınırsız trafik", "Unlimited traffic"),
        "home.devices" to arrayOf("Устройства", "Cihazlar", "Devices"),
        "home.offline" to arrayOf("Нет связи с сервером Colitu. Повторяем попытку…", "Colitu sunucusuna ulaşılamıyor. Yeniden deneniyor…", "Can’t reach Colitu right now. Retrying…"),
        "server.auto" to arrayOf("Лучший сервер", "En iyi sunucu", "Best server"),
        "server.autoHint" to arrayOf("Colitu сам выберет самый свободный и стабильный", "Colitu en boş ve kararlı sunucuyu kendisi seçer", "Colitu picks the least busy, most stable one"),
        "server.none" to arrayOf("Серверы появятся здесь", "Sunucular burada görünecek", "Servers will appear here"),
        "server.load.low" to arrayOf("Низкая нагрузка", "Düşük yük", "Low load"),
        "server.load.medium" to arrayOf("Средняя нагрузка", "Orta yük", "Medium load"),
        "server.load.high" to arrayOf("Высокая нагрузка", "Yüksek yük", "High load"),
        "server.offline" to arrayOf("Недоступен", "Çevrimdışı", "Offline"),
        "server.connected" to arrayOf("Подключено", "Bağlı", "Connected"),
        "server.selected" to arrayOf("Выбрано", "Seçili", "Selected"),
        "locations.kicker" to arrayOf("ЛОКАЦИИ", "KONUMLAR", "LOCATIONS"),
        "locations.title" to arrayOf("Выберите локацию", "Bir konum seçin", "Choose a location"),
        "locations.sub" to arrayOf("Серверов онлайн: {n}. При смене локации подключение переключится автоматически.", "Çevrimiçi sunucu: {n}. Konumu değiştirdiğinizde bağlantı otomatik olarak geçer.", "{n} servers online. Switching location moves your connection automatically."),
        "locations.search" to arrayOf("Поиск страны или города", "Ülke veya şehir ara", "Search country or city"),
        "locations.empty" to arrayOf("Ничего не найдено", "Sonuç bulunamadı", "Nothing found"),
        "locations.switching" to arrayOf("Переключаемся на {server}…", "{server} konumuna geçiliyor…", "Switching to {server}…"),
        "locations.switched" to arrayOf("Подключено: {server}", "Bağlandı: {server}", "Connected to {server}"),
        "plan.none" to arrayOf("Нет активного тарифа", "Aktif paket yok", "No active plan"),
        "plan.noneHint" to arrayOf("Выберите тариф, чтобы подключиться.", "Bağlanmak için bir paket seçin.", "Choose a plan to connect."),
        "plan.status.active" to arrayOf("АКТИВЕН", "AKTİF", "ACTIVE"),
        "plan.status.trialing" to arrayOf("ПРОБНЫЙ", "DENEME", "TRIAL"),
        "plan.status.expired" to arrayOf("ИСТЁК", "SÜRESİ DOLDU", "EXPIRED"),
        "plan.status.inactive" to arrayOf("НЕ АКТИВЕН", "PASİF", "INACTIVE"),
        "plan.until" to arrayOf("До {date}", "{date} tarihine kadar", "Until {date}"),
        "plan.left" to arrayOf("осталось {left}", "{left} kaldı", "{left} left"),
        "plan.choose" to arrayOf("Выбрать тариф", "Paket seç", "Choose a plan"),
        "plan.extend" to arrayOf("Продлить", "Süreyi uzat", "Extend"),
        "plan.trialName" to arrayOf("Пробный период", "Deneme süresi", "Free trial"),
        "plan.manageTitle" to arrayOf("Подписка управляется на colitu.com", "Abonelik colitu.com üzerinden yönetilir", "Your subscription is managed on colitu.com"),
        "plan.manageBody" to arrayOf(
            "Тариф, продление и устройства — в личном кабинете. Войдите с той же почтой, что и в приложении.",
            "Paket, süre uzatma ve cihazlar hesabınızdan yönetilir. Uygulamadaki e-posta adresinizle giriş yapın.",
            "Plans, renewals and devices are handled in your account. Sign in with the same e-mail as in the app.",
        ),
        "plan.manageButton" to arrayOf("Открыть app.colitu.com", "app.colitu.com’u aç", "Open app.colitu.com"),
        "plan.refresh" to arrayOf("Обновить статус", "Durumu yenile", "Refresh status"),
        "plan.refreshHint" to arrayOf(
            "После изменений в кабинете вернитесь сюда и обновите статус.",
            "Hesabınızda değişiklik yaptıktan sonra buraya dönüp durumu yenileyin.",
            "After changing something in your account, come back and refresh.",
        ),
        "plan.refreshed" to arrayOf("Статус обновлён", "Durum güncellendi", "Status updated"),
        "plan.manage" to arrayOf("Управлять", "Yönet", "Manage"),
        "plan.lifetime" to arrayOf("Бессрочно", "Süresiz", "No expiry"),
        "day.one" to arrayOf("{n} день", "{n} gün", "{n} day"),
        "day.few" to arrayOf("{n} дня", "{n} gün", "{n} days"),
        "day.many" to arrayOf("{n} дней", "{n} gün", "{n} days"),
        "hour.one" to arrayOf("{n} час", "{n} saat", "{n} hour"),
        "hour.few" to arrayOf("{n} часа", "{n} saat", "{n} hours"),
        "hour.many" to arrayOf("{n} часов", "{n} saat", "{n} hours"),
        "device.one" to arrayOf("{n} устройство", "{n} cihaz", "{n} device"),
        "device.few" to arrayOf("{n} устройства", "{n} cihaz", "{n} devices"),
        "device.many" to arrayOf("{n} устройств", "{n} cihaz", "{n} devices"),
        "pricing.current" to arrayOf("Текущий тариф", "Mevcut paket", "Current plan"),
        "pricing.retry" to arrayOf("Повторить", "Tekrar dene", "Try again"),
        "pay.cancel" to arrayOf("Отменить", "Vazgeç", "Cancel"),
        "account.kicker" to arrayOf("АККАУНТ", "HESAP", "ACCOUNT"),
        "account.title" to arrayOf("Ваш аккаунт", "Hesabınız", "Your account"),
        "account.email" to arrayOf("Электронная почта", "E-posta", "Email"),
        "account.plan" to arrayOf("Тариф", "Paket", "Plan"),
        "account.validUntil" to arrayOf("Действует до", "Geçerlilik", "Valid until"),
        "account.devices" to arrayOf("УСТРОЙСТВА", "CİHAZLAR", "DEVICES"),
        "account.devicesTitle" to arrayOf("Подключено {used} из {limit}", "{limit} cihazdan {used} tanesi bağlı", "{used} of {limit} in use"),
        "account.thisDevice" to arrayOf("ЭТО УСТРОЙСТВО", "BU CİHAZ", "THIS DEVICE"),
        "account.lastSeen" to arrayOf("Активность: {date}", "Son etkinlik: {date}", "Last active {date}"),
        "account.remove" to arrayOf("Отключить устройство", "Cihazı kaldır", "Remove device"),
        "account.removeConfirm" to arrayOf("Отключить «{name}»? На нём потребуется войти снова.", "“{name}” kaldırılsın mı? Bu cihazda yeniden giriş yapmak gerekecek.", "Remove “{name}”? It will need to sign in again."),
        "account.removed" to arrayOf("Устройство отключено", "Cihaz kaldırıldı", "Device removed"),
        "account.manage" to arrayOf("Управлять на colitu.com", "colitu.com’da yönet", "Manage on colitu.com"),
        "account.signOut" to arrayOf("Выйти", "Çıkış yap", "Sign out"),
        "account.signOutConfirm" to arrayOf("Выйти из аккаунта на этом устройстве? VPN будет отключён.", "Bu cihazda hesaptan çıkılsın mı? VPN bağlantısı kesilecek.", "Sign out on this device? The VPN will disconnect."),
        "account.help" to arrayOf("Нужна помощь?", "Yardım mı lazım?", "Need help?"),
        "account.helpHint" to arrayOf("Напишите нам — отвечаем быстро и по-человечески.", "Bize yazın; hızlı ve insan gibi yanıt veririz.", "Write to us. We answer quickly, and a real person reads it."),
        "account.support" to arrayOf("Центр поддержки", "Destek merkezi", "Support center"),
        "account.mail" to arrayOf("Написать на почту", "E-posta gönder", "Email support"),
        "account.mailUnavailable" to arrayOf("Почтовое приложение недоступно. Напишите на support@colitu.com.", "Posta uygulaması açılamadı. support@colitu.com adresine yazın.", "No mail app is available. Write to support@colitu.com."),
        "settings.kicker" to arrayOf("НАСТРОЙКИ", "AYARLAR", "SETTINGS"),
        "settings.title" to arrayOf("Настройки", "Ayarlar", "Settings"),
        "settings.language" to arrayOf("Язык", "Dil", "Language"),
        "settings.connection" to arrayOf("Подключение", "Bağlantı", "Connection"),
        "settings.alwaysOn" to arrayOf("Постоянная защита", "Sürekli koruma", "Always-on protection"),
        "settings.alwaysOnHint" to arrayOf("Android сам держит VPN включённым и восстанавливает его. Откроются настройки VPN: включите «Постоянная VPN» для Colitu.", "Android VPN’i sürekli açık tutar ve kendisi yeniden bağlar. VPN ayarları açılır; Colitu için “Her zaman açık VPN”i etkinleştirin.", "Android keeps the VPN on and restores it by itself. VPN settings open; turn on “Always-on VPN” for Colitu."),
        "settings.dns" to arrayOf("Защита от утечек DNS", "DNS sızıntı koruması", "DNS leak protection"),
        "settings.dnsHint" to arrayOf("DNS-запросы идут только через VPN. Всегда включено.", "DNS sorguları yalnızca VPN üzerinden gider. Her zaman açık.", "DNS lookups only go through the VPN. Always on."),
        "settings.autoConnect" to arrayOf("Автоподключение", "Otomatik bağlan", "Auto-connect"),
        "settings.autoConnectHint" to arrayOf("Подключаться сразу после запуска приложения.", "Uygulama açılır açılmaz bağlan.", "Connect as soon as the app starts."),
        "settings.app" to arrayOf("Приложение", "Uygulama", "App"),
        "settings.about" to arrayOf("О приложении", "Hakkında", "About"),
        "settings.version" to arrayOf("Версия {version}", "Sürüm {version}", "Version {version}"),
        "settings.privacy" to arrayOf("Конфиденциальность", "Gizlilik", "Privacy"),
        "settings.terms" to arrayOf("Условия", "Koşullar", "Terms"),
        "settings.website" to arrayOf("colitu.com", "colitu.com", "colitu.com"),
        "settings.saved" to arrayOf("Сохранено", "Kaydedildi", "Saved"),
        "settings.reconnectHint" to arrayOf("Изменения применятся при следующем подключении.", "Değişiklik bir sonraki bağlantıda uygulanır.", "Changes apply the next time you connect."),
        "about.openSource" to arrayOf("Открытый исходный код", "Açık kaynak", "Open source"),
        "about.openSourceHint" to arrayOf("GPL-3.0 · исходный код на GitHub", "GPL-3.0 · kaynak kodu GitHub'da", "GPL-3.0 · source code on GitHub"),
        "about.credits" to arrayOf("Основано на v2rayNG (GPL-3.0), Xray-core (MPL-2.0) и hev-socks5-tunnel (MIT).", "v2rayNG (GPL-3.0), Xray-core (MPL-2.0) ve hev-socks5-tunnel (MIT) üzerine kuruludur.", "Built on v2rayNG (GPL-3.0), Xray-core (MPL-2.0) and hev-socks5-tunnel (MIT)."),
        "brand.credit" to arrayOf("Colitu — продукт компании {brand}.", "Colitu bir {brand} ürünüdür.", "Colitu is a {brand} product."),
        "auth.kicker" to arrayOf("БЕЗОПАСНО · ПРИВАТНО · БЫСТРО", "GÜVENLİ · ÖZEL · HIZLI", "SECURE · PRIVATE · FAST"),
        "auth.heroTitle" to arrayOf("Свободный интернет в одно касание.", "Özgür internet, tek dokunuşla.", "The open internet, one tap away."),
        "auth.heroSub" to arrayOf("Серверы в разных странах, шифрование трафика и никаких журналов.", "Farklı ülkelerde sunucular, şifreli trafik ve kayıt tutmama.", "Servers in many countries, encrypted traffic and no activity logs."),
        "auth.login" to arrayOf("Вход", "Giriş", "Sign in"),
        "auth.register" to arrayOf("Регистрация", "Kayıt ol", "Sign up"),
        "auth.loginTitle" to arrayOf("С возвращением", "Tekrar hoş geldiniz", "Welcome back"),
        "auth.loginSub" to arrayOf("Войдите в аккаунт Colitu — тот же, что на colitu.com.", "Colitu hesabınızla giriş yapın; colitu.com ile aynı hesap.", "Sign in with your Colitu account, the same one you use on colitu.com."),
        "auth.registerTitle" to arrayOf("Создать аккаунт", "Hesap oluştur", "Create account"),
        "auth.registerSub" to arrayOf("Зарегистрируйтесь по электронной почте и попробуйте Colitu бесплатно 24 часа.", "E-postanızla kaydolun. İlk 24 saatlik denemenizle Colitu’yu tanıyın.", "Sign up with your email and try Colitu free for 24 hours."),
        "auth.email" to arrayOf("Электронная почта", "E-posta", "Email"),
        "auth.emailHint" to arrayOf("you@example.com", "ornek@eposta.com", "you@example.com"),
        "auth.password" to arrayOf("Пароль", "Şifre", "Password"),
        "auth.passwordHint" to arrayOf("Не менее 10 символов", "En az 10 karakter", "At least 10 characters"),
        "auth.passwordRepeat" to arrayOf("Повторите пароль", "Şifreyi tekrarla", "Repeat password"),
        "auth.show" to arrayOf("Показать пароль", "Şifreyi göster", "Show password"),
        "auth.terms" to arrayOf("Я принимаю условия и политику конфиденциальности", "Koşulları ve gizlilik politikasını kabul ediyorum", "I accept the terms and privacy policy"),
        "auth.submitLogin" to arrayOf("Войти", "Giriş yap", "Sign in"),
        "auth.submitRegister" to arrayOf("Создать аккаунт", "Hesap oluştur", "Create account"),
        "auth.forgot" to arrayOf("Забыли пароль?", "Şifremi unuttum", "Forgot password?"),
        "auth.remember" to arrayOf("Вы останетесь в аккаунте на этом устройстве.", "Bu cihazda oturumunuz açık kalır.", "You’ll stay signed in on this device."),
        "auth.err.email" to arrayOf("Введите корректный адрес почты.", "Geçerli bir e-posta adresi girin.", "Enter a valid email address."),
        "auth.err.password" to arrayOf("Пароль должен быть не короче 10 символов.", "Şifre en az 10 karakter olmalı.", "The password must be at least 10 characters."),
        "auth.err.passwordEmpty" to arrayOf("Введите пароль.", "Şifrenizi girin.", "Enter your password."),
        "auth.err.mismatch" to arrayOf("Пароли не совпадают.", "Şifreler eşleşmiyor.", "Passwords don’t match."),
        "auth.err.terms" to arrayOf("Примите условия, чтобы продолжить.", "Devam etmek için koşulları kabul edin.", "Accept the terms to continue."),
        "auth.expired" to arrayOf("Сеанс завершён. Войдите снова.", "Oturumunuz sona erdi. Lütfen tekrar giriş yapın.", "Your session ended. Please sign in again."),
        "err.credentials" to arrayOf("Неверная почта или пароль.", "E-posta veya şifre hatalı.", "Email or password is incorrect."),
        "err.registration" to arrayOf("Эту почту нельзя зарегистрировать: возможно, аккаунт уже существует.", "Bu e-posta ile kayıt yapılamıyor; hesap zaten olabilir.", "This email can’t be registered. It may already have an account."),
        "err.rateLimited" to arrayOf("Слишком много попыток. Подождите минуту.", "Çok fazla deneme. Lütfen bir dakika bekleyin.", "Too many attempts. Please wait a minute."),
        "err.deviceLimit" to arrayOf("Достигнут лимит устройств вашего тарифа. Отключите старое устройство на colitu.com/devices или добавьте место в тариф.", "Paketinizin cihaz sınırına ulaşıldı. colitu.com/devices adresinden eski bir cihazı kaldırın veya paketinize cihaz ekleyin.", "Your plan’s device limit is reached. Remove an old device at colitu.com/devices or add a seat to your plan."),
        "err.noPlan" to arrayOf("Тариф не активен. Выберите тариф, чтобы подключиться.", "Paketiniz aktif değil. Bağlanmak için bir paket seçin.", "Your plan isn’t active. Choose a plan to connect."),
        "err.quota" to arrayOf("Лимит трафика на этот период исчерпан.", "Bu dönemin trafik kotası doldu.", "You’ve used this period’s traffic allowance."),
        "err.noServers" to arrayOf("Сейчас нет доступных серверов. Попробуйте чуть позже.", "Şu anda uygun sunucu yok. Biraz sonra tekrar deneyin.", "No server is available right now. Please try again shortly."),
        "err.network" to arrayOf("Нет связи с сервером Colitu. Проверьте интернет.", "Colitu sunucusuna ulaşılamadı. İnternet bağlantınızı kontrol edin.", "Can’t reach Colitu. Check your internet connection."),
        "err.unreachable" to arrayOf("Сервер не отвечает. Попробуйте другую локацию.", "Sunucu yanıt vermiyor. Başka bir konum deneyin.", "The server isn’t responding. Try another location."),
        "err.verify" to arrayOf("VPN подключился, но трафик не идёт. Закройте другие VPN-приложения и попробуйте снова.", "VPN bağlandı ama trafik akmıyor. Diğer VPN uygulamalarını kapatıp tekrar deneyin.", "The VPN connected but traffic isn’t flowing. Close other VPN apps and try again."),
        "err.tun" to arrayOf("Android не смог запустить VPN-туннель. Закройте другие VPN-приложения и попробуйте снова.", "Android VPN tünelini başlatamadı. Diğer VPN uygulamalarını kapatıp tekrar deneyin.", "Android couldn’t start the VPN tunnel. Close other VPN apps and try again."),
        "err.permission" to arrayOf("Доступ к VPN не разрешён. Нажмите «Подключить» и разрешите запрос Android.", "VPN izni verilmedi. “Bağlan”a basıp Android’in isteğine izin verin.", "VPN permission was denied. Press Connect and allow Android’s request."),
        "err.engine" to arrayOf("VPN-движок не запустился с этой конфигурацией. Попробуйте ещё раз.", "VPN motoru bu yapılandırmayla başlayamadı. Tekrar deneyin.", "The VPN engine couldn’t start with this configuration. Please try again."),
        "err.blocked" to arrayOf("Подключение сейчас недоступно: {reason}", "Bağlantı şu anda kullanılamıyor: {reason}", "Connecting isn’t available right now: {reason}"),
        "err.updateRequired" to arrayOf("Обновите Colitu, чтобы подключиться.", "Bağlanmak için Colitu’yu güncelleyin.", "Update Colitu to connect."),
        "err.maintenance" to arrayOf("Идут технические работы. Попробуйте чуть позже.", "Bakım çalışması sürüyor. Biraz sonra tekrar deneyin.", "Maintenance is in progress. Please try again shortly."),
        "err.generic" to arrayOf("Что-то пошло не так. Попробуйте ещё раз.", "Bir şeyler ters gitti. Tekrar deneyin.", "Something went wrong. Please try again."),
        "err.payment" to arrayOf("Не удалось создать платёж. Попробуйте другой способ оплаты.", "Ödeme oluşturulamadı. Başka bir ödeme yöntemi deneyin.", "The payment couldn’t be created. Try another payment method."),
        "err.disconnect" to arrayOf("Не удалось отключиться. Попробуйте ещё раз.", "Bağlantı kesilemedi. Tekrar deneyin.", "Couldn’t disconnect. Please try again."),
        "err.reconnectFailed" to arrayOf("Соединение потеряно. Нажмите «Подключить», чтобы восстановить.", "Bağlantı koptu. Yeniden bağlanmak için “Bağlan”a basın.", "The connection dropped. Press Connect to restore it."),
        "err.region" to arrayOf("Регистрация и вход из вашего региона сейчас недоступны. Если включён другой VPN или прокси, отключите его и попробуйте снова.", "Bulunduğunuz bölgeden kayıt ve giriş şu anda kullanılamıyor. Başka bir VPN veya proxy açıksa kapatıp tekrar deneyin.", "Sign-up and sign-in aren’t available from your region right now. If another VPN or proxy is on, turn it off and try again."),
        "err.trialUsed" to arrayOf("На этом телефоне пробный период уже использован. Выберите тариф на colitu.com, чтобы продолжить.", "Bu telefonda deneme süresi daha önce kullanıldı. Devam etmek için colitu.com’dan bir paket seçin.", "The free trial was already used on this phone. Choose a plan at colitu.com to continue."),
        "err.notVerified" to arrayOf("Сначала подтвердите адрес электронной почты.", "Önce e-posta adresinizi doğrulayın.", "Please confirm your email address first."),
        "verify.kicker" to arrayOf("ПОСЛЕДНИЙ ШАГ", "SON ADIM", "ONE LAST STEP"),
        "verify.title" to arrayOf("Подтвердите почту", "E-postanızı doğrulayın", "Confirm your email"),
        "verify.sub" to arrayOf("Мы отправили 6-значный код на {email}. Введите его, чтобы активировать аккаунт и пробный период.", "{email} adresine 6 haneli bir kod gönderdik. Hesabınızı ve deneme sürenizi açmak için kodu girin.", "We sent a 6-digit code to {email}. Enter it to activate your account and free trial."),
        "verify.code" to arrayOf("Код из письма", "E-postadaki kod", "Code from the email"),
        "verify.submit" to arrayOf("Подтвердить", "Doğrula", "Confirm"),
        "verify.resend" to arrayOf("Отправить код ещё раз", "Kodu tekrar gönder", "Send the code again"),
        "verify.resendIn" to arrayOf("Отправить снова через {n} с", "{n} sn sonra tekrar gönderebilirsiniz", "Send again in {n}s"),
        "verify.sent" to arrayOf("Новый код отправлен. Проверьте и папку «Спам».", "Yeni kod gönderildi. Gereksiz (spam) klasörünü de kontrol edin.", "A new code is on its way. Check your spam folder too."),
        "verify.other" to arrayOf("Войти в другой аккаунт", "Başka bir hesapla giriş yap", "Use a different account"),
        "verify.hint" to arrayOf("Код действует 15 минут. Письмо не пришло? Проверьте «Спам» или отправьте код ещё раз.", "Kod 15 dakika geçerlidir. E-posta gelmediyse spam klasörüne bakın veya kodu yeniden gönderin.", "The code is valid for 15 minutes. No email? Check spam or send the code again."),
        "verify.web" to arrayOf("Код можно ввести и на colitu.com: приложение продолжит само, как только почта будет подтверждена.", "Kodu colitu.com üzerinden de girebilirsiniz; doğrulandığında uygulama kendiliğinden devam eder.", "You can also enter the code on colitu.com; the app carries on by itself once your email is confirmed."),
        "verify.openMail" to arrayOf("Открыть почту", "E-posta uygulamasını aç", "Open email app"),
        "update.headline" to arrayOf("Доступна версия Colitu {version}", "Colitu {version} hazır", "Colitu {version} is available"),
        "update.body" to arrayOf("Обновить сейчас? В старой версии подключение и вход в аккаунт могут работать с ошибками.", "Şimdi güncellemek ister misiniz? Eski sürümde bağlantı ve hesap işlemlerinde sorunlar yaşanabilir.", "Update now? The old version may have problems connecting or signing in."),
        "update.force" to arrayOf("Эта версия больше не поддерживается. Обновите приложение, чтобы продолжить.", "Bu sürüm artık desteklenmiyor. Devam etmek için uygulamayı güncelleyin.", "This version is no longer supported. Update the app to continue."),
        "update.size" to arrayOf("Размер загрузки: {size} МБ", "İndirme boyutu: {size} MB", "Download size: {size} MB"),
        "update.now" to arrayOf("Обновить", "Güncelle", "Update"),
        "update.later" to arrayOf("Позже", "Daha sonra", "Later"),
        "update.downloading" to arrayOf("Загрузка… {n}%", "İndiriliyor… %{n}", "Downloading… {n}%"),
        "update.install" to arrayOf("Установить", "Yükle", "Install"),
        "update.permission" to arrayOf("Разрешите Colitu устанавливать обновления, затем вернитесь в приложение.", "Colitu'nun güncelleme yüklemesine izin verin, ardından uygulamaya geri dönün.", "Allow Colitu to install updates, then come back to the app."),
        "update.failed" to arrayOf("Не удалось загрузить обновление. Проверьте интернет и попробуйте снова.", "Güncelleme indirilemedi. İnternet bağlantınızı kontrol edip tekrar deneyin.", "The update could not be downloaded. Check your connection and try again."),
        "update.retry" to arrayOf("Повторить", "Tekrar dene", "Try again"),
        "update.check" to arrayOf("Проверить обновления", "Güncellemeleri denetle", "Check for updates"),
        "update.latest" to arrayOf("У вас последняя версия.", "En son sürümü kullanıyorsunuz.", "You have the latest version."),
        "verify.done" to arrayOf("Почта подтверждена. Добро пожаловать в Colitu!", "E-postanız doğrulandı. Colitu’ya hoş geldiniz!", "Email confirmed. Welcome to Colitu!"),
        "verify.err.length" to arrayOf("Введите все 6 цифр кода.", "Kodun 6 hanesini de girin.", "Enter all 6 digits of the code."),
        "verify.err.invalid" to arrayOf("Неверный код. Проверьте письмо и попробуйте снова.", "Kod hatalı. E-postayı kontrol edip tekrar deneyin.", "That code isn’t right. Check the email and try again."),
        "verify.err.expired" to arrayOf("Срок действия кода истёк. Отправьте новый.", "Kodun süresi doldu. Yeni bir kod isteyin.", "The code has expired. Request a new one."),
        "verify.err.wait" to arrayOf("Новый код можно запросить через минуту.", "Yeni kodu bir dakika sonra isteyebilirsiniz.", "You can request a new code in a minute."),
        "verify.err.mail" to arrayOf("Сейчас не удаётся отправить письмо. Попробуйте чуть позже или напишите в поддержку.", "Şu anda e-posta gönderilemiyor. Biraz sonra tekrar deneyin veya destekle iletişime geçin.", "We can’t send email right now. Try again shortly or contact support."),
        "cat.streaming" to arrayOf("Стриминг", "Streaming", "Streaming"),
        "cat.gaming" to arrayOf("Игры", "Oyun", "Gaming"),
        "cat.privacy" to arrayOf("Приватность", "Gizlilik", "Privacy"),
        "cat.speed" to arrayOf("Скорость", "Hız", "Speed"),
        "cat.torrent" to arrayOf("Торренты", "Torrent", "Torrent"),
        "cat.ai" to arrayOf("ИИ", "Yapay zekâ", "AI"),
        "cat.empty" to arrayOf("В этой категории пока нет серверов.", "Bu kategoride henüz sunucu yok.", "No servers in this category yet."),
        "nav.support" to arrayOf("Поддержка", "Destek", "Support"),
        "support.title" to arrayOf("Живая поддержка", "Canlı destek", "Live support"),
        "support.sub" to arrayOf("Напишите нам прямо из приложения. Отвечает живой человек, обычно в течение часа.", "Bize doğrudan uygulamadan yazın. Gerçek bir kişi, genellikle bir saat içinde yanıt verir.", "Write to us right from the app. A real person answers, usually within an hour."),
        "support.new" to arrayOf("Новое обращение", "Yeni talep", "New request"),
        "support.empty" to arrayOf("Обращений пока нет. Опишите проблему — мы поможем.", "Henüz talebiniz yok. Sorununuzu anlatın, yardımcı olalım.", "No requests yet. Tell us what’s wrong and we’ll help."),
        "support.subject" to arrayOf("Тема", "Konu", "Subject"),
        "support.subjectHint" to arrayOf("Например: не подключается", "Örn: Bağlanamıyorum", "For example: can’t connect"),
        "support.message" to arrayOf("Сообщение", "Mesaj", "Message"),
        "support.messageHint" to arrayOf("Опишите, что происходит и что вы уже пробовали…", "Ne olduğunu ve neler denediğinizi yazın…", "Describe what’s happening and what you’ve tried…"),
        "support.reply" to arrayOf("Напишите ответ…", "Yanıtınızı yazın…", "Write a reply…"),
        "support.attach" to arrayOf("Прикрепить файл", "Dosya ekle", "Attach a file"),
        "support.attachHint" to arrayOf("Фото, PDF, TXT, ZIP · до 10 МБ", "Fotoğraf, PDF, TXT, ZIP · en fazla 10 MB", "Photos, PDF, TXT, ZIP · up to 10 MB"),
        "support.diagnostics" to arrayOf("Приложить диагностику", "Tanılama bilgisini ekle", "Include diagnostics"),
        "support.diagnosticsHint" to arrayOf("Версия приложения и Android, модель телефона, сеть, состояние подключения и последние ошибки. Без истории посещений.", "Uygulama ve Android sürümü, telefon modeli, ağ, bağlantı durumu ve son hatalar. Gezinme geçmişi gönderilmez.", "App and Android version, phone model, network, connection state and recent errors. No browsing history."),
        "support.create" to arrayOf("Отправить обращение", "Talebi gönder", "Send request"),
        "support.cancel" to arrayOf("Отмена", "Vazgeç", "Cancel"),
        "support.team" to arrayOf("Поддержка Colitu", "Colitu Destek", "Colitu Support"),
        "support.status.waiting" to arrayOf("ЖДЁТ ОТВЕТА", "YANIT BEKLİYOR", "AWAITING REPLY"),
        "support.status.open" to arrayOf("В РАБОТЕ", "İNCELENİYOR", "IN PROGRESS"),
        "support.status.resolved" to arrayOf("РЕШЕНО", "ÇÖZÜLDÜ", "RESOLVED"),
        "support.status.closed" to arrayOf("ЗАКРЫТО", "KAPANDI", "CLOSED"),
        "support.closed" to arrayOf("Обращение закрыто. Создайте новое, если нужна помощь.", "Bu talep kapatıldı. Yardım gerekirse yeni bir talep açın.", "This request is closed. Start a new one if you need help."),
        "support.newReply" to arrayOf("Новый ответ поддержки", "Destekten yeni yanıt", "New reply from support"),
        "support.sent" to arrayOf("Обращение отправлено. Мы ответим здесь и по почте.", "Talebiniz gönderildi. Buradan ve e-postayla yanıt vereceğiz.", "Request sent. We’ll answer here and by email."),
        "support.err.subject" to arrayOf("Укажите тему и сообщение.", "Konu ve mesajı yazın.", "Add a subject and a message."),
        "support.err.file" to arrayOf("Файл больше 10 МБ или такой тип не поддерживается.", "Dosya 10 MB’tan büyük veya bu tür desteklenmiyor.", "The file is over 10 MB or the type isn’t supported."),
        "support.err.files" to arrayOf("Можно прикрепить не больше 5 файлов.", "En fazla 5 dosya ekleyebilirsiniz.", "You can attach up to 5 files."),
        "support.err.unavailable" to arrayOf("Поддержка в приложении временно недоступна. Напишите на support@colitu.com.", "Uygulama içi destek geçici olarak kapalı. support@colitu.com adresine yazın.", "In-app support is unavailable right now. Write to support@colitu.com."),
        "support.help" to arrayOf("Справочный центр", "Yardım merkezi", "Help centre"),
        "support.back" to arrayOf("Все обращения", "Tüm talepler", "All requests"),
        "info.reconnected" to arrayOf("Соединение восстановлено", "Bağlantı yeniden kuruldu", "Connection restored"),
        "info.disconnected" to arrayOf("VPN отключён", "VPN bağlantısı kesildi", "VPN disconnected"),
        "settings.alwaysOnOpen" to arrayOf("Открыть настройки VPN", "VPN ayarlarını aç", "Open VPN settings"),
        "settings.alwaysOnState" to arrayOf("Постоянная VPN включена в Android", "Android’de her zaman açık VPN etkin", "Always-on VPN is on in Android"),
        "settings.killSwitch" to arrayOf("Блокировать без VPN", "VPN yokken interneti engelle", "Block connections without VPN"),
        "settings.killSwitchHint" to arrayOf("Включается там же, в настройках VPN Android.", "Android VPN ayarlarında, aynı yerden açılır.", "Turned on in the same place, Android’s VPN settings."),
        "update.title" to arrayOf("Доступна новая версия", "Yeni sürüm hazır", "A new version is available"),
        "update.action" to arrayOf("Обновить", "Güncelle", "Update"),
        "vpn.permissionTitle" to arrayOf("Разрешите VPN-подключение", "VPN bağlantısına izin verin", "Allow the VPN connection"),
        "err.clock" to arrayOf("На устройстве неверные дата или время. Включите «Дата и время сети» в Настройки › Система › Дата и время и попробуйте снова.", "Cihazınızın tarihi veya saati yanlış. Ayarlar › Sistem › Tarih ve saat bölümünden otomatik tarih ve saati açıp yeniden deneyin.", "This device’s date or time is wrong. Turn on automatic date and time in Settings › System › Date & time and try again."),
        // Android TV
        "tv.press" to arrayOf("Нажмите OK на пульте, чтобы подключиться", "Bağlanmak için kumandada OK tuşuna basın", "Press OK on the remote to connect"),
        "tv.pressOff" to arrayOf("Нажмите OK, чтобы отключиться", "Bağlantıyı kesmek için OK tuşuna basın", "Press OK to disconnect"),
        "tv.qr.title" to arrayOf("Откройте на телефоне", "Telefonunuzda açın", "Open on your phone"),
        "tv.qr.sub" to arrayOf("Наведите камеру телефона на QR-код.", "Telefonunuzun kamerasını QR koda tutun.", "Point your phone’s camera at the QR code."),
        "tv.qr.close" to arrayOf("Закрыть", "Kapat", "Close"),
    )

    private val countries: Map<String, Array<String>> = hashMapOf(
        "AE" to arrayOf("ОАЭ", "BAE", "United Arab Emirates"),
        "AM" to arrayOf("Армения", "Ermenistan", "Armenia"),
        "AR" to arrayOf("Аргентина", "Arjantin", "Argentina"),
        "AT" to arrayOf("Австрия", "Avusturya", "Austria"),
        "AU" to arrayOf("Австралия", "Avustralya", "Australia"),
        "AZ" to arrayOf("Азербайджан", "Azerbaycan", "Azerbaijan"),
        "BE" to arrayOf("Бельгия", "Belçika", "Belgium"),
        "BG" to arrayOf("Болгария", "Bulgaristan", "Bulgaria"),
        "BR" to arrayOf("Бразилия", "Brezilya", "Brazil"),
        "BY" to arrayOf("Беларусь", "Belarus", "Belarus"),
        "CA" to arrayOf("Канада", "Kanada", "Canada"),
        "CH" to arrayOf("Швейцария", "İsviçre", "Switzerland"),
        "CN" to arrayOf("Китай", "Çin", "China"),
        "CY" to arrayOf("Кипр", "Kıbrıs", "Cyprus"),
        "CZ" to arrayOf("Чехия", "Çekya", "Czechia"),
        "DE" to arrayOf("Германия", "Almanya", "Germany"),
        "DK" to arrayOf("Дания", "Danimarka", "Denmark"),
        "EE" to arrayOf("Эстония", "Estonya", "Estonia"),
        "ES" to arrayOf("Испания", "İspanya", "Spain"),
        "FI" to arrayOf("Финляндия", "Finlandiya", "Finland"),
        "FR" to arrayOf("Франция", "Fransa", "France"),
        "GB" to arrayOf("Великобритания", "Birleşik Krallık", "United Kingdom"),
        "GE" to arrayOf("Грузия", "Gürcistan", "Georgia"),
        "GR" to arrayOf("Греция", "Yunanistan", "Greece"),
        "HK" to arrayOf("Гонконг", "Hong Kong", "Hong Kong"),
        "HU" to arrayOf("Венгрия", "Macaristan", "Hungary"),
        "IE" to arrayOf("Ирландия", "İrlanda", "Ireland"),
        "IL" to arrayOf("Израиль", "İsrail", "Israel"),
        "IN" to arrayOf("Индия", "Hindistan", "India"),
        "IS" to arrayOf("Исландия", "İzlanda", "Iceland"),
        "IT" to arrayOf("Италия", "İtalya", "Italy"),
        "JP" to arrayOf("Япония", "Japonya", "Japan"),
        "KR" to arrayOf("Южная Корея", "Güney Kore", "South Korea"),
        "KZ" to arrayOf("Казахстан", "Kazakistan", "Kazakhstan"),
        "LT" to arrayOf("Литва", "Litvanya", "Lithuania"),
        "LU" to arrayOf("Люксембург", "Lüksemburg", "Luxembourg"),
        "LV" to arrayOf("Латвия", "Letonya", "Latvia"),
        "MD" to arrayOf("Молдова", "Moldova", "Moldova"),
        "NL" to arrayOf("Нидерланды", "Hollanda", "Netherlands"),
        "NO" to arrayOf("Норвегия", "Norveç", "Norway"),
        "PL" to arrayOf("Польша", "Polonya", "Poland"),
        "PT" to arrayOf("Португалия", "Portekiz", "Portugal"),
        "RO" to arrayOf("Румыния", "Romanya", "Romania"),
        "RS" to arrayOf("Сербия", "Sırbistan", "Serbia"),
        "RU" to arrayOf("Россия", "Rusya", "Russia"),
        "SE" to arrayOf("Швеция", "İsveç", "Sweden"),
        "SG" to arrayOf("Сингапур", "Singapur", "Singapore"),
        "SK" to arrayOf("Словакия", "Slovakya", "Slovakia"),
        "TR" to arrayOf("Турция", "Türkiye", "Türkiye"),
        "UA" to arrayOf("Украина", "Ukrayna", "Ukraine"),
        "US" to arrayOf("США", "ABD", "United States"),
        "UZ" to arrayOf("Узбекистан", "Özbekistan", "Uzbekistan"),
    )
}
