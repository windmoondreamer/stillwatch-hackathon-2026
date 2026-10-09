"""Deploy the private pilot. Secrets are passed through local files, never printed."""
import json,pathlib,re,subprocess,sys,secrets,os
ROOT=pathlib.Path(__file__).resolve().parent.parent
PRIVATE=ROOT/'.private';PRIVATE.mkdir(exist_ok=True)
CLI=r'C:\Program Files\Amazon\AWSCLIV2\aws.exe'
PROFILE='stillwatch';REGION='ap-southeast-2'
def aws(*args,allow_error=False):
 result=subprocess.run([CLI,*args,'--profile',PROFILE,'--region',REGION,'--no-cli-pager'],capture_output=True,text=True,encoding='utf-8',errors='replace')
 if result.returncode and not allow_error: raise RuntimeError(re.sub(r'sk-[A-Za-z0-9_-]+','[REDACTED]',result.stderr))
 return result
def parsed(*args): return json.loads(aws(*args,'--output','json').stdout or '{}')
identity=parsed('sts','get-caller-identity');account=identity['Account']
name='stillwatch/pilot/openai'
existing=aws('secretsmanager','describe-secret','--secret-id',name,allow_error=True)
secret_file=PRIVATE/'openai-secret.json'
key=os.environ.get('OPENAI_API_KEY','').strip()
if not key and secret_file.exists():key=json.loads(secret_file.read_text(encoding='utf-8')).get('apiKey','')
legacy=ROOT.parent/'stillwatch-voice'/'.env'
if not key and legacy.exists():
 match=re.search(r'^OPENAI_API_KEY=(.+)$',legacy.read_text(encoding='utf-8'),re.M)
 if match:key=match.group(1).strip().strip('"\'')
if key:secret_file.write_text(json.dumps({'apiKey':key}),encoding='utf-8')
if existing.returncode:
 if 'ResourceNotFoundException' not in existing.stderr: raise RuntimeError(existing.stderr)
 if not key:raise RuntimeError('Configure OPENAI_API_KEY or .private/openai-secret.json for the initial deployment.')
 secret=parsed('secretsmanager','create-secret','--name',name,'--secret-string','file://'+str(secret_file),'--tags','Key=Application,Value=StillWatch')
else:
 secret=json.loads(existing.stdout)
 if key:
  stored=json.loads(parsed('secretsmanager','get-secret-value','--secret-id',name)['SecretString'])
  if stored.get('apiKey')!=key:aws('secretsmanager','put-secret-value','--secret-id',name,'--secret-string','file://'+str(secret_file))
bucket='stillwatch-builds-'+account+'-'+REGION
existing=aws('s3api','head-bucket','--bucket',bucket,allow_error=True)
if existing.returncode:
 if '404' not in existing.stderr and 'Not Found' not in existing.stderr: raise RuntimeError(existing.stderr)
 parsed('s3api','create-bucket','--bucket',bucket,'--create-bucket-configuration','LocationConstraint='+REGION)
aws('s3api','put-public-access-block','--bucket',bucket,'--public-access-block-configuration','BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true')
encryption=PRIVATE/'bucket-encryption.json';encryption.write_text(json.dumps({'Rules':[{'ApplyServerSideEncryptionByDefault':{'SSEAlgorithm':'AES256'}}]}),encoding='utf-8')
aws('s3api','put-bucket-encryption','--bucket',bucket,'--server-side-encryption-configuration','file://'+str(encryption))
metadata=json.loads((PRIVATE/'deployment.json').read_text(encoding='utf-8')) if (PRIVATE/'deployment.json').exists() else {}
metadata.update(account=account,region=REGION,profile=PROFILE,bucket=bucket,secretArn=secret['ARN'],stack='stillwatch-pilot')
device_params=[]
if (PRIVATE/'hardware.json').exists():
 hardware=json.loads((PRIVATE/'hardware.json').read_text(encoding='utf-8-sig'))
 if hardware.get('nativeDeviceId'):
  secret_name='stillwatch/pilot/esp-mqtt';existing_device=aws('secretsmanager','describe-secret','--secret-id',secret_name,allow_error=True)
  device_file=PRIVATE/'device-secret.json'
  if existing_device.returncode:
   if 'ResourceNotFoundException' not in existing_device.stderr:raise RuntimeError(existing_device.stderr)
   device_file.write_text(json.dumps({'username':'stillwatch-room-01','password':secrets.token_urlsafe(32),'nativeDeviceId':hardware['nativeDeviceId']}),encoding='utf-8')
   device_secret=parsed('secretsmanager','create-secret','--name',secret_name,'--secret-string','file://'+str(device_file),'--tags','Key=Application,Value=StillWatch')
  else:
   device_secret=json.loads(existing_device.stdout)
   if not device_file.exists():device_file.write_text(parsed('secretsmanager','get-secret-value','--secret-id',secret_name)['SecretString'],encoding='utf-8')
  metadata.update(nativeDeviceId=hardware['nativeDeviceId'],deviceSecretArn=device_secret['ARN'])
  device_params=['NativeDeviceId='+hardware['nativeDeviceId'],'DeviceSecretArn='+device_secret['ARN']]
(PRIVATE/'deployment.json').write_text(json.dumps(metadata,indent=2),encoding='utf-8')
print('Private API secret and build bucket ready. Deploying authenticated application stack...',flush=True)
previous=aws('cloudformation','describe-stacks','--stack-name',metadata['stack'],allow_error=True)
if not previous.returncode and json.loads(previous.stdout)['Stacks'][0]['StackStatus']=='ROLLBACK_COMPLETE':
 resources=parsed('cloudformation','list-stack-resources','--stack-name',metadata['stack'])['StackResourceSummaries']
 (PRIVATE/'failed-stack-retained.json').write_text(json.dumps([r for r in resources if r['ResourceStatus']=='DELETE_SKIPPED'],indent=2),encoding='utf-8')
 print('Replacing failed initial stack; retained empty resources recorded locally.',flush=True)
 aws('cloudformation','delete-stack','--stack-name',metadata['stack'])
 aws('cloudformation','wait','stack-delete-complete','--stack-name',metadata['stack'])
result=aws('cloudformation','deploy','--template-file',str(ROOT/'aws/template.json'),'--s3-bucket',bucket,'--stack-name',metadata['stack'],'--capabilities','CAPABILITY_IAM','--parameter-overrides','LoginDomainPrefix=stillwatch-pilot-'+account,'OpenAiSecretArn='+secret['ARN'],*device_params)
print(result.stdout,flush=True)
outputs=parsed('cloudformation','describe-stacks','--stack-name',metadata['stack'])['Stacks'][0]['Outputs']
values={item['OutputKey']:item['OutputValue'] for item in outputs};metadata.update(values)
(PRIVATE/'deployment.json').write_text(json.dumps(metadata,indent=2),encoding='utf-8')
if values.get('NativeDomainName'):
 native_domain=parsed('iot','describe-domain-configuration','--domain-configuration-name',values['NativeDomainName'])
 metadata['nativeEndpoint']=native_domain['domainName'];(PRIVATE/'deployment.json').write_text(json.dumps(metadata,indent=2),encoding='utf-8')
(ROOT/'aws-config.properties').write_text('apiUrl='+values['ApiUrl']+'\ncognitoDomain='+values['CognitoDomain']+'\ncognitoClientId='+values['CognitoClientId']+'\n',encoding='utf-8')
print('Deployed HTTPS API: '+values['ApiUrl'])
