from pilot_tools import *
resources=parsed('cloudformation','list-stack-resources','--stack-name','stillwatch-pilot')['StackResourceSummaries']
table=next(r['PhysicalResourceId'] for r in resources if r['LogicalResourceId']=='State')
key=save('db-key.json',{'pk':{'S':'ZONE#room-01'},'sk':{'S':'STATE'}})
values=save('db-reset-values.json',{':t1':{'N':'300'},':t2':{'N':'60'},':one':{'N':'1'}})
aws('dynamodb','update-item','--table-name',table,'--key',key,'--update-expression','SET t1 = :t1, t2 = :t2, #v = #v + :one','--expression-attribute-names','{"#v":"version"}','--expression-attribute-values',values)
print('Restored inactivity threshold to 300 seconds and worker response window to 60 seconds; existing case deadline preserved.')
