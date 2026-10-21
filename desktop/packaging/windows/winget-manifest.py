#!/usr/bin/env python3
"""winget manifests (schema 1.6) for a released MSI, so ETPH can be listed in
the Windows Package Manager community repository (microsoft/winget-pkgs) or
installed from a local manifest today:

    winget install --manifest <unzipped dir>

    winget-manifest.py <tag> <msi> <out dir>   ->  <out>/etph-winget-<tag>.zip
"""
import hashlib
import os
import sys
import zipfile

PKG_ID = "ErrantSolutions.ETPH"
BASE = "https://git.canutethegreat.com/CanuteTheGreat/etphonehome/releases/download"


def main():
    tag, msi, out = sys.argv[1], sys.argv[2], sys.argv[3]
    ver = tag.lstrip("v")
    sha = hashlib.sha256(open(msi, "rb").read()).hexdigest().upper()
    name = os.path.basename(msi)
    head = f"PackageIdentifier: {PKG_ID}\nPackageVersion: {ver}\n"
    files = {
        f"{PKG_ID}.yaml": head + "DefaultLocale: en-US\nManifestType: version\nManifestVersion: 1.6.0\n",
        f"{PKG_ID}.installer.yaml": head + f"""MinimumOSVersion: 10.0.17763.0
InstallerType: msi
Scope: machine
InstallModes:
- interactive
- silent
UpgradeBehavior: install
Commands:
- etphonehome
Installers:
- Architecture: x64
  InstallerUrl: {BASE}/{tag}/{name}
  InstallerSha256: {sha}
ManifestType: installer
ManifestVersion: 1.6.0
""",
        f"{PKG_ID}.locale.en-US.yaml": head + """PackageLocale: en-US
Publisher: Errant Solutions
PublisherUrl: https://errant.solutions/
PublisherSupportUrl: https://errant.solutions/etphonehome/
PackageName: ETPH
PackageUrl: https://errant.solutions/etphonehome/
License: MIT
LicenseUrl: https://git.canutethegreat.com/CanuteTheGreat/etphonehome/src/branch/main/LICENSE
ShortDescription: Internet from your Android phone over USB or Wi-Fi (with the ETPH app).
Description: ETPH shares an Android phone's internet with this PC through the ETPH app, over USB (no Android SDK needed) or Wi-Fi with cert-pinned TLS 1.3. Includes a tray app, a service and the etphonehome command line.
Tags:
- tethering
- usb
- android
- hotspot
ManifestType: defaultLocale
ManifestVersion: 1.6.0
""",
    }
    os.makedirs(out, exist_ok=True)
    path = os.path.join(out, f"etph-winget-{tag}.zip")
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        for n, body in files.items():
            z.writestr(f"manifests/e/ErrantSolutions/ETPH/{ver}/{n}", body)
    print(path)


if __name__ == "__main__":
    main()
