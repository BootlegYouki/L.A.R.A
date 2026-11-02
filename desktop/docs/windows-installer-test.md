# Windows Installer Test Report

**Date:** October 2026 (Single-PC test)  
**Updated:** November 2, 2026 (Two-PC test completed)  
**Tester:** Jan Kiervy  
**Issue:** [DESKTOP 1.2] #5 - Hub Discovery

**Note:** This document contains original single-PC test results. See `two-pc-test-results.md` for complete two-PC validation that proves firewall rules work across network.

---

## Test Environment

- **OS:** Windows 11
- **Network:** bluetootootooh 7 (mobile phone Wi-Fi hotspot)
- **Network Profile:** Public ✅
- **Installer:** L.A.R.A._0.1.0_x64-setup.exe
- **Test Setup:** Mock Hub and Desktop app on same laptop

---

## Test Results Summary

### ✅ Task 1: Windows Firewall NSIS Hook
**Status:** COMPLETED

Created installer hooks that automatically add firewall rules:
- `desktop/src-tauri/windows/hooks.nsh` created
- `tauri.conf.json` updated with `installerHooks` reference
- Rules added: UDP 8888 (beacon), UDP 5353 (mDNS)

### ✅ Task 2: mDNS Test on Windows
**Status:** COMPLETED - FAILED (Expected)

**Test Command:**
```powershell
$env:LARA_TEST_REAL_HUB_IP = '192.168.100.13'
cargo test --locked --manifest-path src-tauri/Cargo.toml --lib real_hub_mdns_without_udp -- --ignored --nocapture
```

**Result:** ❌ FAILED after 25.07 seconds
- mDNS-only discovery: Not working on Windows
- UDP beacon discovery: Works correctly ✅
- **This confirms the documented Windows mDNS compatibility issue**

### ✅ Task 3: Installer Evidence on Windows
**Status:** COMPLETED

#### Installation Test
✅ Installer ran successfully  
✅ Only UAC prompt appeared (normal elevated install)  
✅ **No Windows Firewall popup** (proves automatic rule addition)  
✅ Installation completed without errors

#### Discovery Test
nonce (Mock Hub)"  
✅ Hub IP: 10.172.136.226:8080  
✅ No manual configuration required  
✅ Works on mobile phone Wi-Fi hotspot network  
✅ Works on Public network profile

#### Network Interruption Test
**Note:** Mock Hub and Desktop app running on same laptop

✅ **Mock Hub stopped:** Hub disappeared after ~11 seconds  
✅ **Mock Hub restarted:** Hub reappeared automatically  
✅ **App stability:** Desktop remained stable during interruption

**Wi-Fi Toggle Test Limitation:**  
Cannot perform true Wi-Fi toggle test when both Mock Hub and Desktop run on the same device. They communicate via localhost (127.0.0.1), which remains active when Wi-Fi is turned off. Stop/restart test validates the same expiry and rediscovery mechanisms.

#### Firewall Rules Verification
✅ Rules automatically added by installer:
- Rule: LARA Desktop Discovery (UDP 8888)
- Status: Enabled
- Profiles: Domain, Private, Public ✅
- Protocol: UDP
- Port: 8888
- Action: Allow

---

## Screenshots

### Screenshot 1: Network Profile - Public
![Network Profile](screenshots/network-profile-public.png)

Shows:
- Network: bluetootootooh 7 (mobile phone Wi-Fi hotspot)
- NetworkCategory: Public ✅
- Proves test performed on Public network as required

### Screenshot 2: Firewall Rule Verification
![Firewall Rules](screenshots/firewall-rule-udp-8888.png)

Shows:
- Rule Name: LARA Desktop Discovery (UDP 8888)
- Enabled: Yes
- Profiles: Domain, Private, Public
- Protocol: UDP
- LocalPort: 8888
- Action: Allow

### Screenshot 3: Hub Discovered
![Hub Discovered](screenshots/hub-discovered-bluetooth-network.png)

Shows:
- Desktop app with Hub in "Nearby classroom Hubs"
- Hub name: "Grade 4 - Science (Mock Hub)"
- Hub IP: 10.172.136.226:8080
- Network: bluetootootooh 7 (mobile phone Wi-Fi hotspot name)
- Status: "Not connected to Hub" (correct - authentication not implemented in #5)
- Discovery does not require user input

---

## Additional Tests Performed

### Networks Tested
✅ Home Wi-Fi (Hirang 5GHz 5) - Public profile  
✅ Mobile phone Wi-Fi hotspot (bluetootootooh 7) - Public profile

Discovery worked correctly on both networks.

### Language Testing
✅ English UI functional  
✅ Filipino UI functional  
✅ Language toggle works without restart

---

## Known Limitations

### mDNS Discovery on Windows
- **Issue:** mDNS-only discovery (without UDP beacon) fails on Windows
- **Status:** Documented in `desktop/docs/discovery.md`
- **Impact:** UDP beacon discovery works correctly as primary mechanism
- **Action:** Reported to Lead/Server team for investigation

### Multi-Device Wi-Fi Toggle Test
- **Issue:** Cannot test true network interruption with single-device setup
- **Workaround:** Mock Hub stop/restart validates same expiry/rediscovery logic
- **Impact:** Core discovery mechanisms proven functional
- **Future:** Requires second device for complete Wi-Fi toggle validation

---

## Compliance Checklist

- ✅ No external cloud/CDN dependency added
- ✅ Desktop documentation updated
- ✅ Other teams' source untouched
- ✅ No tutor/quiz behavior changed
- ✅ Strict TypeScript/build passed
- ✅ Lint passed (zero warnings)
- ✅ All tests passed (20 desktop tests)

---

## Conclusion

⚠️ **Testing Status: Incomplete**

**Completed on single-PC setup:**
- ✅ Installer runs without errors
- ✅ Firewall rules added automatically
- ✅ Discovery mechanisms functional
- ✅ Expiry and rediscovery work correctly

**Critical limitation identified:**
Single-PC testing cannot validate firewall rules because traffic stays within localhost and never traverses Windows Firewall.

**Required for completion:**
1. Two-PC test: Hub on PC A, installed Desktop on PC B (Public network, non-admin user)
2. Verify: Hub appears without firewall popup, Wi-Fi toggle works
3. Document: OS versions on both PCs
4. mDNS test: Clarify which PC runs which OS, open Server issue if Hub-side Windows causes failure

**Known issues:**
- ❌ mDNS-only discovery fails on Windows (requires two-PC test to determine if Hub-side or client-side)
- ⚠️ Firewall rule effectiveness not yet proven (requires network traffic between devices)

**Next steps:** Complete two-PC testing session to validate firewall rules work across network.

---

## Test Commands Reference

### Build Installer
```cmd
cd desktop
npm run tauri build
```

### Verify Firewall Rules
```powershell
netsh advfirewall firewall show rule name="LARA Desktop Discovery (UDP 8888)"
```

### Check Network Profile
```powershell
Get-NetConnectionProfile
```

### Set Network to Public
```powershell
Set-NetConnectionProfile -Name "YourNetworkName" -NetworkCategory Public
```

### Run mDNS Test
```powershell
$env:LARA_TEST_REAL_HUB_IP = '192.168.100.13'
cargo test --locked --manifest-path src-tauri/Cargo.toml --lib real_hub_mdns_without_udp -- --ignored --nocapture
```
