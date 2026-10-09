"""Reauthenticate existing pilot users without changing accounts, roles or passwords."""
from pilot_tools import *
accounts=json.loads((PRIVATE/'accounts.json').read_text(encoding='utf-8'))
client=metadata()['CognitoClientId']
for role in ['MANAGER','WORKER']:
 account=accounts[role]
 request=save('auth-request.json',{'AuthFlow':'USER_PASSWORD_AUTH','ClientId':client,'AuthParameters':{'USERNAME':account['username'],'PASSWORD':account['password']}})
 auth=parsed('cognito-idp','initiate-auth','--cli-input-json',request)['AuthenticationResult']
 save(role.lower()+'-session.json',{'access_token':auth['AccessToken'],'id_token':auth['IdToken'],'refresh_token':auth['RefreshToken'],'expires_in':auth['ExpiresIn'],'expiresAt':int(time.time()*1000)+auth['ExpiresIn']*1000})
 print(role+' session renewed; credentials remain private.')
