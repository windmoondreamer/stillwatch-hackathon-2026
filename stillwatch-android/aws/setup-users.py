import secrets,time,json
from pilot_tools import *
m=metadata();pool=m['UserPoolId'];client=m['CognitoClientId']
accounts=json.loads((PRIVATE/'accounts.json').read_text()) if (PRIVATE/'accounts.json').exists() else {}
for role in ['MANAGER','WORKER']:
 name='stillwatch-'+role.lower();password=accounts.get(role,{}).get('password') or secrets.token_urlsafe(22)+'aA1!'
 user=aws('cognito-idp','admin-get-user','--user-pool-id',pool,'--username',name,allow_error=True)
 if user.returncode:
  if 'UserNotFoundException' not in user.stderr:raise RuntimeError(user.stderr)
  parsed('cognito-idp','admin-create-user','--user-pool-id',pool,'--username',name,'--message-action','SUPPRESS')
 aws('cognito-idp','admin-set-user-password','--user-pool-id',pool,'--username',name,'--password',password,'--permanent')
 aws('cognito-idp','admin-add-user-to-group','--user-pool-id',pool,'--username',name,'--group-name',role)
 auth=parsed('cognito-idp','initiate-auth','--cli-input-json',save('auth-request.json',{'AuthFlow':'USER_PASSWORD_AUTH','ClientId':client,'AuthParameters':{'USERNAME':name,'PASSWORD':password}}))['AuthenticationResult']
 save(role.lower()+'-session.json',{'access_token':auth['AccessToken'],'id_token':auth['IdToken'],'refresh_token':auth['RefreshToken'],'expires_in':auth['ExpiresIn'],'expiresAt':int(time.time()*1000)+auth['ExpiresIn']*1000})
 accounts[role]={'username':name,'password':password}
 print(role+' pilot login ready; credentials remain in the private local file.',flush=True)
save('accounts.json',accounts)
