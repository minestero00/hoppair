#!/usr/bin/env python3
"""Compile the Apple Icon Composer source, including legacy macOS fallback sizes."""
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
source = root / "assets/app-icon/AppIcon.icon"
output = root / "dist/CompiledIcons"
output.mkdir(parents=True, exist_ok=True)
subprocess.run(["xcrun", "actool", str(source), "--compile", str(output), "--platform", "macosx", "--minimum-deployment-target", "14.0", "--app-icon", "AppIcon", "--output-partial-info-plist", str(output / "partial.plist"), "--target-device", "mac", "--warnings", "--notices"], check=True)
