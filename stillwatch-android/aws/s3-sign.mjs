import {createHash,createHmac} from 'node:crypto';
const hex=value=>createHash('sha256').update(value).digest('hex');
const mac=(key,value)=>createHmac('sha256',key).update(value).digest();
const encode=value=>encodeURIComponent(value).replace(/[!'()*]/g,c=>'%'+c.charCodeAt(0).toString(16).toUpperCase());
export function signObjectUrl({bucket,key,method='GET',mime,size,region,credentials,time=new Date()}) {
  if(!bucket||!key||!region||!credentials.accessKeyId||!credentials.secretAccessKey)throw new Error('Object signing configuration missing');
  const amz=time.toISOString().replace(/[:-]|\.\d{3}/g,''),date=amz.slice(0,8),scope=`${date}/${region}/s3/aws4_request`;
  const host=`${bucket}.s3.${region}.amazonaws.com`,path='/'+key.split('/').map(encode).join('/');
  const headers={host};if(method==='PUT'){headers['content-type']=mime;headers['content-length']=String(size);}
  const names=Object.keys(headers).sort(),signed=names.join(';');
  const query={'X-Amz-Algorithm':'AWS4-HMAC-SHA256','X-Amz-Content-Sha256':'UNSIGNED-PAYLOAD','X-Amz-Credential':`${credentials.accessKeyId}/${scope}`,'X-Amz-Date':amz,'X-Amz-Expires':'300','X-Amz-SignedHeaders':signed};
  if(credentials.sessionToken)query['X-Amz-Security-Token']=credentials.sessionToken;
  const canonicalQuery=Object.entries(query).map(([k,v])=>[encode(k),encode(v)]).sort(([a],[b])=>a.localeCompare(b)).map(([k,v])=>`${k}=${v}`).join('&');
  const request=[method,path,canonicalQuery,names.map(name=>`${name}:${headers[name]}\n`).join(''),signed,'UNSIGNED-PAYLOAD'].join('\n');
  const toSign=['AWS4-HMAC-SHA256',amz,scope,hex(request)].join('\n');
  const signing=mac(mac(mac(mac('AWS4'+credentials.secretAccessKey,date),region),'s3'),'aws4_request');
  const signature=createHmac('sha256',signing).update(toSign).digest('hex');
  return `https://${host}${path}?${canonicalQuery}&X-Amz-Signature=${signature}`;
}
