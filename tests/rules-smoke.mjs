import fs from 'node:fs';
import assert from 'node:assert/strict';
import { initializeTestEnvironment, assertSucceeds, assertFails } from '@firebase/rules-unit-testing';
import { doc, setDoc, updateDoc, getDoc, runTransaction, serverTimestamp } from 'firebase/firestore';

const projectId='demo-warisan-pos-live';
const today=new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Kuala_Lumpur',
  year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date()).replaceAll('/','-');

const env=await initializeTestEnvironment({
  projectId,firestore:{host:'127.0.0.1',port:8080,rules:fs.readFileSync('../firestore.rules','utf8')}
});
const owner=env.authenticatedContext('owner',{email:'owner@example.test',email_verified:true}).firestore();
const staff=env.authenticatedContext('staff',{email:'staff@example.test',email_verified:true}).firestore();
const outsider=env.authenticatedContext('outsider',{email:'other@example.test',email_verified:true}).firestore();
const workspace='workspaces/owner';
const branch=workspace+'/branches/branch1';
const inv=branch+'/live/current';
const ord=branch+'/liveOrders/order1';
const daily=branch+'/daily/'+today;
const lastYear=branch+'/daily/2025-01-01';
function d(database,path){return doc(database,path)}
const inventory={ownerUid:'owner',branchName:'Utama',
  stock:[10,10,10,10,0,10,10],menuPrices:[130,160,200,60,100,700,500],
  customMenu:'[]',menuVersion:'v1'};
try{
  await env.withSecurityRulesDisabled(async c=>{
    const db=c.firestore();
    await setDoc(d(db,workspace),{ownerUid:'owner',name:'Warisan'});
    await setDoc(d(db,branch),{ownerUid:'owner',name:'Utama'});
    await setDoc(d(db,branch+'/members/staff'),{
      uid:'staff',email:'staff@example.test',role:'STAFF',active:true
    });
    await setDoc(d(db,inv),inventory);
    await setDoc(d(db,lastYear),{sales:100,cash:100,qr:0,transactions:1});
  });
  await assertSucceeds(getDoc(d(owner,inv)));
  await assertSucceeds(getDoc(d(staff,inv)));
  await assertFails(getDoc(d(outsider,inv)));
  await assertSucceeds(getDoc(d(owner,lastYear)));
  await assertFails(getDoc(d(staff,lastYear)));
  await assertFails(updateDoc(d(staff,inv),{stock:[999,10,10,10,0,10,10]}));

  // A staff member cannot create arbitrary owner-controlled accounts.
  await assertFails(setDoc(d(staff,'staffProfiles/staff'),{
    uid:'staff',role:'OWNER',active:true,ownerUid:'staff',
    branchId:'branch1',email:'staff@example.test',branchName:'Utama'
  }));
  await assertSucceeds(setDoc(d(owner,'staffProfiles/newworker'),{
    uid:'newworker',role:'STAFF',active:true,ownerUid:'owner',
    branchId:'branch1',email:'worker@example.test',branchName:'Utama',createdAt:new Date()
  }));

  // Atomic cashier reservation: quantity 2 deducted from inventory.
  await assertSucceeds(runTransaction(staff,async tx=>{
    const before=await tx.get(d(staff,inv));
    assert.equal(before.data().stock[0],10);
    tx.update(d(staff,inv),{
      stock:[8,10,10,10,0,10,10],lastOrderId:'order1',
      lastStockAction:'RESERVE',updatedAt:serverTimestamp()
    });
    tx.set(d(staff,ord),{
      ownerUid:'owner',branchId:'branch1',actorUid:'staff',
      deviceOrderId:'order1',deviceId:'phone1',day:today,
      total:260,qty:[2,0,0,0,0,0,0],status:'PENDING',createdAt:serverTimestamp()
    });
  }));
  const remaining=await getDoc(d(owner,inv));
  assert.equal(remaining.data().stock[0],8);

  // Payment may only be linked to the approved reservation.
  await assertSucceeds(runTransaction(staff,async tx=>{
    const order=await tx.get(d(staff,ord));
    const report=await tx.get(d(staff,daily));
    assert.equal(report.exists(),false);
    assert.equal(order.data().status,'PENDING');
    tx.set(d(staff,daily),{
      sales:260,cash:260,qr:0,transactions:1,businessDay:today,
      lastPaymentId:'order1',updatedAt:serverTimestamp()
    });
    tx.update(d(staff,ord),{status:'PAID',method:'Tunai',paidAt:serverTimestamp()});
  }));
  assert.equal((await getDoc(d(owner,daily))).data().sales,260);
  await assertFails(updateDoc(d(staff,daily),{sales:999999}));
  await assertFails(updateDoc(d(staff,ord),{status:'REFUNDED'}));

  // Owner-only refund approvals, with an atomic reversal of the paid order.
  const reqPath=branch+'/refundRequests/request1';
  await assertSucceeds(setDoc(d(staff,reqPath),{
    ownerUid:'owner',branchId:'branch1',employeeUid:'staff',
    employeeEmail:'staff@example.test',businessDay:today,
    saleFingerprint:'order1|260',receipt:'Sate Ayam x 2',
    amount:260,status:'PENDING',requestedAt:serverTimestamp()
  }));
  await assertFails(updateDoc(d(staff,reqPath),{
    status:'APPROVED',decidedBy:'staff',decidedAt:serverTimestamp()
  }));
  await assertSucceeds(updateDoc(d(owner,reqPath),{
    status:'APPROVED',decidedBy:'owner',decidedAt:serverTimestamp()
  }));
  await assertSucceeds(updateDoc(d(staff,reqPath),{
    status:'CLAIMED',claimedAt:serverTimestamp()
  }));
  await assertSucceeds(runTransaction(staff,async tx=>{
    const receipt=await tx.get(d(staff,ord));
    const report=await tx.get(d(staff,daily));
    assert.equal(receipt.data().status,'PAID');
    assert.equal(report.data().sales,260);
    tx.update(d(staff,daily),{
      sales:0,cash:0,qr:0,lastRefundId:'order1',updatedAt:serverTimestamp()
    });
    tx.update(d(staff,ord),{
      status:'REFUNDED',refundApprovalId:'request1',
      refundBy:'staff',refundedAt:serverTimestamp()
    });
  }));
  assert.equal((await getDoc(d(owner,daily))).data().sales,0);
  await assertSucceeds(updateDoc(d(staff,reqPath),{
    status:'COMPLETED',completedAt:serverTimestamp()
  }));
  console.log('PASS: Owner/staff permissions, historical report privacy, atomic reserve/pay/refund, staff tampering denied.');
}finally{await env.cleanup();}
