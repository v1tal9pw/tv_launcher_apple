# Personal TV updates

## TCL C6K — v63 (3.60)

- Плавная панель управления без микро-пролагов: размытый фон панели готовится заранее в фоне при загрузке обоев (`0 мс` на UI-потоке), заменены тяжёлые `clipPath` на аппаратный `BitmapShader`, добавлен плавный VSync-старт и tvOS-кривая открытия/закрытия (`PathInterpolator(0.16, 1, 0.3, 1)`).
- Автоназвание папок по типу приложений: при создании новой папки автоматически предлагается категория по приложению (*Кино и ТВ*, *Музыка*, *Игры*, *Браузеры*, *VPN и сеть*, *Утилиты*, *Магазины приложений*, *Общение*, *Новости*, *Фото и медиа*, *Умный дом*, *Карты*).
- Системные звуки навигации везде: единый движок `NavSound` теперь озвучивает переходы и подтверждения не только на главном экране, но и в панели управления, настройках лаунчера, подменю функций, окне очистки, меню источников и библиотеке приложений.
- Настройки «Свечение от приложения»: добавлен выбор режима свечения (*За доком*, новое Ambilight-свечение *По краю экрана*, *Горизонт* и *Выключено* — полное отключение), регулировка интенсивности (`Выкл`, `20%–100%`), отдельный переключатель свечения вокруг плитки и живой предпросмотр в настройках.
- Удалён раздел и фоновый слой «Небо на фоне» для более чистого интерфейса и меньшей нагрузки на систему.

## TCL C6K — v62 (3.59)

- Системная очистка через локальный ADB: кнопка «Очистка системы» в панели управления закрывает фоновые сторонние и тяжёлые медиаприложения (`am force-stop`), очищает фоновые процессы (`am kill-all`) и кэш (`pm trim-caches`), автоматически защищая сам лаунчер, активное приложение, VPN, клавиатуру и службы спецвозможностей, и показывает уведомление о количестве закрытых приложений и освобождённой ОЗУ.
- Обои в стиле Apple в 4K (`3840×2160`): добавлены 6 встроенных 4K-обоев (*Apple Aurora*, *Monterey Waves*, *Ventura Bloom*, *Sonoma Horizon*, *Sequoia Twilight*, *Tahoe Liquid Glass*) с полноцветным декодированием (`ARGB_8888`) без цветовых полос; *Apple Aurora 4K* используется по умолчанию.
- Главное меню: нажатие кнопки «Вверх» на главном экране больше не открывает панель управления (панель по-прежнему открывается кнопкой настроек, шестерёнкой на пульте и удержанием «Домой»).
- Источники поверх приложений: выбор «Источники» в панели управления поверх YouTube и других приложений теперь открывает стеклянный список HDMI / AV / Live TV прямо в оверлее без запуска лаунчера и без закрытия активного приложения; «Назад» возвращает фокус на плитку «Источники».
- Bluetooth → «Пульты и аксессуары»: убран переход в экран поиска/сопряжения `AddAccessoryActivity`. Теперь открывается раздел «Пульты и аксессуары» (`SliceActivity` / `CONNECTED_DEVICES` / системные настройки), при наличии локального ADB право `WRITE_SECURE_SETTINGS` для прямого открытия выдаётся автоматически при первом нажатии.

## TCL C6K — v61 (3.58)

- Панель управления: выход по кнопке «Назад» из любых настроек теперь возвращает обратно в панель управления с фокусом на нажатой плитке (на главном экране и поверх других приложений).
- Bluetooth: исправлено открытие раздела Bluetooth на TCL C6K / Google TV 14 (поддержка сопряжения аксессуаров `CONNECT_INPUT` с запросом разрешения `BLUETOOTH_CONNECT`, системного выбора устройств и slice-маршрута).
- Кнопка «шестерёнка» на пульте (включая код TCL `83`): открывает панель управления и закрывает её при повторном нажатии по той же логике, что и удержание кнопки «Домой».
- Оптимизация кода и размера APK (−465 КБ): удалена неиспользуемая интеграция Shizuku, оставлены ресурсы `ru`/`en`, убран дубликат обоев и оптимизированы ресурсы, видеообои, блюр, шрифты, сеть, локальный ADB и кеш обложек.

## TCL C6K — v59 (3.56)

- Панель управления с датой, часами и плитками в две колонки в стеклянном оформлении.
- Wi-Fi, источники и настройки перенесены с главного экрана в панель. Bluetooth открывает список устройств.
- Док автоматически опускается при возврате выбора на его приложения.
- Очистка по 27 компонентам профиля CleanYourTV для TCL C6K, выбор пакетов, резервные копии и восстановление через локальный ADB.
- Погода повторяет загрузку после восстановления сети, показывает статус ошибок и использует язык системы. Подписи переведены на русский и английский.
- Выбор своих фото и видеообоев с телевизора или флешки. Добавлено видео побережья без звука; видеоплеер освобождается при уходе с главного экрана.
- Отдельные карточки продолжения просмотра включены по умолчанию для приложений, публикующих данные Android TV. Ранее сохранённое отключение сохраняется.
- Исправлены права запуска скриптов, переводы, документация и проверки единственного помощника кнопок.

Сборка, модульные тесты и lint проверены локально. Новые функции, визуальные переходы, очистка, видеообои и нагрузка на физическом TCL C6K пока не проверены.
YouTube, Кинопоиск и другие приложения показывают карточки только при наличии опубликованной ими ссылки на продолжение.
Видеообои поддерживают локальные файлы до Full HD, 30 кадров/с при наличии сведений о частоте кадров и 200 МБ; при ошибке показывается стоп-кадр.

Соавторы — [v1tal9](https://github.com/v1tal9pw) и [Денис (sicarioden)](https://github.com/sicarioden).
Помощь в разработке — OpenAI Codex.

## TCL C6K — v58 (3.55)

This version is prepared for the TCL C6K with Google TV 14. The Home handover
and Remote Button Helper were checked on that TV. Other TV models may need
different package names and button mappings.

Co-authors: [v1tal9](https://github.com/v1tal9pw) and
[Denis (sicarioden)](https://github.com/sicarioden).
Development assistance: OpenAI Codex (AI assistant).

The Windows helpers are in this folder:

- `tcl-button-helper.bat` checks and repairs the remote button service.
- `tcl-home-handover.bat` checks Wi-Fi ownership, backs up TV settings, and
  makes this launcher Home by disabling the two competing Google TV Home apps.
- `tcl-home-restore.bat` restores Google TV as Home.

Keep each BAT beside its matching PS1 file. The Home handover stops before
changing packages if a saved Wi-Fi network belongs to the Google TV setup app.
AirPlay packages are left enabled; check AirPlay and physical remote buttons
after a handover and again after reboot. The BAT files contain no TV IP,
password, ADB key, or personal backup.

This copy checks the latest public release of
`v1tal9pw/tv_launcher_apple` when Home opens, at most once every
24 hours. If its tag is a higher `v<versionCode>` and it contains
`tvhome-<versionCode>.apk`, the TV offers to download and install it. Android
asks for approval to install from this app and to confirm each update.

## First-time setup

1. Create a **public, empty** GitHub repository named `tv_launcher_apple` in
   the `v1tal9pw` account. Do not add a README or licence on GitHub.
2. Push this source tree to its `main` branch. Keep local build output,
   `local.properties`, TV backups, and `.idea` files out of the commit.
3. In the repository, open **Settings → Secrets and variables → Actions** and
   add `TVHOME_DEBUG_KEYSTORE_B64`. Its value is a single-line Base64 encoding
   of this computer's `%USERPROFILE%\.android\debug.keystore`. Keep the
   keystore and its Base64 text private. The release job refuses to publish an
   APK unless its signing certificate matches the launcher already installed
   on the TCL C6K.
4. Make sure GitHub Actions is enabled and the default `GITHUB_TOKEN` can write
   repository contents (**Settings → Actions → General → Workflow permissions**).
5. Push the first successful release tag matching `versionCode`. The release
   job will run tests, lint and the size gate, then publish an APK named for
   that version code as a GitHub Release asset.

For each later release, increase `versionCode` in `app/build.gradle`, commit
and push the source, then push the matching `v<versionCode>` tag. Tag the
commit containing that version. The workflow is
`../.github/workflows/personal-tv-release.yml`.

The TV must be online. Open the launcher after the release is published; the
next check offers the update. If the previous check was less than 24 hours
ago, wait until the next day. Installation needs the TV's on-screen approval.
