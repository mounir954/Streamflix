@echo off
rem Construit l'interface (export statique Next.js) et l'empaquette dans app\src\main\assets\web.zip
rem Necessite Node.js 20+ et Python 3. Un web.zip deja construit est fourni dans le projet.
cd /d "%~dp0web" || exit /b 1
call npm ci --no-audit --no-fund || exit /b 1
set NEXT_PUBLIC_PLATFORM=android
call npx next build || exit /b 1
python -c "import os,zipfile;src='out';dest='../app/src/main/assets/web.zip';os.makedirs(os.path.dirname(dest),exist_ok=True);os.path.exists(dest) and os.remove(dest);z=zipfile.ZipFile(dest,'w',zipfile.ZIP_DEFLATED,compresslevel=9);[z.write(os.path.join(d,f),os.path.relpath(os.path.join(d,f),src).replace(os.sep,'/')) for d,_,fs in os.walk(src) for f in sorted(fs)];z.close();print('web.zip OK')"
