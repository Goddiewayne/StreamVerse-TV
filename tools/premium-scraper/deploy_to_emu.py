#!/usr/bin/env python3
"""
Deploy premium sources to all connected emulators.

Pushes premium_sources.json into each emulator's SharedPreferences
for the core module, so PremiumClient picks them up on next launch.

Usage:
    python deploy_to_emu.py                              # deploy to all devices
    python deploy_to_emu.py --device emulator-5554        # specific device
    python deploy_to_emu.py --config path/to/sources.json # custom config path
"""

import json
import logging
import subprocess
import sys
from pathlib import Path

logging.basicConfig(level=logging.INFO, format="[%(levelname)s] %(message)s")
log = logging.getLogger("deploy")

PACKAGE = "com.streamverse"
PREFS_FILE = "premium_sources"
ADB = Path(
    subprocess.run(
        ["where", "adb"],
        capture_output=True, text=True, shell=True,
    ).stdout.strip().splitlines()[0]
    if sys.platform == "win32" else "adb"
)

# Fallback paths to try
ADB_CANDIDATES = [
    Path(r"C:\Users\godwi\AppData\Local\Android\Sdk\platform-tools\adb.exe"),
    Path.home() / "Android" / "Sdk" / "platform-tools" / "adb",
]


def find_adb() -> str:
    for p in ADB_CANDIDATES:
        if p.exists():
            return str(p)
    return "adb"


def get_devices() -> list[str]:
    try:
        r = subprocess.run(
            [find_adb(), "devices"],
            capture_output=True, text=True, timeout=10,
        )
        devices = []
        for line in r.stdout.strip().splitlines()[1:]:
            if line.strip() and "device" in line and "offline" not in line:
                devices.append(line.split()[0])
        return devices
    except Exception as e:
        log.error(f"Failed to list devices: {e}")
        return []


def push_sources(device: str, sources: list[str]):
    """Push source URLs into the app's SharedPreferences via ADB."""
    prefs_json = json.dumps(sources)
    adb = find_adb()

    # Write sources to device temp, then use run-as to inject into SharedPreferences
    cmds = [
        [adb, "-s", device, "shell", "echo", f"'{prefs_json}'", ">", "/data/local/tmp/premium_sources.json"],
        [adb, "-s", device, "shell", "run-as", f"{PACKAGE}.core",
         "cp", "/data/local/tmp/premium_sources.json",
         f"/data/data/{PACKAGE}.core/shared_prefs/{PREFS_FILE}.xml"],
    ]

    # Actually, SharedPreferences are XML, not raw JSON.
    # We need to write proper XML. Let's do it properly.
    import xml.sax.saxutils as saxutils

    xml = '<?xml version="1.0" encoding="utf-8"?>\n<map>\n'
    xml += f'  <string name="sources_json">{saxutils.escape(prefs_json)}</string>\n'
    xml += f'  <long name="discovered_at_ms" value="0" />\n'
    xml += "</map>\n"

    # Write XML to temp
    local_tmp = Path("/data/local/tmp/premium_sources.xml") if sys.platform != "win32" else None
    import tempfile
    tmp = Path(tempfile.gettempdir()) / "premium_sources.xml"
    tmp.write_text(xml, encoding="utf-8")

    # Push via ADB
    try:
        subprocess.run(
            [adb, "-s", device, "push", str(tmp), f"/data/local/tmp/{PREFS_FILE}.xml"],
            capture_output=True, text=True, timeout=10, check=True,
        )
        subprocess.run(
            [adb, "-s", device, "shell", "run-as", f"{PACKAGE}.core",
             "cp", f"/data/local/tmp/{PREFS_FILE}.xml",
             f"/data/data/{PACKAGE}.core/shared_prefs/{PREFS_FILE}.xml"],
            capture_output=True, text=True, timeout=10, check=True,
        )
        log.info(f"  {device}: pushed {len(sources)} sources")
    except subprocess.CalledProcessError as e:
        log.warning(f"  {device}: push failed ({e.stderr.strip() or e.returncode})")
    finally:
        tmp.unlink(missing_ok=True)


def main():
    import argparse
    parser = argparse.ArgumentParser(description="Deploy premium sources to emulators")
    parser.add_argument("--config", default="premium_sources.json",
                        help="JSON config with 'sources' array (default: premium_sources.json)")
    parser.add_argument("--device", help="Specific device serial (e.g. emulator-5554)")
    args = parser.parse_args()

    config_path = Path(args.config)
    if not config_path.exists():
        log.error(f"Config not found: {config_path.resolve()}")
        log.info("Run the scraper first: python scraper.py --hunters github,aggregators")
        sys.exit(1)

    with open(config_path) as f:
        config = json.load(f)
    sources = config.get("sources", [])

    if not sources:
        log.warning("No sources in config — nothing to deploy")
        return

    devices = [args.device] if args.device else get_devices()
    if not devices:
        log.error("No connected devices found. Start an emulator first.")
        sys.exit(1)

    log.info(f"Deploying {len(sources)} sources to {len(devices)} device(s)...")
    for d in devices:
        push_sources(d, sources)

    log.info("Done. Restart the app for PremiumClient to pick up new sources.")
    print(f"\nHint:  adb -s {devices[0]} shell am force-stop {PACKAGE}.app")
    print(f"       adb -s {devices[0]} shell am start -n {PACKAGE}.app/.MainActivity")


if __name__ == "__main__":
    main()
