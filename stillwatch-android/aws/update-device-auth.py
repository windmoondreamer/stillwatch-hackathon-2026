import zipfile
from pilot_tools import *
resources=parsed('cloudformation','list-stack-resources','--stack-name','stillwatch-pilot')['StackResourceSummaries']
name=next(r['PhysicalResourceId'] for r in resources if r['LogicalResourceId']=='DeviceAuthFunction')
archive=PRIVATE/'device-auth.zip'
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:z.write(ROOT/'aws/device-auth.cjs','index.js')
aws('lambda','update-function-code','--function-name',name,'--zip-file','fileb://'+str(archive))
print('Device policy now allows native configuration topics scoped to this sensor.')
