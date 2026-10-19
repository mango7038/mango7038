#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
for density in mdpi hdpi xhdpi xxhdpi xxxhdpi; do
  source_file="$ROOT/icon-assets/mipmap-$density/ic_launcher.png.base64"
  target_dir="$ROOT/app/src/main/res/mipmap-$density"
  mkdir -p "$target_dir"
  base64 --decode "$source_file" > "$target_dir/ic_launcher.png"
done
echo "PostFlow launcher PNGs prepared."
