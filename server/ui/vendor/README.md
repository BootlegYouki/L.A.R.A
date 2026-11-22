# Bundled spreadsheet parser

`xlsx-0.20.3.tgz` is the official SheetJS Community Edition 0.20.3 distribution, licensed under Apache-2.0. Its LICENSE and NOTICE files are included in the archive and installed package.

Source: https://cdn.sheetjs.com/xlsx-0.20.3/xlsx-0.20.3.tgz

SHA-256: `8dc73fc3b00203e72d176e85b50938627c7b086e607c682e8d3c22c02bb99fe8`

The package manifest and lockfile reference this local archive. This follows the [official vendoring guidance](https://docs.sheetjs.com/docs/getting-started/installation/nodejs/#vendoring). The parser is bundled into the Hub UI and its worker; the installed application does not fetch scripts, fonts, or packages from this source.
