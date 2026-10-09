import {applyTelemetry,evaluate,DomainError} from './domain.mjs';
export function applyNative(zone,input,time,expectedId){
 if(!expectedId||input.nativeDeviceId!==expectedId)throw new DomainError(403,'등록된 ESP32가 아닙니다.');
 const broker=input.brokerTime;
 if(!Number.isFinite(broker)||broker>time+5000||time-broker>30000)throw new DomainError(422,'오래된 ESP32 메시지입니다.');
 const kind=input.nativeResource;
 if(!['health','sensing','motion'].includes(kind))return zone;
 zone.nativeSensor||={deviceId:expectedId,ready:false,online:false,bootId:null,lastMono:-1};const sensor=zone.nativeSensor;
 sensor.resourceTimes||={};
 if(broker<=(sensor.resourceTimes[kind]??-1))return zone;
 sensor.resourceTimes[kind]=broker;
 if(kind==='health'){
  sensor.online=input.online===true&&input.status==='ok';
  if(!sensor.online){sensor.ready=false;zone.lastValidAt=null;return evaluate(zone,time);}
  if(Number.isInteger(input.timestamp_ms)){
   if(Number.isInteger(sensor.lastHealthMono)&&input.timestamp_ms<sensor.lastHealthMono){sensor.bootId='native-'+Math.round(broker-input.timestamp_ms);sensor.lastMono=-1;sensor.ready=false;zone.lastValidAt=null;}
   sensor.lastHealthMono=input.timestamp_ms;
  }
  if(!sensor.bootId){sensor.bootId='native-'+Math.round(broker-(input.uptime_s||0)*1000);}
  return zone;
 }
 if(kind==='sensing'){
  sensor.ready=input.enabled===true&&input.ready===true&&input.mode==='sensing'&&input.derived_events_paused===false;
  if(!sensor.ready){zone.lastValidAt=null;return evaluate(zone,time);}return zone;
 }
 if(kind!=='motion')return zone;
 if(!Number.isInteger(input.timestamp_ms)||input.timestamp_ms<0||!['idle','motion'].includes(input.state)||!Number.isFinite(input.score)||input.score<0||input.score>1)throw new DomainError(422,'ESP32 움직임 메시지 형식 오류');
 // Late motion deliveries are not evidence of a device reboot. Health establishes a new boot.
 if(input.timestamp_ms<=sensor.lastMono)return zone;
 sensor.lastMono=input.timestamp_ms;sensor.online=true;
 const valid=sensor.ready;
 if(valid&&zone.nativeSampleAt&&broker-zone.nativeSampleAt<1500&&!(input.state==='motion'&&broker-Date.parse(zone.lastMotionAt||0)>1500))return zone;
 zone.nativeSampleAt=broker;zone.telemetrySource='ESPectre native MQTT';
 return applyTelemetry(zone,{deviceId:zone.deviceId,capturedAt:new Date(broker).toISOString(),seq:input.timestamp_ms,bootId:sensor.bootId||'native-initial',motion:input.state==='motion',motionScore:input.score,valid},time);
}
