import fs from 'node:fs/promises';
const read=name=>fs.readFile(new URL(name,import.meta.url),'utf8');
const domain=(await read('domain.mjs')).replace(/^import .*;\r?\n/gm,'').replace(/^export /gm,'');
const handler=(await read('handler.mjs')).replace(/^import .*;\r?\n/gm,'').replace(/^export /gm,'');
const signing=(await read('s3-sign.mjs')).replace(/^import .*;\r?\n/gm,'').replace(/^export /gm,'');
const additions=await Promise.all(['flow.mjs','report.mjs','audio.mjs','native.mjs'].map(async name=>(await read(name)).replace(/^import .*;\r?\n/gm,'').replace(/^export /gm,'')));
const code=`const {randomUUID,createHash,createHmac,createSign}=require('node:crypto');\nconst {DynamoDBClient}=require('@aws-sdk/client-dynamodb');\nconst {DynamoDBDocumentClient,GetCommand,PutCommand,QueryCommand,DeleteCommand}=require('@aws-sdk/lib-dynamodb');\nconst {SFNClient,StartExecutionCommand}=require('@aws-sdk/client-sfn');\nconst {LambdaClient,InvokeCommand}=require('@aws-sdk/client-lambda');\nconst {SNSClient,PublishCommand}=require('@aws-sdk/client-sns');\nconst {SecretsManagerClient,GetSecretValueCommand}=require('@aws-sdk/client-secrets-manager');\n${domain}\n${signing}\n${additions.join('\n')}\n${handler}\nexports.handler=handler;`;
const ref=name=>({Ref:name}),get=(name,attribute)=>({'Fn::GetAtt':[name,attribute]}),sub=value=>({'Fn::Sub':value});
const context=(why,must=[])=>({'com.aws.cloudformation.Context':{why,must}});
const resource=(Type,Properties,why,must=[],extra={})=>({Type,Metadata:context(why,must),Properties,...extra});
const template={
  AWSTemplateFormatVersion:'2010-09-09',
  Description:'StillWatch single-zone Android application backend with authenticated worker actions, durable state and IoT telemetry ingestion.',
  Metadata:{AWSToolsMetrics:{AWSAgentToolkit:'aws-cloudformation@3'},'com.aws.cloudformation.Context':{must:['Deploy only in the selected project Region ap-southeast-2.','Worker response and manager acknowledgement must remain separate.','No plaintext credentials in source or outputs.'],ref:[{at:'README.md',has:'deployment and integration limitations'}]}},
  Parameters:{
    ZoneId:{Type:'String',Default:'room-01',AllowedPattern:'[a-zA-Z0-9_-]{1,40}'},
    DeviceId:{Type:'String',Default:'stillwatch-room-01',AllowedPattern:'[a-zA-Z0-9_-]{1,80}'},
    LoginDomainPrefix:{Type:'String',AllowedPattern:'[a-z0-9-]{3,50}',Description:'Globally unique Cognito domain prefix.'},
    FirebaseSecretName:{Type:'String',Default:'',Description:'Optional existing Secrets Manager secret name holding Firebase service-account JSON; never provide its content.',AllowedPattern:'[a-zA-Z0-9/_+=.@-]{0,200}'},
    OpenAiSecretArn:{Type:'String',Description:'Existing Secrets Manager ARN holding {apiKey}; never the secret value.'},
    NativeDeviceId:{Type:'String',Default:'',AllowedPattern:'[a-f0-9]{16}|'},
    DeviceSecretArn:{Type:'String',Default:'',Description:'Device MQTT secret ARN; never the credentials.'}
  },
  Conditions:{HasFirebase:{'Fn::Not':[{'Fn::Equals':[ref('FirebaseSecretName'),'']}]},HasNative:{'Fn::Not':[{'Fn::Equals':[ref('NativeDeviceId'),'']}] }},
  Resources:{},Outputs:{}
};
const R=template.Resources;
R.State=resource('AWS::DynamoDB::Table',{BillingMode:'PAY_PER_REQUEST',AttributeDefinitions:[{AttributeName:'pk',AttributeType:'S'},{AttributeName:'sk',AttributeType:'S'}],KeySchema:[{AttributeName:'pk',KeyType:'HASH'},{AttributeName:'sk',KeyType:'RANGE'}],SSESpecification:{SSEEnabled:true},PointInTimeRecoverySpecification:{PointInTimeRecoveryEnabled:true}},'Persistent zone state, response deadlines and notification intents.', ['Retain data on stack removal; preserve version-based conditional updates.'],{DeletionPolicy:'Retain',UpdateReplacePolicy:'Retain'});
R.FailedEvents=resource('AWS::SQS::Queue',{SqsManagedSseEnabled:true,MessageRetentionPeriod:1209600},'Retain asynchronous Lambda failures for investigation.',['Do not discard failed sensor events.']);
R.Floorplans=resource('AWS::S3::Bucket',{PublicAccessBlockConfiguration:{BlockPublicAcls:true,IgnorePublicAcls:true,BlockPublicPolicy:true,RestrictPublicBuckets:true},BucketEncryption:{ServerSideEncryptionConfiguration:[{ServerSideEncryptionByDefault:{SSEAlgorithm:'AES256'}}]},VersioningConfiguration:{Status:'Enabled'}},'Private PDF/image floorplans served only with short-lived signed URLs.',['Block all public access; retain uploaded plans.'],{DeletionPolicy:'Retain',UpdateReplacePolicy:'Retain'});
R.FloorplanPolicy=resource('AWS::S3::BucketPolicy',{Bucket:ref('Floorplans'),PolicyDocument:{Version:'2012-10-17',Statement:[{Effect:'Deny',Principal:'*',Action:'s3:*',Resource:[get('Floorplans','Arn'),sub('${Floorplans.Arn}/*')],Condition:{Bool:{'aws:SecureTransport':'false'}}}]}},'Reject unencrypted access to floorplan objects.');
R.Logs=resource('AWS::Logs::LogGroup',{LogGroupName:sub('/aws/lambda/${AWS::StackName}-api'),RetentionInDays:14},'Bounded diagnostic logs without sensor payloads or tokens.',['Never log credential contents or FCM tokens.']);
R.Execution=resource('AWS::IAM::Role',{
  AssumeRolePolicyDocument:{Version:'2012-10-17',Statement:[{Effect:'Allow',Principal:{Service:'lambda.amazonaws.com'},Action:'sts:AssumeRole'}]},
  Policies:[{PolicyName:'Runtime',PolicyDocument:{Version:'2012-10-17',Statement:[
    {Effect:'Allow',Action:['dynamodb:GetItem','dynamodb:PutItem','dynamodb:DeleteItem','dynamodb:Query'],Resource:get('State','Arn')},
    {Effect:'Allow',Action:['logs:CreateLogStream','logs:PutLogEvents'],Resource:get('Logs','Arn')},
    {Effect:'Allow',Action:['sqs:SendMessage'],Resource:get('FailedEvents','Arn')},
    {Effect:'Allow',Action:['s3:GetObject','s3:PutObject'],Resource:sub('${Floorplans.Arn}/${ZoneId}/*')},
    {Effect:'Allow',Action:['secretsmanager:GetSecretValue'],Resource:ref('OpenAiSecretArn')},
    {Effect:'Allow',Action:['sns:Publish'],Resource:'*'},
    {Effect:'Allow',Action:['states:StartExecution'],Resource:get('DeadlineFlow','Arn')},
    {Effect:'Allow',Action:['lambda:InvokeFunction'],Resource:sub('arn:${AWS::Partition}:lambda:${AWS::Region}:${AWS::AccountId}:function:${AWS::StackName}-api')}
  ]}}]
},'Lambda execution role scoped to its state table, logs and failure queue.',['Do not grant administrator or wildcard data permissions.']);
R.Backend=resource('AWS::Lambda::Function',{
  FunctionName:sub('${AWS::StackName}-api'),Runtime:'nodejs22.x',Handler:'index.handler',Role:get('Execution','Arn'),MemorySize:256,Timeout:60,
  DeadLetterConfig:{TargetArn:get('FailedEvents','Arn')},Code:{ZipFile:code},
  Environment:{Variables:{TABLE_NAME:ref('State'),ZONE_ID:ref('ZoneId'),DEVICE_ID:ref('DeviceId'),FLOORPLAN_BUCKET:ref('Floorplans'),
    OPENAI_SECRET_ARN:ref('OpenAiSecretArn'),SMS_TEST_RECIPIENT:'+821000000000',FLOW_ARN:get('DeadlineFlow','Arn'),NATIVE_DEVICE_ID:ref('NativeDeviceId'),
    FCM_SERVICE_ACCOUNT:{'Fn::If':['HasFirebase',sub('{{resolve:secretsmanager:${FirebaseSecretName}:SecretString}}'),'']}}}
},'Short API/event handlers; timers and notification intent persist in DynamoDB.',['No in-memory timer dependence; preserve authenticated role checks.']);
R.Users=resource('AWS::Cognito::UserPool',{
  AdminCreateUserConfig:{AllowAdminCreateUserOnly:true},
  Policies:{PasswordPolicy:{MinimumLength:12,RequireLowercase:true,RequireUppercase:true,RequireNumbers:true,RequireSymbols:true}},
  UserPoolTier:'ESSENTIALS',DeletionProtection:'ACTIVE',MfaConfiguration:'OPTIONAL',EnabledMfas:['SOFTWARE_TOKEN_MFA']
},'Separate application users from AWS project team members; invitation-based access.',['Do not enable public registration; retain user directory.'],{DeletionPolicy:'Retain',UpdateReplacePolicy:'Retain'});
R.ManagerGroup=resource('AWS::Cognito::UserPoolGroup',{GroupName:'MANAGER',UserPoolId:ref('Users')},'Application safety-manager role.',['Manager acknowledgement cannot impersonate a worker.']);
R.WorkerGroup=resource('AWS::Cognito::UserPoolGroup',{GroupName:'WORKER',UserPoolId:ref('Users')},'Application worker role.',['Only the registered worker can respond to their own check.']);
R.Client=resource('AWS::Cognito::UserPoolClient',{
  UserPoolId:ref('Users'),GenerateSecret:false,AllowedOAuthFlowsUserPoolClient:true,AllowedOAuthFlows:['code'],AllowedOAuthScopes:['openid','email'],
  CallbackURLs:['stillwatch://auth'],LogoutURLs:['stillwatch://auth'],SupportedIdentityProviders:['COGNITO'],
  EnableTokenRevocation:true,PreventUserExistenceErrors:'ENABLED',AccessTokenValidity:60,IdTokenValidity:60,RefreshTokenValidity:7,
  TokenValidityUnits:{AccessToken:'minutes',IdToken:'minutes',RefreshToken:'days'}
  ,ExplicitAuthFlows:['ALLOW_ADMIN_USER_PASSWORD_AUTH','ALLOW_USER_PASSWORD_AUTH','ALLOW_REFRESH_TOKEN_AUTH']
},'Public Android OAuth client using authorization-code PKCE.',['Never add a client secret to the Android application.']);
R.Login=resource('AWS::Cognito::UserPoolDomain',{Domain:ref('LoginDomainPrefix'),UserPoolId:ref('Users'),ManagedLoginVersion:2},'Hosted browser login rather than collecting application passwords in the app.');
R.LoginBranding=resource('AWS::Cognito::ManagedLoginBranding',{UserPoolId:ref('Users'),ClientId:ref('Client'),UseCognitoProvidedValues:true},'Enable the managed login page for this app client.');
R.Api=resource('AWS::ApiGatewayV2::Api',{Name:sub('${AWS::StackName}-api'),ProtocolType:'HTTP'},'HTTPS API front door for authenticated Android requests.',['Never expose unauthenticated worker or manager actions.']);
R.Authorizer=resource('AWS::ApiGatewayV2::Authorizer',{ApiId:ref('Api'),AuthorizerType:'JWT',IdentitySource:['$request.header.Authorization'],Name:'Cognito',JwtConfiguration:{Audience:[ref('Client')],Issuer:sub('https://cognito-idp.${AWS::Region}.amazonaws.com/${Users}')}},'Verify Cognito JWT issuer/audience before invoking application code.');
R.Integration=resource('AWS::ApiGatewayV2::Integration',{ApiId:ref('Api'),IntegrationType:'AWS_PROXY',IntegrationUri:get('Backend','Arn'),PayloadFormatVersion:'2.0'},'API Gateway to Lambda proxy integration.');
R.Route=resource('AWS::ApiGatewayV2::Route',{ApiId:ref('Api'),RouteKey:'ANY /api/{proxy+}',AuthorizationType:'JWT',AuthorizerId:ref('Authorizer'),Target:{'Fn::Join':['/',['integrations',ref('Integration')]]}},'Protect all application API paths; no anonymous route.');
R.Stage=resource('AWS::ApiGatewayV2::Stage',{ApiId:ref('Api'),StageName:'$default',AutoDeploy:true,DefaultRouteSettings:{ThrottlingBurstLimit:20,ThrottlingRateLimit:10}},'Bounded prototype API traffic.');
R.ApiPermission=resource('AWS::Lambda::Permission',{Action:'lambda:InvokeFunction',FunctionName:ref('Backend'),Principal:'apigateway.amazonaws.com',SourceAccount:ref('AWS::AccountId'),SourceArn:sub('arn:${AWS::Partition}:execute-api:${AWS::Region}:${AWS::AccountId}:${Api}/*/*/api/*')},'Allow only this project API to invoke the backend.');
R.Thing=resource('AWS::IoT::Thing',{ThingName:ref('DeviceId')},'Register the expected physical ESP32 identity; firmware is managed separately.');
R.DevicePolicy=resource('AWS::IoT::Policy',{PolicyDocument:{Version:'2012-10-17',Statement:[
  {Effect:'Allow',Action:'iot:Connect',Resource:sub('arn:${AWS::Partition}:iot:${AWS::Region}:${AWS::AccountId}:client/${DeviceId}'),Condition:{Bool:{'iot:Connection.Thing.IsAttached':'true'}}},
  {Effect:'Allow',Action:'iot:Publish',Resource:sub('arn:${AWS::Partition}:iot:${AWS::Region}:${AWS::AccountId}:topic/stillwatch/telemetry/${DeviceId}')}
]}},'Device-specific MQTT publish policy; certificate provisioning is a separate hardware integration step.',['Attach an active certificate to the registered thing; never share device private keys in source.']);
R.TelemetryRule=resource('AWS::IoT::TopicRule',{TopicRulePayload:{AwsIotSqlVersion:'2016-03-23',RuleDisabled:false,Sql:"SELECT *, 'telemetry' AS ingest, topic(3) AS deviceId FROM 'stillwatch/telemetry/+'",Actions:[{Lambda:{FunctionArn:get('Backend','Arn')}}]}},'Route authenticated MQTT telemetry and derive device identity from the topic.');
R.IotPermission=resource('AWS::Lambda::Permission',{Action:'lambda:InvokeFunction',FunctionName:ref('Backend'),Principal:'iot.amazonaws.com',SourceAccount:ref('AWS::AccountId'),SourceArn:sub('arn:${AWS::Partition}:iot:${AWS::Region}:${AWS::AccountId}:rule/${TelemetryRule}')},'Scope IoT invocation permission to this telemetry rule.');
R.Tick=resource('AWS::Events::Rule',{ScheduleExpression:'rate(1 minute)',State:'ENABLED',Targets:[{Id:'Backend',Arn:get('Backend','Arn')}]},'Evaluate missing-sensor deadlines and retry pending notifications when no telemetry arrives.',['Minute cadence adds up to roughly one minute scheduling latency; this is not a safety-certified real-time timer.']);
R.TickPermission=resource('AWS::Lambda::Permission',{Action:'lambda:InvokeFunction',FunctionName:ref('Backend'),Principal:'events.amazonaws.com',SourceAccount:ref('AWS::AccountId'),SourceArn:get('Tick','Arn')},'Allow only the health-check schedule to invoke the backend.');
R.DeadlineRole=resource('AWS::IAM::Role',{AssumeRolePolicyDocument:{Version:'2012-10-17',Statement:[{Effect:'Allow',Principal:{Service:'states.amazonaws.com'},Action:'sts:AssumeRole'}]},Policies:[{PolicyName:'DeadlineInvoke',PolicyDocument:{Version:'2012-10-17',Statement:[{Effect:'Allow',Action:'lambda:InvokeFunction',Resource:sub('arn:${AWS::Partition}:lambda:${AWS::Region}:${AWS::AccountId}:function:${AWS::StackName}-api')}]}}]},'Deadline workflow can invoke only this application backend.');
R.DeadlineFlow=resource('AWS::StepFunctions::StateMachine',{StateMachineType:'STANDARD',RoleArn:get('DeadlineRole','Arn'),Definition:{StartAt:'WaitForWorker',States:{
  WaitForWorker:{Type:'Wait',TimestampPath:'$.deadlineAt',Next:'WorkerDeadline'},
  WorkerDeadline:{Type:'Task',Resource:'arn:aws:states:::lambda:invoke',Parameters:{FunctionName:sub('arn:${AWS::Partition}:lambda:${AWS::Region}:${AWS::AccountId}:function:${AWS::StackName}-api'),'Payload.$':'$'},ResultPath:'$.workerResult',Next:'WaitForManager'},
  WaitForManager:{Type:'Wait',TimestampPath:'$.managerDeadlineAt',Next:'ManagerDeadline'},
  ManagerDeadline:{Type:'Task',Resource:'arn:aws:states:::lambda:invoke',Parameters:{FunctionName:sub('arn:${AWS::Partition}:lambda:${AWS::Region}:${AWS::AccountId}:function:${AWS::StackName}-api'),'Payload.$':'$'},ResultPath:'$.managerResult',End:true}
}}},'Persist worker deadline and the 60-second manager deadline independently of phone and sensor traffic.');
R.DeviceAuthRole=resource('AWS::IAM::Role',{AssumeRolePolicyDocument:{Version:'2012-10-17',Statement:[{Effect:'Allow',Principal:{Service:'lambda.amazonaws.com'},Action:'sts:AssumeRole'}]},Policies:[{PolicyName:'ReadOwnDeviceCredential',PolicyDocument:{Version:'2012-10-17',Statement:[{Effect:'Allow',Action:'secretsmanager:GetSecretValue',Resource:ref('DeviceSecretArn')}]}}]},'Device authenticator reads only the single registered device secret.',[],{Condition:'HasNative'});
R.DeviceAuthFunction=resource('AWS::Lambda::Function',{Runtime:'nodejs22.x',Handler:'index.handler',Role:get('DeviceAuthRole','Arn'),Timeout:15,MemorySize:128,Code:{ZipFile:await read('device-auth.cjs')},Environment:{Variables:{DEVICE_SECRET_ARN:ref('DeviceSecretArn'),ACCOUNT_ID:ref('AWS::AccountId')}}},'Authenticate ESPectre Native using its device credential over verified TLS.',[],{Condition:'HasNative'});
R.DeviceAuthorizer=resource('AWS::IoT::Authorizer',{AuthorizerFunctionArn:get('DeviceAuthFunction','Arn'),SigningDisabled:true,Status:'ACTIVE'},'Device password is checked by Lambda; allow only this native sensor topic.',[],{Condition:'HasNative'});
R.DeviceAuthPermission=resource('AWS::Lambda::Permission',{Action:'lambda:InvokeFunction',FunctionName:ref('DeviceAuthFunction'),Principal:'iot.amazonaws.com',SourceAccount:ref('AWS::AccountId'),SourceArn:get('DeviceAuthorizer','Arn')},'Only this custom IoT authorizer may invoke device authentication.',[],{Condition:'HasNative'});
R.NativeDomain=resource('AWS::IoT::DomainConfiguration',{DomainConfigurationName:sub('${AWS::StackName}-native'),ServiceType:'DATA',AuthenticationType:'CUSTOM_AUTH',ApplicationProtocol:'SECURE_MQTT',DomainConfigurationStatus:'ENABLED',AuthorizerConfig:{DefaultAuthorizerName:ref('DeviceAuthorizer'),AllowAuthorizerOverride:false}},'Dedicated AWS managed TLS endpoint for firmware without client X509 or ALPN.',[],{Condition:'HasNative'});
R.NativeRule=resource('AWS::IoT::TopicRule',{TopicRulePayload:{AwsIotSqlVersion:'2016-03-23',RuleDisabled:false,Sql:sub("SELECT *, 'native' AS ingest, topic(4) AS nativeDeviceId, topic(5) AS nativeResource, timestamp() AS brokerTime FROM 'espectre/v1/devices/${NativeDeviceId}/+'"),Actions:[{Lambda:{FunctionArn:get('Backend','Arn')}}]}},'Translate physical ESPectre health, readiness and motion into the project state.',[],{Condition:'HasNative'});
R.NativePermission=resource('AWS::Lambda::Permission',{Action:'lambda:InvokeFunction',FunctionName:ref('Backend'),Principal:'iot.amazonaws.com',SourceAccount:ref('AWS::AccountId'),SourceArn:sub('arn:${AWS::Partition}:iot:${AWS::Region}:${AWS::AccountId}:rule/${NativeRule}')},'Scope native telemetry invocation to this physical sensor rule.',[],{Condition:'HasNative'});
template.Outputs={
  ApiUrl:{Value:get('Api','ApiEndpoint')},CognitoDomain:{Value:sub('https://${LoginDomainPrefix}.auth.${AWS::Region}.amazoncognito.com')},CognitoClientId:{Value:ref('Client')},UserPoolId:{Value:ref('Users')},DevicePolicy:{Value:ref('DevicePolicy')},DeviceId:{Value:ref('DeviceId')},ZoneId:{Value:ref('ZoneId')},NativeDomainName:{Value:ref('NativeDomain'),Condition:'HasNative'},DeviceAuthorizerName:{Value:ref('DeviceAuthorizer'),Condition:'HasNative'}
};
const encoded=JSON.stringify(template).replace(/[^\x00-\x7F]/g,char=>'\\u'+char.charCodeAt(0).toString(16).padStart(4,'0'));
await fs.writeFile(new URL('template.json',import.meta.url),encoded);
await fs.writeFile(new URL('lambda-inline.cjs',import.meta.url),code);
console.log(`CloudFormation generated: ${Object.keys(R).length} resources, ${Buffer.byteLength(encoded)} bytes.`);
