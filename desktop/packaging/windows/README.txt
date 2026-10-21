ETPH for Windows (Errant Solutions)
===================================

ETPH shares your Android phone's internet with this computer over USB or
Wi-Fi, through the ETPH app on the phone.

USB (free): turn on Developer options > USB debugging on the phone, start
ETPH on the phone in USB mode, plug it in, then click the ETPH icon in the
notification area and choose "Connect over USB". Tap "Allow" on the phone
the first time. No Android SDK or adb is needed: ETPH talks to the phone over
WinUSB itself. Phones whose adb interface has no WinUSB driver need their
maker's USB driver (or Google's USB driver) installed once.

Wi-Fi (ETPH Pro on the phone): save the phone once from an Administrator
command prompt, with the token the phone shows:

  "C:\Program Files\ETPH\etphonehome.exe" --config "%ProgramData%\ETPH\config.toml" save-profile phone -t wifi --host <phone IP> --token <token>

then pick it under "Connect to a saved phone" in the ETPH menu.

Command line: Start menu > ETPH > "ETPH command prompt" opens a prompt in
the install folder (right-click > Run as administrator for usb/wifi);
`etphonehome ctl status` works without Administrator while the ETPH service
runs. Elsewhere use the full path: "C:\Program Files\ETPH\etphonehome.exe".

Files: C:\Program Files\ETPH. Service: "ETPH tethering service" (log in
%ProgramData%\ETPH\service.log). Uninstall from Settings > Apps.

The Wintun driver (wintun.dll) is by WireGuard LLC, see wintun-LICENSE.txt.
Help: https://errant.solutions/etphonehome/
