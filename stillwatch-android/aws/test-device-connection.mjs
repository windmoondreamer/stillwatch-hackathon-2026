import fs from 'node:fs';
import mqtt from '../../stillwatch-voice/node_modules/mqtt/build/mqtt.js';
const privateDir=new URL('../.private/',import.meta.url);
const config=JSON.parse(fs.readFileSync(new URL('deployment.json',privateDir)));
const secret=JSON.parse(fs.readFileSync(new URL('device-secret.json',privateDir)));
const client=mqtt.connect('mqtts://'+config.nativeEndpoint+':443',{username:secret.username,password:secret.password,clientId:'stillwatch-connection-check',reconnectPeriod:0,connectTimeout:15000});
const timer=setTimeout(()=>{console.log('Device credential connection timed out.');client.end(true);process.exitCode=1;},20000);
client.on('connect',()=>{console.log('AWS managed device endpoint accepted verified TLS and device credential.');clearTimeout(timer);client.end(true);});
client.on('error',error=>{console.log('Connection failed:',error.code||error.name);clearTimeout(timer);client.end(true);process.exitCode=1;});
