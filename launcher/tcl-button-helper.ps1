# Запускайте tcl-button-helper.bat на компьютере с Windows в одной сети с телевизором.
# Сначала выполняется диагностика. ADB меняет разрешения и состояние службы только при подтверждённом отказе TCL.
param([switch]$SelfTest)

$ErrorActionPreference = 'Stop'
$package = 'com.gathin.tvhome'
$revision = '2026-11-16-boot'

function ExactPackageVersion($listing, $name) {
    $entries = [regex]::Matches($listing, "(?m)^package:$([regex]::Escape($name)) versionCode:(\d+)\r?$")
    if ($entries.Count -ne 1) { throw "Не удалось определить версию пакета $name." }
    return $entries[0].Groups[1].Value
}

function AutoStartDenial($ops, $log) {
    $blocked = @($log -split "`n" | Where-Object {
        $_ -match 'TclAppBoot' -and $_ -match 'com\.gathin\.tvhome' -and
        $_ -match 'InputKeyService' -and $_ -match 'bind' -and
        $_ -match 'forbid|isAllow\s*=\s*false'
    }).Count -gt 0
    if ($blocked) { return BlockedAutoStart $ops }
    return ''
}

function BlockedAutoStart($ops) {
    $blockedOps = [regex]::Matches($ops, '(?m)^\s*(?<name>APP_AUTO_START|AUTO_START):\s*(ignore|deny)\b')
    if ($blockedOps.Count -eq 1) { return $blockedOps[0].Groups['name'].Value }
    return ''
}

function RestrictedSettingsDenial($ops, $log) {
    return ($ops -match '(?m)^\s*ACCESS_RESTRICTED_SETTINGS:\s*(deny|ignore)\b' -and
        $log -match 'Skipping enabling service disallowed by device admin policy: ComponentInfo\{com\.gathin\.tvhome/(?:com\.gathin\.tvhome\.)?InputKeyService\}')
}

function ServiceListWithHelper($list) {
    $service = 'com.gathin.tvhome/com.gathin.tvhome.InputKeyService'
    if ($list -eq 'null' -or $list -eq '') { return $service }
    if ($list -notmatch '^[A-Za-z0-9_.]+/[A-Za-z0-9_.]+(:[A-Za-z0-9_.]+/[A-Za-z0-9_.]+)*$') { throw 'Непонятный формат списка служб. Изменение остановлено.' }
    if (@($list -split ':') -contains $service -or @($list -split ':') -contains 'com.gathin.tvhome/.InputKeyService') { return $list }
    return "${list}:$service"
}

function ServiceListWithoutHelper($list) {
    if ($list -eq 'null' -or $list -eq '') { return '' }
    ServiceListWithHelper $list | Out-Null # Проверка формата перед передачей значения в shell.
    return (@($list -split ':' | Where-Object { $_ -notin @('com.gathin.tvhome/.InputKeyService', 'com.gathin.tvhome/com.gathin.tvhome.InputKeyService') }) -join ':')
}

function ServiceBound($dump) {
    $boundStart = $dump.IndexOf('Bound services:')
    $bindingStart = if ($boundStart -ge 0) { $dump.IndexOf('Binding services:', $boundStart) } else { -1 }
    return ($boundStart -ge 0 -and $bindingStart -gt $boundStart -and
        $dump.Substring($boundStart, $bindingStart - $boundStart) -match 'com\.gathin\.tvhome/(?:com\.gathin\.tvhome\.)?InputKeyService|Service\[label=(?:Помощник кнопок пульта|Remote button helper)\b')
}

if ($SelfTest) {
    if ((ExactPackageVersion "package:com.gathin.tvhome.old versionCode:42`npackage:com.gathin.tvhome versionCode:57" $package) -ne '57') { throw 'Ошибка проверки версии пакета' }
    if ((AutoStartDenial '  AUTO_START: ignore' '[TclAppBoot] forbid bind service com.gathin.tvhome/.InputKeyService') -ne 'AUTO_START') { throw 'Ошибка проверки AUTO_START' }
    if ((AutoStartDenial '  APP_AUTO_START: deny' '[TclAppBoot] bind_service isAllow = false com.gathin.tvhome/.InputKeyService') -ne 'APP_AUTO_START') { throw 'Ошибка проверки APP_AUTO_START' }
    if (AutoStartDenial '  AUTO_START: ignore' 'No TCL binding denial') { throw 'Ложное срабатывание' }
    if (AutoStartDenial '  AUTO_START: ignore' '[TclAppBoot] forbid bind service com.gathin.tvhome/.HomeKeyService') { throw 'Определена не та служба' }
    if (AutoStartDenial "AUTO_START: ignore`nAPP_AUTO_START: ignore" '[TclAppBoot] forbid bind service com.gathin.tvhome/.InputKeyService') { throw 'Неоднозначное разрешение appops' }
    if ((BlockedAutoStart 'AUTO_START: ignore; rejectTime=+12m') -ne 'AUTO_START') { throw 'Не распознан запрет автозапуска' }
    if (BlockedAutoStart "AUTO_START: ignore`nAPP_AUTO_START: ignore") { throw 'Неоднозначный запрет автозапуска' }
    if (-not (RestrictedSettingsDenial 'ACCESS_RESTRICTED_SETTINGS: deny' 'Skipping enabling service disallowed by device admin policy: ComponentInfo{com.gathin.tvhome/com.gathin.tvhome.InputKeyService}')) { throw 'Не найден подтверждённый отказ restricted settings' }
    if (RestrictedSettingsDenial 'ACCESS_RESTRICTED_SETTINGS: deny' 'No accessibility denial') { throw 'Ложное срабатывание restricted settings' }
    if (RestrictedSettingsDenial 'ACCESS_RESTRICTED_SETTINGS: deny' 'Skipping enabling service disallowed by device admin policy: ComponentInfo{com.gathin.tvhome/com.gathin.tvhome.HomeKeyService}') { throw 'Определена не та restricted служба' }
    if ((ServiceListWithHelper 'com.example/.OtherService') -ne 'com.example/.OtherService:com.gathin.tvhome/com.gathin.tvhome.InputKeyService') { throw 'Потеря другой службы' }
    if ((ServiceListWithHelper 'null') -ne 'com.gathin.tvhome/com.gathin.tvhome.InputKeyService') { throw 'Ошибка пустого списка служб' }
    if ((ServiceListWithHelper 'com.gathin.tvhome/.InputKeyService') -ne 'com.gathin.tvhome/.InputKeyService') { throw 'Повторная служба' }
    if ((ServiceListWithoutHelper 'com.example/.OtherService:com.gathin.tvhome/.InputKeyService') -ne 'com.example/.OtherService') { throw 'Потеря другой службы при повторном подключении' }
    try { ServiceListWithHelper 'com.example/.Other;id' | Out-Null; throw 'Принят опасный список служб' } catch { if ($_.Exception.Message -eq 'Принят опасный список служб') { throw } }
    if (-not (ServiceBound "Bound services:{Service[label=Помощник кнопок пульта, capabilities=8]}`nBinding services:{}")) { throw 'Подключённая служба не распознана' }
    if (ServiceBound "Bound services:{}`nBinding services:{Service[label=Помощник кнопок пульта]}") { throw 'Служба ещё не подключена' }
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

function Write-ServiceList($list) {
    if ($list -eq 'null') { Invoke-Shell 'settings --user current delete secure enabled_accessibility_services' | Out-Null }
    elseif ($list -eq '') { Invoke-Shell "settings --user current put secure enabled_accessibility_services ''" | Out-Null }
    else {
        ServiceListWithHelper $list | Out-Null # Проверяет формат перед передачей строки оболочке Android.
        Invoke-Shell "settings --user current put secure enabled_accessibility_services $list" | Out-Null
    }
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
    $bound = ServiceBound $dump
    return [pscustomobject]@{
        Label = $label; Enabled = $enabled; List = $list; Listed = ($list -match 'com\.gathin\.tvhome/(\.InputKeyService|com\.gathin\.tvhome\.InputKeyService)'); Bound = $bound;
        Ops = $ops; Dump = ($selectedDump -join "`n"); Log = ($selectedLog -join "`n"); AttemptLog = $attemptLog
    }
}

function Report($snapshot) {
    $listed = if ($snapshot.Listed) { 'да' } else { 'нет' }
    $bound = if ($snapshot.Bound) { 'да' } else { 'нет' }
    return @(
        "[$($snapshot.Label)]",
        "accessibility_enabled=$($snapshot.Enabled)",
        "InputKeyService в списке: $listed",
        "InputKeyService подключена системой: $bound",
        'Разрешения appops:', $snapshot.Ops,
        'Состояние спецвозможностей:', $snapshot.Dump,
        'Последние записи лога:', $snapshot.Log, ''
    ) -join "`r`n"
}

try {
    Write-Host "Сценарий: $revision"
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
    $version = ExactPackageVersion (Invoke-Shell "cmd package list packages --show-versioncode $package") $package
    $foreground = @((Invoke-Shell 'dumpsys activity activities') -split "`n" | Where-Object { $_ -match 'mResumedActivity|topResumedActivity' } | Select-Object -First 2) -join "`n"
    $homeResolver = Invoke-Shell 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME'
    $device = "$(Invoke-Shell 'getprop ro.product.model') / Android $(Invoke-Shell 'getprop ro.build.version.release') / $(Invoke-Shell 'getprop ro.build.display.id')"
    $directory = Join-Path $env:TEMP ('tvhome-button-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    $before = Snapshot 'до попытки включения'
    $reportPath = Join-Path $directory 'report.txt'
    Set-Content -LiteralPath $reportPath -Value "Сценарий: $revision`r`nУстройство: $device`r`nПакет: $package, версия: $version`r`nАктивное окно: $foreground`r`nГлавный экран по умолчанию: $homeResolver`r`n$(Report $before)" -Encoding UTF8
    $initialList = $before.List
    $initialEnabled = $before.Enabled
    if ($initialEnabled -notmatch '^[01]$') { throw 'Непонятное состояние accessibility_enabled. Изменение остановлено.' }
    $targetList = ServiceListWithHelper $initialList
    $withoutHelper = ServiceListWithoutHelper $initialList
    $report = "Сценарий: $revision`r`nУстройство: $device`r`nПакет: $package, версия: $version`r`nАктивное окно: $foreground`r`nГлавный экран по умолчанию: $homeResolver`r`n$(Report $before)`r`nИсходные настройки: список служб=$initialList; accessibility_enabled=$initialEnabled."
    Set-Content -LiteralPath $reportPath -Value $report -Encoding UTF8
    $marker = 'TVHOME_' + [guid]::NewGuid().ToString('N')
    Invoke-Shell "log -p i -t TVHomeDiag $marker" | Out-Null
    try {
        if ($before.Listed -and -not $before.Bound) {
            Write-ServiceList $withoutHelper
            Start-Sleep -Seconds 1
        }
        Write-ServiceList $targetList
        Invoke-Shell 'settings --user current put secure accessibility_enabled 1' | Out-Null
        Start-Sleep -Seconds 3
        $after = Snapshot 'после попытки включения через ADB'
    } catch {
        $trialError = $_.Exception.Message
        $undoErrors = @()
        try { Write-ServiceList $initialList } catch { $undoErrors += "список служб: $($_.Exception.Message)" }
        try { Invoke-Shell "settings --user current put secure accessibility_enabled $initialEnabled" | Out-Null } catch { $undoErrors += "accessibility_enabled: $($_.Exception.Message)" }
        $undoResult = if ($undoErrors.Count) { $undoErrors -join '; ' } else { 'прежние настройки восстановлены' }
        $report += "`r`nПервая попытка через ADB не удалась: $trialError`r`nОткат: $undoResult"
        Set-Content -LiteralPath $reportPath -Value $report -Encoding UTF8
        throw "Не удалось выполнить первую попытку через ADB: $trialError. Откат: $undoResult"
    }
    $report += "`r`n" + (Report $after)
    Set-Content -LiteralPath $reportPath -Value $report -Encoding UTF8
    $op = AutoStartDenial $after.Ops $after.AttemptLog
    if ($after.Enabled -eq '1' -and $after.Listed -and $after.Bound) {
        $report += "`r`nПомощник кнопок подключён после включения через ADB."
        $startupOp = BlockedAutoStart $after.Ops
        if ($startupOp) {
            $oldStartupMode = [regex]::Match($after.Ops, "(?m)^\s*${startupOp}:\s*(deny|ignore)\b").Groups[1].Value
            $report += "`r`nПосле предыдущей перезагрузки помощник отключился; ${startupOp}=$oldStartupMode. Разрешаю автозапуск только этому пакету."
            Set-Content -LiteralPath $reportPath -Value $report -Encoding UTF8
            try {
                Invoke-Shell "appops set $package $startupOp allow" | Out-Null
                Invoke-Shell 'appops write-settings' | Out-Null
                if ((Invoke-Shell "appops get $package $startupOp") -notmatch "(?m)^\s*${startupOp}:\s*allow\b") { throw "${startupOp} не сохранился." }
                $report += "`r`n${startupOp}=allow сохранён. Устойчивость после перезагрузки ещё нужно проверить на ТВ."
            } catch {
                $startupError = $_.Exception.Message
                try { Invoke-Shell "appops set $package $startupOp $oldStartupMode" | Out-Null; Invoke-Shell 'appops write-settings' | Out-Null } catch { $startupError += "; откат не удался: $($_.Exception.Message)" }
                $report += "`r`nНе удалось разрешить автозапуск: $startupError"
                Set-Content -LiteralPath $reportPath -Value $report -Encoding UTF8
                throw $startupError
            }
        }
        Write-Host 'Помощник кнопок подключён системой. Проверьте настоящую кнопку пульта в стороннем приложении.'
    } elseif (RestrictedSettingsDenial $after.Ops $after.AttemptLog) {
        $oldList = $initialList
        $newList = $targetList
        $oldEnabled = $initialEnabled
        $oldRestrictedMode = [regex]::Match($after.Ops, '(?m)^\s*ACCESS_RESTRICTED_SETTINGS:\s*(deny|ignore)\b').Groups[1].Value
        $oldAutoMode = ''
        $autoOp = ''
        $report += "`r`nADB-исправление: прежний список служб=$oldList; accessibility_enabled=$oldEnabled; ACCESS_RESTRICTED_SETTINGS=$oldRestrictedMode."
        Set-Content -LiteralPath $reportPath -Value $report -Encoding UTF8
        try {
            Invoke-Shell "appops set $package ACCESS_RESTRICTED_SETTINGS allow" | Out-Null
            Invoke-Shell 'appops write-settings' | Out-Null
            if ((Invoke-Shell "appops get $package ACCESS_RESTRICTED_SETTINGS") -notmatch '(?m)^\s*ACCESS_RESTRICTED_SETTINGS:\s*allow\b') { throw 'ACCESS_RESTRICTED_SETTINGS не сохранился.' }
            Write-ServiceList $withoutHelper
            Start-Sleep -Seconds 1
            $marker = 'TVHOME_' + [guid]::NewGuid().ToString('N')
            Invoke-Shell "log -p i -t TVHomeDiag $marker" | Out-Null
            Write-ServiceList $newList
            Invoke-Shell 'settings --user current put secure accessibility_enabled 1' | Out-Null
            Start-Sleep -Seconds 3
            $fixed = Snapshot 'после ADB-исправления'
            $report += "`r`n" + (Report $fixed)
            $autoOp = AutoStartDenial $fixed.Ops $fixed.AttemptLog
            if (-not $fixed.Bound -and $autoOp) {
                $oldAutoMode = [regex]::Match($fixed.Ops, "(?m)^\s*${autoOp}:\s*(deny|ignore)\b").Groups[1].Value
                $report += "`r`nОбнаружен дополнительный отказ TCL: ${autoOp}=$oldAutoMode."
                Set-Content -LiteralPath $reportPath -Value $report -Encoding UTF8
                Invoke-Shell "appops set $package $autoOp allow" | Out-Null
                Invoke-Shell 'appops write-settings' | Out-Null
                if ((Invoke-Shell "appops get $package $autoOp") -notmatch "(?m)^\s*${autoOp}:\s*allow\b") { throw "${autoOp} не сохранился." }
                Write-ServiceList $withoutHelper
                Start-Sleep -Seconds 1
                $marker = 'TVHOME_' + [guid]::NewGuid().ToString('N')
                Invoke-Shell "log -p i -t TVHomeDiag $marker" | Out-Null
                Write-ServiceList $newList
                Invoke-Shell 'settings --user current put secure accessibility_enabled 1' | Out-Null
                Start-Sleep -Seconds 3
                $fixed = Snapshot 'после исправления AUTO_START через ADB'
                $report += "`r`n" + (Report $fixed)
            }
            Set-Content -LiteralPath $reportPath -Value $report -Encoding UTF8
            if ($fixed.Enabled -ne '1' -or -not $fixed.Listed -or -not $fixed.Bound) { throw 'Служба не подключилась после ADB-исправления.' }
            Write-Host 'Помощник кнопок подключён системой. Проверьте настоящую кнопку пульта в стороннем приложении.'
        } catch {
            $repairError = $_.Exception.Message
            $undoErrors = @()
            try { Write-ServiceList $oldList } catch { $undoErrors += "список служб: $($_.Exception.Message)" }
            try { Invoke-Shell "settings --user current put secure accessibility_enabled $oldEnabled" | Out-Null } catch { $undoErrors += "accessibility_enabled: $($_.Exception.Message)" }
            if ($autoOp -and $oldAutoMode) {
                try { Invoke-Shell "appops set $package $autoOp $oldAutoMode" | Out-Null } catch { $undoErrors += "${autoOp}: $($_.Exception.Message)" }
            }
            try { Invoke-Shell "appops set $package ACCESS_RESTRICTED_SETTINGS $oldRestrictedMode" | Out-Null; Invoke-Shell 'appops write-settings' | Out-Null } catch { $undoErrors += "ACCESS_RESTRICTED_SETTINGS: $($_.Exception.Message)" }
            $undoResult = if ($undoErrors.Count) { $undoErrors -join '; ' } else { 'прежние настройки восстановлены' }
            $report += "`r`nADB-исправление не удалось: $repairError`r`nОткат: $undoResult"
            Set-Content -LiteralPath $reportPath -Value $report -Encoding UTF8
            throw "$repairError Отчёт: $reportPath. Откат: $undoResult"
        }
    } elseif ($op) {
        $oldAutoMode = [regex]::Match($after.Ops, "(?m)^\s*${op}:\s*(deny|ignore)\b").Groups[1].Value
        $report += "`r`nTCL запретил запуск службы: ${op}=$oldAutoMode. Разрешаю эту операцию и повторяю включение через ADB."
        Set-Content -LiteralPath $reportPath -Value $report -Encoding UTF8
        try {
            Invoke-Shell "appops set $package $op allow" | Out-Null
            Invoke-Shell 'appops write-settings' | Out-Null
            if ((Invoke-Shell "appops get $package $op") -notmatch "(?m)^\s*${op}:\s*allow\b") { throw "Не удалось сохранить $op." }
            Write-ServiceList $withoutHelper
            Start-Sleep -Seconds 1
            $marker = 'TVHOME_' + [guid]::NewGuid().ToString('N')
            Invoke-Shell "log -p i -t TVHomeDiag $marker" | Out-Null
            Write-ServiceList $targetList
            Invoke-Shell 'settings --user current put secure accessibility_enabled 1' | Out-Null
            Start-Sleep -Seconds 3
            $fixed = Snapshot 'после исправления AUTO_START через ADB'
            $report += "`r`n" + (Report $fixed)
            Set-Content -LiteralPath $reportPath -Value $report -Encoding UTF8
            if ($fixed.Enabled -ne '1' -or -not $fixed.Listed -or -not $fixed.Bound) { throw 'Служба не подключилась после исправления AUTO_START.' }
            Write-Host 'Помощник кнопок подключён системой. Проверьте настоящую кнопку пульта в стороннем приложении.'
        } catch {
            $repairError = $_.Exception.Message
            $undoErrors = @()
            try { Write-ServiceList $initialList } catch { $undoErrors += "список служб: $($_.Exception.Message)" }
            try { Invoke-Shell "settings --user current put secure accessibility_enabled $initialEnabled" | Out-Null } catch { $undoErrors += "accessibility_enabled: $($_.Exception.Message)" }
            try { Invoke-Shell "appops set $package $op $oldAutoMode" | Out-Null; Invoke-Shell 'appops write-settings' | Out-Null } catch { $undoErrors += "${op}: $($_.Exception.Message)" }
            $undoResult = if ($undoErrors.Count) { $undoErrors -join '; ' } else { 'прежние настройки восстановлены' }
            $report += "`r`nADB-исправление не удалось: $repairError`r`nОткат: $undoResult"
            Set-Content -LiteralPath $reportPath -Value $report -Encoding UTF8
            throw "$repairError Отчёт: $reportPath. Откат: $undoResult"
        }
    } else {
        Write-ServiceList $initialList
        Invoke-Shell "settings --user current put secure accessibility_enabled $initialEnabled" | Out-Null
        $report += "`r`nПомощник не подключился, а причина не определена. Исходные настройки восстановлены."
    }
    $homeBefore = @($homeResolver -split "`n" | Where-Object { $_ -match '\S' } | Select-Object -Last 1)[0].Trim()
    if ($homeBefore -like 'com.google.android.apps.tv.launcherx/*') {
        try {
            $homeSet = Invoke-Shell 'cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity'
            $homeAfter = Invoke-Shell 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME'
            $report += "`r`nПопытка назначить лаунчер Home: $homeSet`r`nГлавный экран после назначения: $homeAfter"
            if ($homeAfter -match 'com\.gathin\.tvhome/\.MainActivity') {
                Write-Host 'Наш лаунчер назначен главным экраном. Проверьте после перезагрузки.'
            } else {
                Write-Host 'TCL оставил Google TV главным экраном. Штатный лаунчер не отключался.'
            }
        } catch {
            $report += "`r`nНазначение Home не удалось: $($_.Exception.Message)"
            Write-Host 'Назначение Home не удалось. Штатный лаунчер не отключался.'
        }
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
