// Verify the same authorized canonical text in Node; never JSON.stringify its parsed value.
const fs=require('fs'),crypto=require('crypto');
const m=JSON.parse(fs.readFileSync(0,'utf8'));let ok=false;
if(Object.keys(m).sort().join(',')==='algorithm,canonical_text,digest,encoding'&&m.algorithm==='SHA-256'&&m.encoding==='UTF-8'&&typeof m.canonical_text==='string'&&typeof m.digest==='string'){
 const actual=crypto.createHash('sha256').update(m.canonical_text,'utf8').digest('hex');ok=/^[a-f0-9]{64}$/.test(m.digest)&&crypto.timingSafeEqual(Buffer.from(actual),Buffer.from(m.digest));
}
process.stdout.write(JSON.stringify({verified:ok}));process.exitCode=ok?0:1;
