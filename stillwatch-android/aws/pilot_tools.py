import json,pathlib,subprocess,re,time,urllib.request,os
ROOT=pathlib.Path(__file__).resolve().parent.parent
PRIVATE=ROOT/'.private'
CLI=r'C:\Program Files\Amazon\AWSCLIV2\aws.exe'
ADB=r'C:\Users\User\AppData\Local\Android\Sdk\platform-tools\adb.exe'
def aws(*args,allow_error=False):
 r=subprocess.run([CLI,*args,'--profile','stillwatch','--region','ap-southeast-2','--no-cli-pager'],capture_output=True,text=True,encoding='utf-8',errors='replace',env={**os.environ,'AWS_CLI_FILE_ENCODING':'UTF-8','PYTHONUTF8':'1'})
 if r.returncode and not allow_error:raise RuntimeError(re.sub(r'sk-[A-Za-z0-9_-]+','[REDACTED]',r.stderr))
 return r
def parsed(*args):return json.loads(aws(*args,'--output','json').stdout or '{}')
def metadata():
 m=json.loads((PRIVATE/'deployment.json').read_text(encoding='utf-8'))
 if not m.get('ApiUrl'):
  stack=parsed('cloudformation','describe-stacks','--stack-name',m['stack'])['Stacks'][0]
  m.update({o['OutputKey']:o['OutputValue'] for o in stack.get('Outputs',[])})
 return m
def save(name,data):
 p=PRIVATE/name;p.write_text(json.dumps(data,ensure_ascii=True,indent=2),encoding='utf-8');return 'file://'+str(p)
def adb(*args,input=None):
 serial=os.environ.get('STILLWATCH_ADB_SERIAL','').strip()
 r=subprocess.run([ADB,*(['-s',serial] if serial else ['-d']),*args],input=input,capture_output=True)
 if r.returncode:raise RuntimeError(r.stderr.decode(errors='replace')[:500])
 return r.stdout.decode(errors='replace')
def session(role):return json.loads((PRIVATE/(role.lower()+'-session.json')).read_text(encoding='utf-8'))
def api(role,method,path,data=None):
 token=session(role)['access_token'];url=metadata()['ApiUrl']+path
 request=urllib.request.Request(url,data=None if data is None else json.dumps(data).encode(),method=method,headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'})
 with urllib.request.urlopen(request,timeout=40) as r:return json.load(r)
