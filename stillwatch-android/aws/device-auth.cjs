const {SecretsManagerClient,GetSecretValueCommand}=require('@aws-sdk/client-secrets-manager');
const {timingSafeEqual}=require('node:crypto');
const client=new SecretsManagerClient({});let cached;
exports.handler=async event=>{
 try{
  if(!cached||cached.until<Date.now()){const r=await client.send(new GetSecretValueCommand({SecretId:process.env.DEVICE_SECRET_ARN}));cached={...JSON.parse(r.SecretString),until:Date.now()+300000};}
  const mqtt=event.protocolData?.mqtt||{},password=Buffer.from(mqtt.password||'','base64'),expected=Buffer.from(cached.password);
  if(mqtt.username!==cached.username||password.length!==expected.length||!timingSafeEqual(password,expected)||!/^[-a-zA-Z0-9_:]{1,128}$/.test(mqtt.clientId||''))return {isAuthenticated:false};
  const arn=`arn:aws:iot:${process.env.AWS_REGION}:${process.env.ACCOUNT_ID}`,prefix=`espectre/v1/devices/${cached.nativeDeviceId}`;
  return {isAuthenticated:true,principalId:cached.nativeDeviceId,disconnectAfterInSeconds:86400,refreshAfterInSeconds:300,policyDocuments:[{Version:'2012-10-17',Statement:[
   {Effect:'Allow',Action:'iot:Connect',Resource:`${arn}:client/${mqtt.clientId}`},
   {Effect:'Allow',Action:['iot:Publish','iot:RetainPublish'],Resource:[`${arn}:topic/${prefix}/*`,`${arn}:topic/homeassistant/*/native_${cached.nativeDeviceId}_*/config`]},
   {Effect:'Allow',Action:'iot:Subscribe',Resource:[`${arn}:topicfilter/${prefix}/*`,`${arn}:topicfilter/homeassistant/status`]},
   {Effect:'Allow',Action:'iot:Receive',Resource:[`${arn}:topic/${prefix}/*`,`${arn}:topic/homeassistant/status`]}
  ]}]};
 }catch{return {isAuthenticated:false};}
};
