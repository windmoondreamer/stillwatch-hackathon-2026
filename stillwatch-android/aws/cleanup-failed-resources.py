from pilot_tools import *
path=PRIVATE/'failed-stack-retained.json'
old=json.loads(path.read_text(encoding='utf-8')) if path.exists() else []
current=parsed('cloudformation','list-stack-resources','--stack-name','stillwatch-pilot')['StackResourceSummaries']
active={r['PhysicalResourceId'] for r in current if r.get('PhysicalResourceId')}
results=[]
for r in old:
 name=r['PhysicalResourceId'];kind=r['ResourceType']
 if name in active:raise RuntimeError('Refusing active resource cleanup')
 if kind=='AWS::DynamoDB::Table':
  if parsed('dynamodb','scan','--table-name',name,'--limit','1')['Count']:results.append({'type':kind,'result':'kept_nonempty'});continue
  aws('dynamodb','delete-table','--table-name',name)
 elif kind=='AWS::S3::Bucket':
  versions=parsed('s3api','list-object-versions','--bucket',name,'--max-items','1')
  if versions.get('Versions') or versions.get('DeleteMarkers'):results.append({'type':kind,'result':'kept_nonempty'});continue
  aws('s3api','delete-bucket','--bucket',name)
 elif kind=='AWS::Cognito::UserPool':
  if parsed('cognito-idp','list-users','--user-pool-id',name,'--limit','1')['Users']:results.append({'type':kind,'result':'kept_nonempty'});continue
  aws('cognito-idp','update-user-pool','--user-pool-id',name,'--deletion-protection','INACTIVE')
  aws('cognito-idp','delete-user-pool','--user-pool-id',name)
 else:continue
 results.append({'type':kind,'result':'deleted_own_empty_failed_resource'})
save('failed-resource-cleanup.json',results)
print(json.dumps(results))
