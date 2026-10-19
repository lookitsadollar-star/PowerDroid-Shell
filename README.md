# PowerDroid Shell

Terminál ve stylu PowerShellu, který běží přímo v telefonu s Androidem (8.0+) a ovládá ho.

## Instalace
1. Zkopírujte `PowerDroid-Shell.apk` do telefonu a otevřete ho.
2. Povolte „Instalace z neznámých zdrojů“ pro správce souborů nebo prohlížeč.
3. Při prvním spuštění: `Request-Permission Storage` (přístup k souborům).

## Sestavení ze zdrojů
```
./gradlew assembleDebug      # výstup: app/build/outputs/apk/debug/app-debug.apk
```

## Ukázky
```powershell
Get-Help                                   # seznam všech příkazů
Get-Battery; Get-DeviceInfo; Get-Storage
torch on                                   # svítilna
Set-Volume 40 -Stream Media
Set-Ringer Vibrate
Set-Brightness 70                          # systémový jas po: Request-Permission Settings
say "Ahoj, tady PowerShell" -Language cs
Start-App kamera
Get-App | Where-Object { $_ -match 'google' } | Sort-Object
Get-ChildItem /sdcard/DCIM -Recurse -Filter *.jpg | Measure-Object
Invoke-WebRequest example.com -OutFile stranka.html
media next                                 # další skladba
notify "Hotovo" -Title "PowerDroid"
$x = 5; $x * 2 + 1
ping google.com
ls -la /system/bin                         # neznámé příkazy jdou do Linux shellu
Edit-Profile "Set-Alias t Set-Flashlight"  # spouští se při startu
```
Spodní lišta: TAB (doplňování), ↑/↓ (historie), Ctrl+C (přerušení), A+/A- (velikost písma).
