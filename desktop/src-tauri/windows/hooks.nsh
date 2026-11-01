; Tauri NSIS hooks (bundle.windows.nsis.installerHooks). Opens the Desktop client's discovery ports
; in Windows Defender Firewall so it can receive UDP beacons and mDNS replies on any network profile,
; Public included (rules/networking-and-lan.md section 4.1). The installer runs elevated
; (installMode perMachine), which netsh needs.

!macro LARA_DELETE_FIREWALL_RULES
  nsExec::Exec '"$SYSDIR\netsh.exe" advfirewall firewall delete rule name="LARA Desktop Discovery (UDP 8888)"'
  Pop $0
  nsExec::Exec '"$SYSDIR\netsh.exe" advfirewall firewall delete rule name="LARA Desktop Discovery (mDNS)"'
  Pop $0
!macroend

!macro NSIS_HOOK_POSTINSTALL
  ; Delete first so a reinstall or an update never stacks duplicate rules.
  !insertmacro LARA_DELETE_FIREWALL_RULES
  nsExec::Exec '"$SYSDIR\netsh.exe" advfirewall firewall add rule name="LARA Desktop Discovery (UDP 8888)" dir=in action=allow protocol=UDP localport=8888'
  Pop $0
  nsExec::Exec '"$SYSDIR\netsh.exe" advfirewall firewall add rule name="LARA Desktop Discovery (mDNS)" dir=in action=allow protocol=UDP localport=5353'
  Pop $0
!macroend

!macro NSIS_HOOK_POSTUNINSTALL
  ; An update runs the old uninstaller first; the new install's POSTINSTALL re-adds the rules, so
  ; only a real uninstall needs to close the ports.
  ${If} $UpdateMode <> 1
    !insertmacro LARA_DELETE_FIREWALL_RULES
  ${EndIf}
!macroend
