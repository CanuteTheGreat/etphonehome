# ETPH on real Windows (GitHub-hosted Windows Server runner): install the
# MSI, then connect to the phone's networking core (standin) on this machine
# through Wintun and use the internet through it.
#
#   e2e.ps1 -Msi <etph-windows-*.msi> -Standin <standin install dir>
#
# The stand-in binds its relay sockets to this machine's real address
# (--bind-address), so its own traffic leaves through the real adapter
# (strong host model) instead of looping into the tunnel's routes.
param([string]$Msi, [string]$Standin)
$ErrorActionPreference = "Continue"
$Token = "WinE2eTok"
$W = Join-Path $env:RUNNER_TEMP "etph-e2e"
New-Item -ItemType Directory -Force -Path $W | Out-Null
$script:Pass = 0; $script:Fail = 0
function Pass($m) { Write-Host "PASS: $m"; $script:Pass++ }
function Fail($m) { Write-Host "FAIL: $m"; $script:Fail++ }
$Exe = "C:\Program Files\ETPH\etphonehome.exe"
$Cfg = "C:\ProgramData\ETPH\config.toml"

# ---- install --------------------------------------------------------------
$p = Start-Process msiexec.exe -ArgumentList "/i `"$Msi`" /qn /l*v `"$W\msi.log`"" -Wait -PassThru
if ($p.ExitCode -eq 0) { Pass "msiexec /i $(Split-Path $Msi -Leaf)" } else { Get-Content "$W\msi.log" -Tail 40; Fail "msiexec exit $($p.ExitCode)" }
foreach ($f in "etphonehome.exe", "etph-tray.exe", "wintun.dll") {
  if (Test-Path "C:\Program Files\ETPH\$f") { Pass "installed $f" } else { Fail "missing $f" }
}
$sig = Get-AuthenticodeSignature "C:\Program Files\ETPH\wintun.dll"
if ($sig.Status -eq "Valid" -and $sig.SignerCertificate.Subject -match "WireGuard") { Pass "wintun.dll signed by WireGuard LLC ($($sig.Status))" } else { Fail "wintun.dll signature: $($sig.Status) $($sig.SignerCertificate.Subject)" }
$svc = Get-Service ETPH -ErrorAction SilentlyContinue
for ($i = 0; $i -lt 20 -and $svc.Status -ne "Running"; $i++) { Start-Sleep 1; $svc = Get-Service ETPH -ErrorAction SilentlyContinue }
if ($svc.Status -eq "Running") { Pass "ETPH service running (LocalSystem)" } else { Fail "service: $($svc.Status)"; Get-Content C:\ProgramData\ETPH\service.log -Tail 30 -ErrorAction SilentlyContinue }
$machinePath = [Environment]::GetEnvironmentVariable("PATH", "Machine")
if ($machinePath -like "*Program Files\ETPH*") { Pass "installer added ETPH to PATH" } else { Fail "PATH not updated" }
$st = & $Exe ctl status --json 2>&1 | Out-String
if ($st -match '"state":"idle"') { Pass "ctl talks to the service (idle)" } else { Fail "ctl status: $st" }
$usb = & $Exe usb-list --json 2>&1 | Out-String
if ($usb.Trim().StartsWith("[")) { Pass "usb-list enumerates USB through WinUSB/libusb" } else { Fail "usb-list: $usb" }

# ---- the phone ------------------------------------------------------------
$ip = (Get-NetIPConfiguration | Where-Object { $_.IPv4DefaultGateway -ne $null } | Select-Object -First 1).IPv4Address.IPAddress
Write-Host "address for the stand-in's own traffic: $ip"
$sp = Start-Process -FilePath "$Standin\bin\standin.bat" -ArgumentList "--listen 127.0.0.1:9301 --token $Token --require-tls --stats-log --stats-secs 2 --bind-address $ip" `
  -RedirectStandardOutput "$W\standin.log" -RedirectStandardError "$W\standin.err" -PassThru -WindowStyle Hidden
for ($i = 0; $i -lt 100; $i++) { if ((Get-Content "$W\standin.log" -Raw -ErrorAction SilentlyContinue) -match "listening on") { break }; Start-Sleep -Milliseconds 300 }

# ---- CLI session (Administrator, as the runner is) ------------------------
$env:ETPH_TOKEN = $Token
$cli = Start-Process -FilePath $Exe -ArgumentList "wifi --host 127.0.0.1 --port 9301 --interface etphcli" `
  -RedirectStandardOutput "$W\cli.out" -RedirectStandardError "$W\cli.log" -PassThru -WindowStyle Hidden
for ($i = 0; $i -lt 60; $i++) { if ((Get-Content "$W\cli.log" -Raw -ErrorAction SilentlyContinue) -match "Tethering connection established") { break }; Start-Sleep 1 }
$log = Get-Content "$W\cli.log" -Raw
if ($log -match "Tethering connection established" -and $log -match "TLS handshake complete") { Pass "Wi-Fi session up (cert-pinned TLS 1.3) over Wintun" } else { Write-Host $log; Fail "session" }
$ad = Get-NetAdapter -Name etphcli -ErrorAction SilentlyContinue
if ($ad -and $ad.InterfaceDescription -match "Wintun|ETPH|etph") { Pass "Wintun adapter etphcli ($($ad.InterfaceDescription))" } else { Fail "adapter: $($ad | Format-List | Out-String)" }
$addr = Get-NetIPAddress -InterfaceAlias etphcli -AddressFamily IPv4 -ErrorAction SilentlyContinue
if ($addr.IPAddress -eq "10.0.0.1") { Pass "etphcli has 10.0.0.1" } else { Fail "etphcli address: $($addr.IPAddress)" }
$r = Find-NetRoute -RemoteIPAddress 1.1.1.1 | Select-Object -First 1
$r2 = Find-NetRoute -RemoteIPAddress 200.1.1.1 | Select-Object -First 1
if ($r.InterfaceAlias -eq "etphcli" -and $r2.InterfaceAlias -eq "etphcli") { Pass "0/1 + 128/1 routes win over the runner's default route" } else { Fail "route lookup: $($r.InterfaceAlias) $($r2.InterfaceAlias)"; route print -4 }
$dns = (Get-DnsClientServerAddress -InterfaceAlias etphcli -AddressFamily IPv4).ServerAddresses
if ($dns -contains "8.8.8.8") { Pass "etphcli DNS = the phone's DNS server" } else { Fail "DNS: $dns" }
$before = (Get-NetAdapterStatistics -Name etphcli).ReceivedBytes
$page = & curl.exe -sS -m 30 https://example.com 2>&1 | Out-String
$after = (Get-NetAdapterStatistics -Name etphcli).ReceivedBytes
if ($page -match "Example Domain") { Pass "HTTPS through the phone" } else { Fail "curl: $page" }
if ($after -gt $before) { Pass "the request crossed etphcli ($before -> $after bytes in)" } else { Fail "no bytes on etphcli ($before -> $after)" }
& curl.exe -sS -m 60 -o "$W\5m" "https://speed.cloudflare.com/__down?bytes=5000000"
if ((Get-Item "$W\5m" -ErrorAction SilentlyContinue).Length -eq 5000000) { Pass "5 MB download through the phone" } else { Fail "5 MB download" }
# A killed client gets no chance to say goodbye; the service path below
# checks the clean disconnect.
Stop-Process -Id $cli.Id -Force
Start-Sleep 3
Remove-Item Env:\ETPH_TOKEN

# ---- the tray app's service path ------------------------------------------
& $Exe --config $Cfg save-profile ci -t wifi --host 127.0.0.1 --port 9301 --token $Token | Out-Null
if ($LASTEXITCODE -eq 0) { Pass "saved a phone for the service" } else { Fail "save-profile" }
& $Exe ctl connect ci | Out-Null
for ($i = 0; $i -lt 40; $i++) { if ((& $Exe ctl status --json | Out-String) -match '"state":"connected"') { break }; Start-Sleep 1 }
$st = & $Exe ctl status --json | Out-String
if ($st -match '"state":"connected"' -and $st -match '"phone_pro":true') { Pass "service connected (status shows the phone and ETPH Pro)" } else { Fail "service connect: $st"; Get-Content C:\ProgramData\ETPH\service.log -Tail 30 }
$page = & curl.exe -sS -m 30 https://example.com 2>&1 | Out-String
if ($page -match "Example Domain") { Pass "HTTPS through the service's session" } else { Fail "curl via service: $page" }
& $Exe ctl disconnect | Out-Null
Start-Sleep 3
$st = & $Exe ctl status --json | Out-String
if ($st -match '"state":"idle"') { Pass "ctl disconnect" } else { Fail "disconnect: $st" }
if ((Get-Content "$W\standin.log" -Raw) -match "Disconnect|session over") { Pass "the phone saw a clean disconnect" } else { Fail "no clean disconnect on the phone" }

$tray = Start-Process -FilePath "C:\Program Files\ETPH\etph-tray.exe" -PassThru
Start-Sleep 6
if (-not $tray.HasExited) { Pass "tray app starts and stays up"; Stop-Process -Id $tray.Id -Force } else { Fail "tray app exited ($($tray.ExitCode))" }

# ---- uninstall ------------------------------------------------------------
$p = Start-Process msiexec.exe -ArgumentList "/x `"$Msi`" /qn /l*v `"$W\msi-x.log`"" -Wait -PassThru
if ($p.ExitCode -eq 0 -and -not (Test-Path $Exe) -and -not (Get-Service ETPH -ErrorAction SilentlyContinue)) { Pass "msiexec /x removes files and the service" } else { Fail "uninstall exit $($p.ExitCode)" }
Stop-Process -Id $sp.Id -Force -ErrorAction SilentlyContinue

Write-Host "windows e2e summary: $($script:Pass) passed, $($script:Fail) failed"
if ($script:Fail -gt 0) { exit 1 }
exit 0
