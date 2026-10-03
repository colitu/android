# Colitu VPN для Android

[![Build](https://img.shields.io/github/actions/workflow/status/colitu/colitu-android/ci.yml?branch=main&style=flat-square&label=build&labelColor=101014)](https://github.com/colitu/colitu-android/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/colitu/colitu-android?style=flat-square&labelColor=101014&color=7c6cff)](https://github.com/colitu/colitu-android/releases/latest)
[![License](https://img.shields.io/badge/license-GPL--3.0-7c6cff?style=flat-square&labelColor=101014)](LICENSE)
[![Colitu Network](https://img.shields.io/endpoint?url=https%3A%2F%2Fstatus.colitu.com%2Fapi%2Fgithub-badge%3Fcomponent%3Dnetwork&style=flat-square)](https://status.colitu.com)

[English](README.md) · **Русский**

Android-клиент [Colitu VPN](https://colitu.com) с открытым исходным кодом.
Интерфейс написан на Jetpack Compose, а VPN работает на ядре Xray через
`VpnService`. Все данные об аккаунте, тарифе и серверах приложение получает
через Colitu API.

| | |
|---|---|
| Пакет | `com.colitulu` |
| Min / target SDK | 24 (Android 7.0) / 36 |
| Языки | русский, английский, турецкий (переключаются в приложении мгновенно) |
| Сайт | <https://colitu.com> |
| Лицензия | [GPL-3.0](LICENSE) |

> Для подключения нужен аккаунт Colitu. В приложении нет зашитых серверов:
> список серверов и профили подключения Colitu API выдаёт отдельно для каждого устройства.

## Возможности

- **Автоматический выбор протокола.** Hysteria2, VLESS Reality, Trojan и
  Shadowsocks проверяются параллельно. Приложение запускает самый быстрый,
  проверяет туннель реальным запросом и при неудаче переключается на следующий.
- **Весь аккаунт прямо в приложении.** Знакомство с приложением, вход и
  регистрация, подтверждение e-mail, статус тарифа, устройства, статистика
  трафика и поддержка с перепиской по обращениям. В приложении ничего не
  продаётся: тариф оформляется и продлевается в личном кабинете app.colitu.com.
- **Выбор локации** с задержкой в реальном времени; предпочитаемая локация запоминается.
- **Интерфейс для Android TV** (leanback-лаунчер, управление с пульта).
- **Проверенные обновления внутри приложения** только в APK с colitu.com
  (flavor `direct`): манифест релиза должен быть подписан нашим ключом ECDSA,
  APK — совпадать по SHA-256, а обновление с другой подписью Android не
  установит. Сборки для Play и F-Droid обновляет магазин.
- **Безопасное хранение токенов.** Токены сессии и VPN-профили шифруются
  AES-GCM ключом из Android Keystore (на устройствах с неисправным Keystore —
  закрытым ключом приложения).
- **Закрыто для других приложений.** Локальный SOCKS-вход Xray получает
  случайный порт и учётную запись на каждое подключение, внутренние сообщения
  приложения не экспортируются, нет URL-схемы и плагина Tasker, через которые
  можно добавить сервер или включить/выключить туннель. При подключении
  запросы к API идут через туннель, для api.colitu.com закреплены сертификаты.

## Как устанавливается соединение

1. `POST /auth/login` (или `/auth/register`) возвращает пару access/refresh токенов.
2. `POST /devices/register` привязывает установку к слоту устройства.
3. `GET /client/bootstrap`, `/me`, `/me/entitlement` и `/servers` заполняют интерфейс.
4. При выборе локации вызывается `PUT /me/preferences`, затем `GET /config`
   возвращает конфигурацию, привязанную к устройству: основной профиль и запасные.
5. `XrayMobileAdapter` превращает её в конфиг Xray, который запускается через
   `VpnService` и [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel).

Цены, доступность и лимиты устройств приложение само не вычисляет: источник
истины всегда сервер.

## Структура проекта

```
android/                      проект Android Studio (Gradle, Kotlin)
  app/src/main/java/com/v2ray/ang/
    colitu/
      api/                    HTTP-клиент, менеджер токенов, защищённое хранилище
      app/ColituController.kt управление подключением и переключение протоколов
      data/, repository/      модели API и репозитории
      design/                 тема, шрифт Colitu Sans, иконки, анимации частиц
      screens/                экраны Compose (главная, локации, тариф, аккаунт, поддержка…)
      l10n/                   строки ru / en / tr
      update/                 проверяемый механизм самообновления
    …                         среда выполнения туннеля (core, service, fmt, handler)
  app/libs/                   готовые libv2ray.aar и libhev-socks5-tunnel.so
AndroidLibXrayLite/           сабмодуль: исходники libv2ray.aar
hev-socks5-tunnel/            сабмодуль: исходники libhev-socks5-tunnel.so
docs/                         заметки о релизах и описании в магазине
fastlane/                     метаданные для магазина
```

## Сборка

Нужны JDK 21 и Android SDK (platform 36). Нативные библиотеки уже собраны и
лежат в `android/app/libs`, поэтому сабмодули нужны только для их пересборки
(`compile-hevtun.sh`, AndroidLibXrayLite).

```sh
git clone https://github.com/cyberlexs/colitu-android.git
cd colitu-android/android
./gradlew assemblePlaystoreDebug          # debug APK
./gradlew testPlaystoreDebugUnitTest      # юнит-тесты
```

Есть два варианта сборки (product flavors): `playstore` и `fdroid`.

### Другой адрес API

```sh
./gradlew assemblePlaystoreDebug -PCOLITU_API_BASE_URL=https://staging.example.com/api/v1
```

### Подпись релиза

Релизная сборка подписывается, только если хранилище ключей передано через
переменные окружения (`COLITU_ANDROID_KEYSTORE_PATH`,
`COLITU_ANDROID_STORE_PASSWORD`, `COLITU_ANDROID_KEY_ALIAS`,
`COLITU_ANDROID_KEY_PASSWORD`) или через локальный `signing.properties`, который
игнорируется git. Ключи никогда не попадают в репозиторий. Подробности в
[`docs/release.md`](docs/release.md).

## Безопасность

Если вы нашли уязвимость, пожалуйста, не создавайте публичный issue. Напишите
на **support@colitu.com**, и мы с вами свяжемся.

## Лицензия

Colitu VPN для Android распространяется по лицензии
[GNU General Public License v3.0](LICENSE). В состав входят компоненты с
открытым исходным кодом (Xray-core, hev-socks5-tunnel и другие), которые
сохраняют свои лицензии; полный список — в файле [NOTICE](NOTICE). Те же
уведомления приложение показывает из `app/src/main/assets/open_source_licenses.html`.

Название и логотип «Colitu» являются товарными знаками Colitu и не подпадают
под действие GPL. Если вы распространяете изменённую версию, используйте
собственное название и оформление.
