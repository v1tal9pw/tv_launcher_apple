param([switch]$Restore, [switch]$SelfTest)

$ErrorActionPreference = 'Stop'
$launcher = 'com.gathin.tvhome/.MainActivity'
$stock = 'com.google.android.apps.tv.launcherx'
$stockHome = "$stock/.home.HomeActivity"
$wizard = 'com.google.android.tungsten.setupwraith'
$homeQuery = 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME'

function HomeComponent($output) {
    return @($output -split "`n" | Where-Object { $_ -match '\S' } | Select-Object -Last 1)[0].Trim()
}

function IsLauncherHome($component) { return $component -match '^com\.gathin\.tvhome/(?:\.|com\.gathin\.tvhome\.)MainActivity$' }
function IsStockHome($component) { return $component -match '^com\.google\.android\.apps\.tv\.launcherx/(?:\.|com\.google\.android\.apps\.tv\.launcherx\.)home\.HomeActivity$' }
function WifiOwners($output) { return @([regex]::Matches($output, 'cname=([A-Za-z0-9._]+)') | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique) }
function WifiNetworkSummary($output) {
    $id = $null
    foreach ($line in $output -split "`n") {
        if ($line -match '^\s*(\*)?\s*ID:\s*(\d+)\s+SSID:') {
            $id = $Matches[2]
            $current = $Matches[1] -eq '*'
        } elseif ($null -ne $id -and $line -match '\bcname=([A-Za-z0-9._]+)') {
            "ID $id; владелец $($Matches[1]); текущая $current"
            $id = $null
        }
    }
}

if ($SelfTest) {
    if (-not (IsStockHome (HomeComponent "priority=2`ncom.google.android.apps.tv.launcherx/.home.HomeActivity"))) { throw 'Не распознан Home Google TV' }
    if (-not (IsLauncherHome (HomeComponent "priority=0`ncom.gathin.tvhome/com.gathin.tvhome.MainActivity"))) { throw 'Не распознан Home лаунчера' }
    if (@(WifiOwners 'cname=com.google.android.tungsten.setupwraith, cname=android.uid.system').Count -ne 2) { throw 'Не распознаны владельцы Wi-Fi' }
    $sample = "* ID: 4 SSID: secret`n cname=android.uid.system`nID: 7 SSID: private`n cname=com.google.android.tungsten.setupwraith"
    if ((@(WifiNetworkSummary $sample) -join '|') -ne 'ID 4; владелец android.uid.system; текущая True|ID 7; владелец com.google.android.tungsten.setupwraith; текущая False') { throw 'Не распознаны ID сетей Wi-Fi' }
    Write-Host 'Самопроверка пройдена.'
    exit 0
}

function Adb([string[]]$arguments) {
    $ErrorActionPreference = 'Continue'
    $output = & $script:adb @arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "ADB: $($output -join ' ')" }
    return ($output -join "`n").Trim()
}

function Shell([string]$command) { return Adb -arguments @('-s', $script:serial, 'shell', $command) }

function Save-Report($lines) {
    Set-Content -LiteralPath $script:reportPath -Value ($lines -join "`r`n") -Encoding UTF8
}

try {
    $found = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($found) { $adb = $found.Source }
    else {
        foreach ($root in @($PSScriptRoot, $env:LOCALAPPDATA, $env:ANDROID_HOME, $env:ANDROID_SDK_ROOT) | Where-Object { $_ }) {
            foreach ($relative in @('adb.exe', 'platform-tools\adb.exe', 'Android\Sdk\platform-tools\adb.exe')) {
                $path = Join-Path $root $relative
                if (Test-Path -LiteralPath $path) { $adb = $path; break }
            }
            if ($adb) { break }
        }
    }
    if (-not $adb) { throw 'Не найден adb.exe. Положите оба BAT/PS1 рядом с platform-tools или добавьте ADB в PATH.' }
    $ip = Read-Host 'IPv4-адрес ТВ'
    $address = $null
    if (-not [Net.IPAddress]::TryParse($ip, [ref]$address) -or $address.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork) { throw 'Неверный IPv4-адрес.' }
    $port = Read-Host 'Порт подключения ADB (не порт сопряжения)'
    [int]$portNumber = 0
    if (-not [int]::TryParse($port, [ref]$portNumber) -or $portNumber -lt 1 -or $portNumber -gt 65535) { throw 'Неверный порт.' }
    $serial = "${ip}:$portNumber"
    Adb -arguments @('connect', $serial) | Out-Host
    if ((Adb -arguments @('-s', $serial, 'get-state')) -ne 'device') { throw 'Нет подключения к ТВ. Сначала выполните сопряжение через прежний BAT.' }

    $reportPath = Join-Path $env:TEMP ('tvhome-home-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.txt')
    $report = @("Режим: $(if ($Restore) { 'восстановление Google TV' } else { 'передача Home' })", "Устройство: $(Shell 'getprop ro.product.model') / Android $(Shell 'getprop ro.build.version.release')", "Главный экран до: $(Shell $homeQuery)")
    Save-Report $report
    if ($Restore) {
        $report += "Восстановление: $(Shell "pm enable --user 0 $stock")"
        $report += "Восстановление мастера Google TV: $(Shell "pm enable --user 0 $wizard")"
        $report += "Назначение Home: $(Shell "cmd package set-home-activity --user 0 $stockHome")"
        $homeNow = HomeComponent (Shell $homeQuery)
        $report += "Главный экран после: $homeNow"
        Save-Report $report
        if (-not (IsStockHome $homeNow)) { throw "Google TV не стал Home. Отчёт: $reportPath" }
        Write-Host 'Google TV восстановлен как главный экран.'
    } else {
        if (-not (IsStockHome (HomeComponent (Shell $homeQuery)))) { throw "Google TV не является текущим Home. Ничего не изменено. Отчёт: $reportPath" }
        if ((Shell 'pm path com.gathin.tvhome') -notmatch '^package:') { throw 'Наш лаунчер не установлен.' }
        $enabledServices = Shell 'settings --user current get secure enabled_accessibility_services'
        $accessibility = Shell 'dumpsys accessibility'
        $autoStart = Shell 'appops get com.gathin.tvhome AUTO_START'
        if (($enabledServices -notmatch 'com\.gathin\.tvhome/(?:\.|com\.gathin\.tvhome\.)InputKeyService') -or
                ($accessibility -notmatch 'Bound services:\{[^}]*Service\[label=(?:Помощник кнопок пульта|Remote button helper)\b') -or
                ($autoStart -notmatch '(?m)^\s*AUTO_START:\s*allow\b')) {
            throw 'Помощник кнопок не подключён или автозапуск не разрешён. Google TV оставлен включённым.'
        }
        $claimants = Shell 'cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME'
        $report += "Доступные Home: $claimants"
        Save-Report $report
        if ($claimants -notmatch 'com\.gathin\.tvhome/(?:\.|com\.gathin\.tvhome\.)MainActivity') { throw 'Наш лаунчер не объявлен как Home.' }
        if ($claimants -notmatch 'FallbackHome') { throw 'Нет резервного Home до разблокировки. Штатный лаунчер оставлен включённым.' }
        if ($claimants -notmatch 'com\.google\.android\.tungsten\.setupwraith/\.RecoveryActivity') { throw 'Мастер Google TV больше не перехватывает Home. Ничего не отключено.' }
        $wifiDump = Shell 'dumpsys wifi'
        $wifiOwners = @(WifiOwners $wifiDump)
        $report += "Владельцы сохранённых Wi-Fi: $($wifiOwners -join ', ')"
        $report += 'Сети Wi-Fi (названия скрыты):'
        $report += @(WifiNetworkSummary $wifiDump)
        $listedIds = @([regex]::Matches((Shell 'cmd wifi list-networks'), '(?m)^\s*(\d+)\s+') | ForEach-Object { $_.Groups[1].Value })
        $report += "ID из списка сохранённых сетей: $($listedIds -join ', ')"
        Save-Report $report
        if ($wifiOwners.Count -eq 0) { throw 'Не удалось проверить владельцев сохранённых Wi-Fi. Ничего не отключено.' }
        if ($wifiOwners -contains $wizard) { throw 'Сохранённая Wi-Fi сеть принадлежит мастеру Google TV. Переподключите её через настройки сети ТВ и повторите запуск. Ничего не отключено.' }
        $backup = Join-Path $PSScriptRoot ('backup\tvhome-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
        New-Item -ItemType Directory -Path $backup -Force | Out-Null
        foreach ($item in @(
            @('packages.txt', 'pm list packages'), @('disabled.txt', 'pm list packages -d'),
            @('getprop.txt', 'getprop'), @('settings-global.txt', 'settings --user current list global'),
            @('settings-secure.txt', 'settings --user current list secure'), @('settings-system.txt', 'settings --user current list system')
        )) { Set-Content -LiteralPath (Join-Path $backup $item[0]) -Value (Shell $item[1]) -Encoding UTF8 }
        $report += 'Резервная копия: папка backup рядом с BAT (не отправляйте её — там могут быть личные данные)'
        Save-Report $report
        $started = Shell "am start -W -a android.intent.action.MAIN -c android.intent.category.HOME -n $launcher"
        $focused = Shell 'dumpsys activity activities'
        $report += "Запуск лаунчера: $started"
        Save-Report $report
        if ($started -notmatch 'Status:\s*ok' -or $focused -notmatch '(?m)(mFocusedApp|topResumedActivity).*com\.gathin\.tvhome/(?:\.|com\.gathin\.tvhome\.)MainActivity') { throw "Лаунчер не открылся. Google TV оставлен включённым. Отчёт: $reportPath" }
        $disabled = $false
        $wizardDisabled = $false
        $committed = $false
        try {
            $disabled = $true
            $report += "Отключение Google TV: $(Shell "pm disable-user --user 0 $stock")"
            Save-Report $report
            if ((Shell "pm list packages -d $stock") -notmatch "(?m)^package:$([regex]::Escape($stock))\r?$") { throw 'Google TV не отключился.' }
            $wizardDisabled = $true
            $report += "Отключение мастера Google TV: $(Shell "pm disable-user --user 0 $wizard")"
            Save-Report $report
            if ((Shell "pm list packages -d $wizard") -notmatch "(?m)^package:$([regex]::Escape($wizard))\r?$") { throw 'Мастер Google TV не отключился.' }
            $report += "Назначение Home: $(Shell "cmd package set-home-activity --user 0 $launcher")"
            $homeNow = HomeComponent (Shell $homeQuery)
            $report += "Главный экран после: $homeNow"
            Save-Report $report
            if (-not (IsLauncherHome $homeNow)) { throw 'TCL не назначил наш лаунчер Home.' }
            $homeStart = Shell 'am start -W -a android.intent.action.MAIN -c android.intent.category.HOME'
            $focused = Shell 'dumpsys activity activities'
            $report += "Открытие Home после назначения: $homeStart"
            Save-Report $report
            if ($homeStart -notmatch 'Status:\s*ok' -or $focused -notmatch '(?m)(mFocusedApp|topResumedActivity).*com\.gathin\.tvhome/(?:\.|com\.gathin\.tvhome\.)MainActivity') { throw 'Home не открыл наш лаунчер.' }
            $committed = $true
        } finally {
            if ($disabled -and -not $committed) {
                try { $report += "Откат Google TV: $(Shell "pm enable --user 0 $stock")" }
                catch { $report += "Ошибка отката Google TV: $($_.Exception.Message)" }
                if ($wizardDisabled) {
                    try { $report += "Откат мастера: $(Shell "pm enable --user 0 $wizard")" }
                    catch { $report += "Ошибка отката мастера: $($_.Exception.Message)" }
                }
                try {
                    $report += "Home Google TV: $(Shell "cmd package set-home-activity --user 0 $stockHome")"
                    $report += "Главный экран после отката: $(HomeComponent (Shell $homeQuery))"
                } catch { $report += "Ошибка проверки отката: $($_.Exception.Message)" }
                Save-Report $report
            }
        }
        Write-Host 'Наш лаунчер назначен Home. Проверьте Home и все нужные кнопки настоящего пульта, затем перезагрузите ТВ.'
    }
    Write-Host "Отчёт: $reportPath"
    Write-Host 'После проверки выключите беспроводную отладку на ТВ.'
} catch {
    Write-Host "Остановлено: $($_.Exception.Message)" -ForegroundColor Red
    if ($reportPath) {
        $report += "Остановлено: $($_.Exception.Message)"
        Save-Report $report
        Write-Host "Отчёт: $reportPath"
    }
    exit 1
} finally {
    if ($adb -and $serial) { try { Adb -arguments @('disconnect', $serial) | Out-Null } catch {} }
}
