#!/bin/sh
# .deb post-install step (bundle.linux.deb.postInstallScript). Opens the Hub's LAN ports when a
# firewall is running and does nothing when none is (rules/networking-and-lan.md section 4.1).
# Needs no internet. The ports must match HUB_PORTS in backend/src/network/firewall_check.rs;
# backend/tests/firewall_check.rs fails if they drift apart.
#
# The rules are kept on removal: ufw and firewalld port rules have no name, so deleting them could
# close a port the admin opened for something else.

[ "$1" = "configure" ] || exit 0

# ufw's status text is translated; the C locale keeps "Status: active" matchable.
if command -v ufw >/dev/null 2>&1 && LC_ALL=C ufw status 2>/dev/null | grep -q "^Status: active"; then
  ufw allow 8080/tcp >/dev/null
  ufw allow 8081/tcp >/dev/null
  ufw allow 8888/udp >/dev/null
elif command -v firewall-cmd >/dev/null 2>&1 && firewall-cmd --state >/dev/null 2>&1; then
  firewall-cmd --quiet --permanent --add-port=8080/tcp
  firewall-cmd --quiet --permanent --add-port=8081/tcp
  firewall-cmd --quiet --permanent --add-port=8888/udp
  firewall-cmd --quiet --reload
fi

# A firewall hiccup must never fail the whole install; the dashboard's port check shows it instead.
exit 0
