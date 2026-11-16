# Запускайте tcl-button-helper.bat на компьютере с Windows в одной сети с телевизором.
# Сначала выполняется диагностика. Разрешение appops меняется только при подтверждённом отказе TCL.
param([switch]$SelfTest)

$ErrorActionPreference = 'Stop'
$package = 'com.gathin.tvhome'

function AutoStartDenial($ops, $log) {
    $blockedOps = [regex]::Matches($ops, '(?m)^\s*(?<name>APP_AUTO_START|AUTO_START):\s*(ignore|deny)\b')
    $blocked = @($log -split "`n" | Where-Object {
        $_ -match 'TclAppBoot' -and $_ -match 'com\.gathin\.tvhome' -and
        $_ -match 'InputKeyService' -and $_ -match 'bind' -and
        $_ -match 'forbid|isAllow\s*=\s*false'
    }).Count -gt 0
    if ($blockedOps.Count -eq 1 -and $blocked) { return $blockedOps[0].Groups['name'].Value }
    return ''
}

if ($SelfTest) {
    if ((AutoStartDenial '  AUTO_START: ignore' '[TclAppBoot] forbid bind service com.gathin.tvhome/.InputKeyService') -ne 'AUTO_START') { throw 'Ошибка проверки AUTO_START' }
    if ((AutoStartDenial '  APP_AUTO_START: deny' '[TclAppBoot] bind_service isAllow = false com.gathin.tvhome/.InputKeyService') -ne 'APP_AUTO_START') { throw 'Ошибка проверки APP_AUTO_START' }
    if (AutoStartDenial '  AUTO_START: ignore' 'No TCL binding denial') { throw 'Ложное срабатывание' }
    if (AutoStartDenial '  AUTO_START: ignore' '[TclAppBoot] forbid bind service com.gathin.tvhome/.HomeKeyService') { throw 'Определена не та служба' }
    if (AutoStartDenial "AUTO_START: ignore`nAPP_AUTO_START: ignore" '[TclAppBoot] forbid bind service com.gathin.tvhome/.InputKeyService') { throw 'Неоднозначное разрешение appops' }
    Write-Host 'Самопроверка пройдена.'
    exit 0
}

function Find-Adb {
    $found = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($found) { return $found.Source }
    $roots = @($PSScriptRoot, $env:LOCALAPPDATA, $env:ANDROID_HOME, $env:ANDROID_SDK_ROOT) | Where-Object { $_ }
    foreach ($root in $roots) {
        foreach ($relative in @('adb.exe', 'platform-tools\adb.exe', 'Android\Sdk\platform-tools\adb.exe')) {
            $path = Join-Path $root $relative
            if (Test-Path -LiteralPath $path) { return $path }
        }
    }
    throw 'Не найден adb.exe. Установите Google SDK Platform-Tools: https://developer.android.com/tools/releases/platform-tools'
}

function Invoke-Adb([string[]]$arguments) {
    $ErrorActionPreference = 'Continue'
    $output = & $script:adb @arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Ошибка ADB: $($output -join ' ')" }
    return ($output -join "`n").Trim()
}

function Invoke-Shell([string]$command) {
    return Invoke-Adb -arguments @('-s', $script:serial, 'shell', $command)
}

function Read-Port([string]$prompt) {
    $text = Read-Host $prompt
    [int]$number = 0
    if (-not [int]::TryParse($text, [ref]$number) -or $number -lt 1 -or $number -gt 65535) { throw 'Порт должен быть числом от 1 до 65535.' }
    return $number
}

function Snapshot([string]$label) {
    $enabled = Invoke-Shell 'settings --user current get secure accessibility_enabled'
    $list = Invoke-Shell 'settings --user current get secure enabled_accessibility_services'
    $ops = Invoke-Shell "appops get $package"
    $dump = Invoke-Shell 'dumpsys accessibility'
    $log = Invoke-Shell 'logcat -d -t 5000 -v time'
    $markerAt = if ($script:marker) { $log.IndexOf($script:marker) } else { -1 }
    $attemptLog = if ($markerAt -ge 0) { $log.Substring($markerAt) } else { '' }
    $selectedDump = @($dump -split "`n" | Where-Object { $_ -match 'Bound services|Binding services|Crashed services|InputKeyService' } | Select-Object -Last 20)
    $selectedLog = @($log -split "`n" | Where-Object { $_ -match 'TVHomeDiag|RemoteButtonHelper|AccessibilityManagerService|AndroidRuntime' -or ($_ -match 'TclAppBoot' -and $_ -match 'com\.gathin\.tvhome') } | Select-Object -Last 70)
    return [pscustomobject]@{
        Label = $label; Enabled = $enabled; Listed = ($list -match 'com\.gathin\.tvhome/(\.InputKeyService|com\.gathin\.tvhome\.InputKeyService)');
        Ops = $ops; Dump = ($selectedDump -join "`n"); Log = ($selectedLog -join "`n"); AttemptLog = $attemptLog
    }
}

function Report($snapshot) {
    $listed = if ($snapshot.Listed) { 'да' } else { 'нет' }
    return @(
        "[$($snapshot.Label)]",
        "accessibility_enabled=$($snapshot.Enabled)",
        "InputKeyService в списке: $listed",
        'Разрешения appops:', $snapshot.Ops,
        'Состояние спецвозможностей:', $snapshot.Dump,
        'Последние записи лога:', $snapshot.Log, ''
    ) -join "`r`n"
}

try {
    $adb = Find-Adb
    Write-Host "Найден ADB: $adb"
    $ip = Read-Host 'IPv4-адрес телевизора (указан в настройках сети)'
    $address = $null
    if (-not [Net.IPAddress]::TryParse($ip, [ref]$address) -or $address.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork) { throw 'Введите корректный IPv4-адрес.' }
    $port = Read-Port 'Порт подключения ADB (не порт сопряжения)'
    $serial = "${ip}:$port"
    try {
        Invoke-Adb -arguments @('connect', $serial) | Out-Host
        if ((Invoke-Adb -arguments @('-s', $serial, 'get-state')) -ne 'device') { throw 'Нет подключения к телевизору' }
    } catch {
        Read-Host 'Если на ТВ появился запрос доступа для этого компьютера, подтвердите его и нажмите Enter' | Out-Null
        try {
            Invoke-Adb -arguments @('connect', $serial) | Out-Host
            if ((Invoke-Adb -arguments @('-s', $serial, 'get-state')) -ne 'device') { throw 'Нет подключения к телевизору' }
        } catch {
            Write-Host 'При беспроводной отладке откройте на ТВ «Сопряжение с помощью кода».'
            $pairPort = Read-Port 'Порт сопряжения ADB, указанный на ТВ'
            $pairCode = Read-Host 'Шестизначный код сопряжения'
            if ($pairCode -notmatch '^\d{6}$') { throw 'Код сопряжения должен состоять из шести цифр.' }
            Invoke-Adb -arguments @('pair', "${ip}:$pairPort", $pairCode) | Out-Host
            Invoke-Adb -arguments @('connect', $serial) | Out-Host
            if ((Invoke-Adb -arguments @('-s', $serial, 'get-state')) -ne 'device') { throw 'ADB не авторизован. Подтвердите запрос на телевизоре.' }
        }
    }
    $installed = Invoke-Shell "pm path $package"
    if ($installed -notmatch '^package:') { throw "Приложение $package не установлено на этом телевизоре." }
    $packageDump = Invoke-Shell "dumpsys package $package"
    if ($packageDump -notmatch 'InputKeyService') { throw 'Установленный APK не содержит InputKeyService. Сначала обновите лаунчер.' }
    $version = @($packageDump -split "`n" | Where-Object { $_ -match 'versionCode=|InputKeyService' } | Select-Object -First 8) -join "`n"
    $device = "$(Invoke-Shell 'getprop ro.product.model') / Android $(Invoke-Shell 'getprop ro.build.version.release') / $(Invoke-Shell 'getprop ro.build.display.id')"
    $directory = Join-Path $env:TEMP ('tvhome-button-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    $before = Snapshot 'до попытки включения'
    $reportPath = Join-Path $directory 'report.txt'
    Set-Content -LiteralPath $reportPath -Value "Устройство: $device`r`nПриложение: $version`r`n$(Report $before)" -Encoding UTF8
    $marker = 'TVHOME_' + [guid]::NewGuid().ToString('N')
    Invoke-Shell "log -p i -t TVHomeDiag $marker" | Out-Null
    Invoke-Shell 'am start -a android.settings.ACCESSIBILITY_SETTINGS' | Out-Null
    Read-Host 'Попробуйте включить «Помощник кнопок пульта» на ТВ, затем нажмите Enter' | Out-Null
    $after = Snapshot 'после попытки включения'
    $report = "Устройство: $device`r`nПриложение: $version`r`n$(Report $before)`r`n$(Report $after)"
    Set-Content -LiteralPath $reportPath -Value $report -Encoding UTF8
    $op = AutoStartDenial $after.Ops $after.AttemptLog
    if ($op) {
        Write-Host "TCL запретил запуск службы: $op заблокирован. Разрешаю только эту операцию..."
        $report += "`r`nПопытка установить ${op}=allow после подтверждённого отказа TclAppBoot."
        Set-Content -LiteralPath $reportPath -Value $report -Encoding UTF8
        Invoke-Shell "appops set $package $op allow" | Out-Null
        Invoke-Shell 'appops write-settings' | Out-Null
        $verified = Invoke-Shell "appops get $package $op"
        if ($verified -notmatch "(?m)^\s*${op}:\s*allow\b") { throw "Не удалось сохранить $op. Отчёт находится в $directory" }
        $report += "`r`nУстановлено ${op}=allow. Прежнее значение указано в разделе «после попытки включения»."
        Set-Content -LiteralPath $reportPath -Value $report -Encoding UTF8
        Invoke-Shell 'am start -a android.settings.ACCESSIBILITY_SETTINGS' | Out-Null
        Read-Host 'Ещё раз включите помощник на ТВ, затем нажмите Enter' | Out-Null
        $report += "`r`n" + (Report (Snapshot 'после исправления AUTO_START'))
    } else {
        $report += "`r`nОднозначная причина в AUTO_START не найдена. Системные настройки не менялись."
    }
    Set-Content -LiteralPath $reportPath -Value $report -Encoding UTF8
    Write-Host "Отчёт сохранён: $reportPath"
    Write-Host 'Перед отправкой отчёта проверьте, нет ли в нём личных данных.'
    Write-Host 'После диагностики выключите беспроводную/сетевую отладку на ТВ.'
    Invoke-Adb -arguments @('disconnect', $serial) | Out-Null
} catch {
    Write-Host "Остановлено: $($_.Exception.Message)" -ForegroundColor Red
    if ($reportPath -and (Test-Path -LiteralPath $reportPath)) { Write-Host "Неполный отчёт: $reportPath" }
    if ($adb -and $serial) { try { Invoke-Adb -arguments @('disconnect', $serial) | Out-Null } catch {} }
    exit 1
}
