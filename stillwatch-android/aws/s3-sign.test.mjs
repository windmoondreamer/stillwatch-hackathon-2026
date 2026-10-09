import {test} from 'node:test';
import assert from 'node:assert/strict';
import {signObjectUrl} from './s3-sign.mjs';
// Fixed vectors independently verified with @aws-sdk/s3-request-presigner 3.1000.0.
// These are synthetic credentials, never a real AWS key.
const credentials={accessKeyId:'FAKEACCESSKEY',secretAccessKey:'fake-verification-secret',sessionToken:'fake/session+token='};
const vectors=[
 ['GET','room-01/test.png','d3d7a0bf2abcf087aa4c20e94cea406813f9844a1e76864589f0114a6253de08'],
 ['GET','room-01/한글 image.png','211ebb4c8a18d87d6c42167514161a0bfda36525638bd55a5b6ac2b1fa467626'],
 ['PUT','room-01/test.png','78a08d2f9215212e02fecead15dbeba1d59c00b4363fbb53f0dad191ed1cd405'],
 ['PUT','room-01/한글 image.png','720f4f25633623e963444e8a13541a8ab96f0e7d04654dee8d277c7b986f7a73']
];
for(const [method,key,signature] of vectors)test(`S3 ${method} SDK signature matches ${key}`,()=>{
 const url=new URL(signObjectUrl({bucket:'stillwatch-test-bucket',key,method,mime:'image/png',size:11133,region:'ap-southeast-2',credentials,time:new Date('2026-10-09T05:00:00Z')}));
 assert.equal(url.searchParams.get('X-Amz-Signature'),signature);
 assert.equal(url.searchParams.get('X-Amz-Expires'),'300');
 assert.equal(url.searchParams.get('X-Amz-Security-Token'),credentials.sessionToken);
});
