# Run tcl-button-helper.bat on a Windows PC on the same network as the TV.
# Read-only diagnosis comes first. Only a confirmed TCL auto-start denial changes an app-op.
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
    if ((AutoStartDenial '  AUTO_START: ignore' '[TclAppBoot] forbid bind service com.gathin.tvhome/.InputKeyService') -ne 'AUTO_START') { throw 'AUTO_START check failed' }
    if ((AutoStartDenial '  APP_AUTO_START: deny' '[TclAppBoot] bind_service isAllow = false com.gathin.tvhome/.InputKeyService') -ne 'APP_AUTO_START') { throw 'APP_AUTO_START check failed' }
    if (AutoStartDenial '  AUTO_START: ignore' 'No TCL binding denial') { throw 'False positive' }
    if (AutoStartDenial '  AUTO_START: ignore' '[TclAppBoot] forbid bind service com.gathin.tvhome/.HomeKeyService') { throw 'Wrong service' }
    if (AutoStartDenial "AUTO_START: ignore`nAPP_AUTO_START: ignore" '[TclAppBoot] forbid bind service com.gathin.tvhome/.InputKeyService') { throw 'Ambiguous app-op' }
    Write-Host 'Self-test passed.'
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
    throw 'adb.exe not found. Install Google SDK Platform-Tools: https://developer.android.com/tools/releases/platform-tools'
}

function Invoke-Adb([string[]]$arguments) {
    $ErrorActionPreference = 'Continue'
    $output = & $script:adb @arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $($output -join ' ')" }
    return ($output -join "`n").Trim()
}

function Invoke-Shell([string]$command) {
    return Invoke-Adb -arguments @('-s', $script:serial, 'shell', $command)
}

function Read-Port([string]$prompt) {
    $text = Read-Host $prompt
    [int]$number = 0
    if (-not [int]::TryParse($text, [ref]$number) -or $number -lt 1 -or $number -gt 65535) { throw 'Port must be from 1 to 65535.' }
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
    return @(
        "[$($snapshot.Label)]",
        "accessibility_enabled=$($snapshot.Enabled)",
        "InputKeyService listed=$($snapshot.Listed)",
        'appops:', $snapshot.Ops,
        'accessibility:', $snapshot.Dump,
        'recent log:', $snapshot.Log, ''
    ) -join "`r`n"
}

try {
    $adb = Find-Adb
    Write-Host "Using $adb"
    $ip = Read-Host 'TV IPv4 address (shown under Network settings)'
    $address = $null
    if (-not [Net.IPAddress]::TryParse($ip, [ref]$address) -or $address.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork) { throw 'Enter a valid IPv4 address.' }
    $port = Read-Port 'ADB connection port (not the pairing port)'
    $serial = "${ip}:$port"
    try {
        Invoke-Adb -arguments @('connect', $serial) | Out-Host
        if ((Invoke-Adb -arguments @('-s', $serial, 'get-state')) -ne 'device') { throw 'Not connected' }
    } catch {
        Read-Host 'If the TV asks to authorize this PC, accept it. Then press Enter' | Out-Null
        try {
            Invoke-Adb -arguments @('connect', $serial) | Out-Host
            if ((Invoke-Adb -arguments @('-s', $serial, 'get-state')) -ne 'device') { throw 'Not connected' }
        } catch {
            Write-Host 'For Wireless debugging, use Pair device with pairing code on the TV.'
            $pairPort = Read-Port 'ADB pairing port shown on TV'
            $pairCode = Read-Host 'Six-digit pairing code'
            if ($pairCode -notmatch '^\d{6}$') { throw 'Pairing code must have six digits.' }
            Invoke-Adb -arguments @('pair', "${ip}:$pairPort", $pairCode) | Out-Host
            Invoke-Adb -arguments @('connect', $serial) | Out-Host
            if ((Invoke-Adb -arguments @('-s', $serial, 'get-state')) -ne 'device') { throw 'ADB is not authorized. Accept the prompt on the TV.' }
        }
    }
    $installed = Invoke-Shell "pm path $package"
    if ($installed -notmatch '^package:') { throw "$package is not installed on this TV." }
    $packageDump = Invoke-Shell "dumpsys package $package"
    if ($packageDump -notmatch 'InputKeyService') { throw 'The installed APK does not register InputKeyService. Update the launcher first.' }
    $version = @($packageDump -split "`n" | Where-Object { $_ -match 'versionCode=|InputKeyService' } | Select-Object -First 8) -join "`n"
    $device = "$(Invoke-Shell 'getprop ro.product.model') / Android $(Invoke-Shell 'getprop ro.build.version.release') / $(Invoke-Shell 'getprop ro.build.display.id')"
    $directory = Join-Path $env:TEMP ('tvhome-button-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    $before = Snapshot 'before toggle'
    $reportPath = Join-Path $directory 'report.txt'
    Set-Content -LiteralPath $reportPath -Value "Device: $device`r`nPackage: $version`r`n$(Report $before)" -Encoding UTF8
    $marker = 'TVHOME_' + [guid]::NewGuid().ToString('N')
    Invoke-Shell "log -p i -t TVHomeDiag $marker" | Out-Null
    Invoke-Shell 'am start -a android.settings.ACCESSIBILITY_SETTINGS' | Out-Null
    Read-Host 'On the TV, try turning on Remote Button Helper. Then press Enter here' | Out-Null
    $after = Snapshot 'after toggle'
    $report = "Device: $device`r`nPackage: $version`r`n$(Report $before)`r`n$(Report $after)"
    Set-Content -LiteralPath $reportPath -Value $report -Encoding UTF8
    $op = AutoStartDenial $after.Ops $after.AttemptLog
    if ($op) {
        Write-Host "TCL denied the helper bind and $op is blocked. Allowing only this app-op..."
        $report += "`r`nAttempting ${op}=allow after a matching TclAppBoot bind denial."
        Set-Content -LiteralPath $reportPath -Value $report -Encoding UTF8
        Invoke-Shell "appops set $package $op allow" | Out-Null
        Invoke-Shell 'appops write-settings' | Out-Null
        $verified = Invoke-Shell "appops get $package $op"
        if ($verified -notmatch "(?m)^\s*${op}:\s*allow\b") { throw "$op was not saved. Report is in $directory" }
        $report += "`r`nApplied ${op}=allow. Previous value is in [after toggle] appops."
        Set-Content -LiteralPath $reportPath -Value $report -Encoding UTF8
        Invoke-Shell 'am start -a android.settings.ACCESSIBILITY_SETTINGS' | Out-Null
        Read-Host 'Try enabling the helper again on the TV, then press Enter here' | Out-Null
        $report += "`r`n" + (Report (Snapshot 'after AUTO_START fix'))
    } else {
        $report += "`r`nNo unambiguous TCL AUTO_START fix was found. No system setting was changed."
    }
    Set-Content -LiteralPath $reportPath -Value $report -Encoding UTF8
    Write-Host "Report saved: $reportPath"
    Write-Host 'Send only this report after reviewing it for private information.'
    Write-Host 'Turn off Wireless/Network debugging on the TV after diagnosis.'
    Invoke-Adb -arguments @('disconnect', $serial) | Out-Null
} catch {
    Write-Host "Stopped: $($_.Exception.Message)" -ForegroundColor Red
    if ($reportPath -and (Test-Path -LiteralPath $reportPath)) { Write-Host "Partial report: $reportPath" }
    if ($adb -and $serial) { try { Invoke-Adb -arguments @('disconnect', $serial) | Out-Null } catch {} }
    exit 1
}
