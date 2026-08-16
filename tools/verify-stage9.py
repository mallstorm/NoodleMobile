#!/usr/bin/env python3
from pathlib import Path
import sys
R=Path(__file__).resolve().parent.parent
C=[]
def rd(x): return (R/x).read_text()
def ck(n,v): C.append((n,bool(v)))
app=rd('app/build.gradle.kts'); top=rd('build.gradle.kts'); ident=rd('app/src/main/java/pro/noodle/mobile/core/version/BuildIdentity.kt')
boot=rd('tools/bootstrap-android-toolchain.sh'); ci=rd('tools/ci-android-build.sh'); cert=rd('tools/verify-apk.sh'); bc=rd('tools/build-check.sh'); wf=rd('.github/workflows/android-ci.yml'); props=rd('gradle/wrapper/gradle-wrapper.properties'); notes=rd('PORTING_NOTES.md')
ck('versionCode 90009','versionCode = 100010' in app); ck('versionName alpha9','versionName = "0.10.0-alpha10"' in app)
ck('identity synchronized','VERSION_NAME = "0.10.0-alpha10"' in ident and 'VERSION_CODE = 100010' in ident and 'USER_AGENT = "NoodleMobile/0.10 Android"' in ident)
ck('SNP compatibility independent','SNP_COMPATIBILITY_VERSION = "1.9.0"' in ident)
ck('Compose BOM stable',app.count('compose-bom:2026.06.00')==2 and '2026.06.01' not in app)
ck('Media3 stable 1.10.1',app.count(':1.10.1')>=7 and ':1.11.0' not in app)
ck('AGP9.3.1','id("com.android.application") version "9.3.1"' in top); ck('KSP2.3.11','id("com.google.devtools.ksp") version "2.3.11"' in top)
ck('Gradle wrapper dist','gradle-9.5.1-bin.zip' in props); ck('Gradle wrapper SHA','bafc141b619ad6350fd975fc903156dd5c151998cc8b058e8c1044ab5f7b031f' in props)
ck('bootstrap executable',(R/'tools/bootstrap-android-toolchain.sh').stat().st_mode & 0o111); ck('verify apk executable',(R/'tools/verify-apk.sh').stat().st_mode & 0o111)
ck('official Gradle SHA in bootstrap','bafc141b619ad6350fd975fc903156dd5c151998cc8b058e8c1044ab5f7b031f' in boot)
ck('official Android CLI SHA in bootstrap','4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583' in boot)
ck('CLI tools build','commandlinetools-linux-15859902_latest.zip' in boot); ck('SDK37 bootstrap','platforms;android-37' in boot); ck('BT36 bootstrap','build-tools;36.0.0' in boot)
ck('checksum fail closed','sha256sum -c -' in boot and 'set -euo pipefail' in boot); ck('CI full tasks',all(x in ci for x in [':app:testDebugUnitTest',':app:lintDebug',':app:assembleDebug',':app:assembleDebugAndroidTest']))
ck('CI delegates APK cert','tools/verify-apk.sh' in ci); ck('cert package alpha9',"package: name='pro.noodle.mobile' versionCode='100010' versionName='0.10.0-alpha10'" in cert); ck('cert target37',"targetSdkVersion:'37'" in cert); ck('cert zip','unzip -t' in cert); ck('cert sha','sha256sum "$APK"' in cert)
ck('build check stage9','verify-stage10.sh' in bc and 'alpha10 build preflight' in bc); ck('local bootstrap gradle path','.toolchain/gradle-9.5.1/bin/gradle' in bc); ck('workflow alpha9','NoodleMobile-0.10.0-alpha10-first-test-apk' in wf); ck('workflow modern actions',all(x in wf for x in ['actions/checkout@v6','actions/setup-java@v5','android-actions/setup-android@v4','gradle/actions/setup-gradle@v6','actions/upload-artifact@v7']))
ck('stale adaptive sentinel removed','DOWNLOAD_ADAPTIVE_NOT_YET_ENABLED' not in notes)
for n,v in C: print(('PASS' if v else 'FAIL')+' | '+n)
f=[n for n,v in C if not v]; print(f'RESULT | pass={len(C)-len(f)} fail={len(f)} total={len(C)}'); sys.exit(1 if f else 0)
