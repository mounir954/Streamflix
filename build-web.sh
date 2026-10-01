#!/usr/bin/env bash
# Construit l'interface (export statique Next.js) et l'empaquette dans app/src/main/assets/web.zip.
# Nécessite Node.js 20+. Un web.zip déjà construit est fourni : ce script n'est utile que si vous modifiez web/.
set -euo pipefail
cd "$(dirname "$0")/web"
npm ci --no-audit --no-fund
NEXT_PUBLIC_PLATFORM=android npx next build
python3 - <<'PY'
import os, zipfile
src, dest = "out", "../app/src/main/assets/web.zip"
os.makedirs(os.path.dirname(dest), exist_ok=True)
if os.path.exists(dest): os.remove(dest)
with zipfile.ZipFile(dest, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    for folder, _, files in os.walk(src):
        for f in sorted(files):
            full = os.path.join(folder, f)
            z.write(full, os.path.relpath(full, src).replace(os.sep, "/"))
print(f"web.zip : {os.path.getsize(dest)/1024/1024:.1f} Mo")
PY
