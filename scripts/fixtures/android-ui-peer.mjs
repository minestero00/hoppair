// Loopback-only sample shelf for the opt-in Android UI gate. Contains no device credentials.
import net from 'node:net';
import crypto from 'node:crypto';
import {readFileSync} from 'node:fs';
const key=Buffer.alloc(32,7), files=new Map(), deleted=new Set();
const item=(kind,title,extra={})=>({id:crypto.randomUUID(),kind,title,size:0,source:'Mac',pinned:false,createdAt:Date.now()/1000,...extra});
const file=item('file','Project brief.txt',{filename:'Project brief.txt',mime:'text/plain',size:24});
files.set(file.id,Buffer.from('A small preview document'));
const imageBytes=readFileSync(new URL('./preview.png',import.meta.url));
const photo=item('file','Coast study.png',{filename:'Coast study.png',mime:'image/png',size:imageBytes.length});
files.set(photo.id,imageBytes);
const items=[
 item('text','A few things for tomorrow',{text:'A few things for tomorrow\nPick up the design where you left off. Keep it simple, keep it useful.',pinned:true}),
 photo,
 file,
 item('link','https://developer.android.com/design',{text:'https://developer.android.com/design'}),
 item('text','Coffee, then a little planning.',{text:'Coffee, then a little planning.'})
];
net.createServer(socket=>{
 let buffered=Buffer.alloc(0);
 socket.on('data',chunk=>{
  buffered=Buffer.concat([buffered,chunk]);if(buffered.length<4)return;
  const length=buffered.readUInt32BE(0);if(buffered.length<length+4)return;
  try{
   const frame=buffered.subarray(4,4+length), decipher=crypto.createDecipheriv('aes-256-gcm',key,frame.subarray(0,12));
   decipher.setAuthTag(frame.subarray(-16));
   const req=JSON.parse(Buffer.concat([decipher.update(frame.subarray(12,-16)),decipher.final()]));
   let result={ok:true,requestId:req.requestId};
   if(req.action==='list'||req.action==='listPage')result={...result,items,cameraRequest:null,deviceName:"Studio Mac",syncAcknowledgement:true,supportsDeletion:true};
   if(req.action==='syncAck') {
    if(typeof req.deviceName !== 'string' || !req.deviceName.trim() || [...req.deviceName].length > 64) throw new Error('Invalid device name');
    result.deviceName='Studio Mac';
   }
   if(req.action==='deleted')result.deleted=(req.ids??[]).filter(id=>deleted.has(id));
   if(req.action==='delete'){deleted.add(req.id);const index=items.findIndex(item=>item.id===req.id);if(index>=0)items.splice(index,1);files.delete(req.id);}
   if(req.action==='get')result.data=files.get(req.id)?.toString('base64');
   if(req.action==='put'&&deleted.has(req.item.id))result={...result,ok:false,error:'Item was deleted'};
   else if(req.action==='put'&&!items.some(i=>i.id===req.item.id)){items.unshift(req.item);if(req.data)files.set(req.item.id,Buffer.from(req.data,'base64'));}
   const iv=crypto.randomBytes(12),cipher=crypto.createCipheriv('aes-256-gcm',key,iv);
   const response=Buffer.concat([iv,cipher.update(JSON.stringify(result)),cipher.final(),cipher.getAuthTag()]);
   const header=Buffer.alloc(4);header.writeUInt32BE(response.length);socket.end(Buffer.concat([header,response]));
  }catch{socket.destroy();}
 });
}).listen(49273,'127.0.0.1',()=>console.log('Isolated UI fixture peer ready on loopback.'));
