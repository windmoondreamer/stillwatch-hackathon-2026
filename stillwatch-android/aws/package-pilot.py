"""Export the APK and a portable source ZIP without private data or build caches."""
import pathlib,zipfile,shutil,hashlib,re,json
root=pathlib.Path(__file__).resolve().parent.parent
out=root/'releases';out.mkdir(exist_ok=True)
apk=out/'StillWatch-AWS-Voice-0.3.0.apk'
shutil.copy2(root/'app/build/outputs/apk/debug/app-debug.apk',apk)
source=out/'StillWatch-AWS-Voice-source-0.3.0.zip'
excluded={'.private','.gradle','.kotlin','.idea','build','releases','node_modules','__pycache__'}
secret_pattern=re.compile(rb'sk-(?:proj-)?[A-Za-z0-9_-]{30,}')
count=0
with zipfile.ZipFile(source,'w',zipfile.ZIP_DEFLATED) as z:
 for p in sorted(root.rglob('*')):
  if not p.is_file():continue
  rel=p.relative_to(root)
  if any(part in excluded for part in rel.parts) or p.name in {'local.properties','.env','google-services.json'} or p.suffix in {'.jks','.keystore','.apk'}:continue
  data=p.read_bytes()
  if secret_pattern.search(data):raise RuntimeError('Secret-like content in export: '+str(rel))
  z.writestr('stillwatch-android/'+rel.as_posix(),data);count+=1
with zipfile.ZipFile(apk) as z:
 for name in z.namelist():
  if name.endswith('.dex') and secret_pattern.search(z.read(name)):raise RuntimeError('Secret-like content in APK')
manifest={'version':'0.3.0','sourceFiles':count,'artifacts':[{'name':p.name,'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in [apk,source]]}
(out/'manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
print(json.dumps({'version':manifest['version'],'sourceFiles':count,'artifacts':[{'name':a['name'],'bytes':a['bytes']} for a in manifest['artifacts']]}))
