"""Build the native terminal APK using the existing SDK. No Gradle download."""
import pathlib, subprocess, zipfile, os
root = pathlib.Path(__file__).resolve().parent
sdk = pathlib.Path(os.environ.get('ANDROID_HOME', r'C:\Users\User\AppData\Local\Android\Sdk'))
jdk = pathlib.Path(os.environ.get('STILLWATCH_JDK', r'C:\Program Files\Android\Android Studio\jbr'))
tools = sdk / 'build-tools' / '37.0.0'
platform = sdk / 'platforms' / 'android-36.1' / 'android.jar'
build = root / 'build'
for sub in ['generated', 'classes', 'dex']: (build / sub).mkdir(parents=True, exist_ok=True)
def run(args): subprocess.run([str(x) for x in args], cwd=root, check=True)
resources = build / 'resources.apk'
run([tools / 'aapt2.exe', 'link', '-I', platform, '--manifest', root / 'AndroidManifest.xml', '-o', resources, '--java', build / 'generated'])
run([jdk / 'bin/javac.exe', '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-classpath', platform, '-d', build / 'classes', *root.glob('src/**/*.java')])
run([jdk / 'bin/java.exe', '-cp', tools / 'lib/d8.jar', 'com.android.tools.r8.D8', '--min-api', '26', '--lib', platform, '--output', build / 'dex', *build.glob('classes/**/*.class')])
unsigned = build / 'unsigned.apk'
with zipfile.ZipFile(resources) as source, zipfile.ZipFile(unsigned, 'w') as target:
    for item in source.infolist(): target.writestr(item, source.read(item.filename))
    target.write(build / 'dex/classes.dex', 'classes.dex')
key = build / 'debug.jks'
if not key.exists(): run([jdk / 'bin/keytool.exe', '-genkeypair', '-keystore', key, '-alias', 'debug', '-storepass', 'android', '-keypass', 'android', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '3650', '-dname', 'CN=StillWatch Diagnostic Debug'])
aligned = build / 'aligned.apk'
run([tools / 'zipalign.exe', '-f', '4', unsigned, aligned])
apk = build / 'stillwatch-worker.apk'
run([jdk / 'bin/java.exe', '-jar', tools / 'lib/apksigner.jar', 'sign', '--ks', key, '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', apk, aligned])
run([jdk / 'bin/java.exe', '-jar', tools / 'lib/apksigner.jar', 'verify', apk])
print(apk)
