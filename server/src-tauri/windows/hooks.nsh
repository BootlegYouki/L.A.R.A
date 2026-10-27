; Tauri NSIS hooks (bundle.windows.nsis.installerHooks). Opens the Hub's LAN ports in Windows
; Defender Firewall so learners' phones can connect on any network profile, Public included
; (rules/networking-and-lan.md section 4.1). The installer runs elevated (installMode perMachine),
; which netsh needs.
;
; The rule names must match HUB_PORTS in backend/src/network/firewall_check.rs, which looks them up
; for the dashboard; backend/tests/firewall_check.rs fails if the two drift apart.

!macro LARA_DELETE_FIREWALL_RULES
  nsExec::Exec '"$SYSDIR\netsh.exe" advfirewall firewall delete rule name="LARA Local Hub (HTTP)"'
  Pop $0
  nsExec::Exec '"$SYSDIR\netsh.exe" advfirewall firewall delete rule name="LARA Local Hub (WebSocket)"'
  Pop $0
  nsExec::Exec '"$SYSDIR\netsh.exe" advfirewall firewall delete rule name="LARA Discovery Beacon (UDP)"'
  Pop $0
!macroend

!macro NSIS_HOOK_POSTINSTALL
  ; Delete first so a reinstall or an update never stacks duplicate rules.
  !insertmacro LARA_DELETE_FIREWALL_RULES
  nsExec::Exec '"$SYSDIR\netsh.exe" advfirewall firewall add rule name="LARA Local Hub (HTTP)" dir=in action=allow protocol=TCP localport=8080'
  Pop $0
  nsExec::Exec '"$SYSDIR\netsh.exe" advfirewall firewall add rule name="LARA Local Hub (WebSocket)" dir=in action=allow protocol=TCP localport=8081'
  Pop $0
  nsExec::Exec '"$SYSDIR\netsh.exe" advfirewall firewall add rule name="LARA Discovery Beacon (UDP)" dir=in action=allow protocol=UDP localport=8888'
  Pop $0
!macroend

!macro NSIS_HOOK_POSTUNINSTALL
  ; An update runs the old uninstaller first; the new install's POSTINSTALL re-adds the rules, so
  ; only a real uninstall needs to close the ports.
  ${If} $UpdateMode <> 1
    !insertmacro LARA_DELETE_FIREWALL_RULES
  ${EndIf}
!macroend
