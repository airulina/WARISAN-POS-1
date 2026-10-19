import fs from 'node:fs';
import assert from 'node:assert/strict';
import { initializeTestEnvironment, assertSucceeds, assertFails } from '@firebase/rules-unit-testing';
import { doc, setDoc, updateDoc, getDoc, runTransaction, serverTimestamp, writeBatch, deleteField } from 'firebase/firestore';

const projectId='demo-warisan-pos-live';
const today=new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Kuala_Lumpur',
  year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date()).replaceAll('/','-');

const env=await initializeTestEnvironment({
  projectId,firestore:{host:'127.0.0.1',port:8080,rules:fs.readFileSync('../firestore.rules','utf8')}
});
const owner=env.authenticatedContext('owner',{email:'owner@example.test',email_verified:true}).firestore();
const staff=env.authenticatedContext('staff',{email:'staff@example.test',email_verified:true}).firestore();
const outsider=env.authenticatedContext('outsider',{email:'other@example.test',email_verified:true}).firestore();
const staff2=env.authenticatedContext('staff2',{email:'staff2@example.test',email_verified:true}).firestore();
const staffGoogle=env.authenticatedContext('staff-google',{email:'staff@example.test',email_verified:true}).firestore();
const staffOtherBranch=env.authenticatedContext('staff-branch-two',{email:'branch-two@example.test',email_verified:true}).firestore();
const workspace='workspaces/owner';
const branch=workspace+'/branches/branch1';
const branchTwo=workspace+'/branches/jalan-kebun';
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
    await setDoc(d(db,branchTwo),{ownerUid:'owner',name:'Warisan Jalan Kebun'});
    await setDoc(d(db,branch+'/members/staff'),{
      uid:'staff',email:'staff@example.test',role:'STAFF',active:true
    });
    await setDoc(d(db,branch+'/members/staff2'),{
      uid:'staff2',email:'staff2@example.test',role:'STAFF',active:true
    });
    await setDoc(d(db,branchTwo+'/members/staff-branch-two'),{
      uid:'staff-branch-two',email:'branch-two@example.test',
      role:'STAFF',active:true
    });
    await setDoc(d(db,'staffProfiles/staff-branch-two'),{
      uid:'staff-branch-two',email:'branch-two@example.test',
      role:'STAFF',active:true,ownerUid:'owner',branchId:'jalan-kebun',
      branchName:'Warisan Jalan Kebun',createdAt:new Date()
    });
    await setDoc(d(db,'staffProfiles/staff'),{
      uid:'staff',email:'staff@example.test',role:'STAFF',active:true,
      ownerUid:'owner',branchId:'branch1',branchName:'Utama',createdAt:new Date()
    });
    await setDoc(d(db,'staffEmails/staff@example.test'),{
      email:'staff@example.test',role:'STAFF',workerUid:'staff',
      ownerUid:'owner',branchId:'branch1',removed:false,updatedAt:new Date()
    });
    await setDoc(d(db,inv),inventory);
    await setDoc(d(db,lastYear),{sales:100,cash:100,qr:0,transactions:1});
  });
  await assertSucceeds(getDoc(d(owner,inv)));
  await assertSucceeds(getDoc(d(staff,inv)));
  await assertFails(getDoc(d(outsider,inv)));

  // A single Owner-uploaded static payment QR is visible across all assigned
  // branches, but only the Owner can replace/delete it.
  const qrRef=d(owner,workspace+'/paymentConfig/mainQr');
  const workerQr=d(staff,workspace+'/paymentConfig/mainQr');
  const outsiderQr=d(outsider,workspace+'/paymentConfig/mainQr');
  await assertSucceeds(setDoc(qrRef,{
    contentType:'image/png',imageBase64:'aGVsbG8=',
    updatedAt:serverTimestamp()
  }));
  await assertSucceeds(getDoc(workerQr));
  await assertSucceeds(getDoc(d(staffOtherBranch,workspace+'/paymentConfig/mainQr')));
  await assertFails(getDoc(outsiderQr));
  await assertFails(setDoc(workerQr,{
    contentType:'image/png',imageBase64:'c3B5',
    updatedAt:serverTimestamp()
  }));
  await assertFails(setDoc(d(staffOtherBranch,workspace+'/paymentConfig/mainQr'),{
    contentType:'image/png',imageBase64:'c3B5',
    updatedAt:serverTimestamp()
  }));
  await assertFails(setDoc(d(staff,workspace+'/paymentConfig/other'),{
    contentType:'image/png',imageBase64:'c3B5',
    updatedAt:serverTimestamp()
  }));
  await assertSucceeds(setDoc(qrRef,{
    contentType:'image/png',imageBase64:'aGVsbG8y',
    updatedAt:serverTimestamp()
  }));
  await assertSucceeds(getDoc(workerQr));
  // Off Day is a server-wide staff ban, not a cosmetic Android login flag.
  // Owner can still read/modify the branch and see its existing records.
  await assertSucceeds(updateDoc(d(owner,branch),{
    operatingStatus:'OFFDAY',operatingReason:'OFF DAY',
    operatingUpdatedBy:'owner',operatingUpdatedAt:serverTimestamp()
  }));
  await assertSucceeds(getDoc(d(staff,branch))); // staff may see why login is blocked
  await assertFails(getDoc(d(staff,inv)));
  await assertFails(getDoc(workerQr)); // branch closed: staff QR access suspended
  await assertSucceeds(getDoc(d(staffOtherBranch,workspace+'/paymentConfig/mainQr')));
  // Another active branch keeps its QR access when Branch Utama is OFF DAY.
  await assertFails(getDoc(d(staff,branch+'/liveSessions/'+today)));
  await assertFails(updateDoc(d(staff,branch),{operatingStatus:'OPEN'}));
  await assertSucceeds(getDoc(d(owner,inv)));
  await assertSucceeds(updateDoc(d(owner,branch),{
    operatingStatus:'STOCK_OUT',operatingReason:'STOK HABIS',
    operatingUpdatedBy:'owner',operatingUpdatedAt:serverTimestamp()
  }));
  await assertFails(getDoc(d(staff,inv)));
  // Re-opening one branch restores permission without resetting its history.
  await assertSucceeds(updateDoc(d(owner,branch),{
    operatingStatus:'OPEN',operatingReason:'',
    operatingUpdatedBy:'owner',operatingUpdatedAt:serverTimestamp()
  }));
  await assertSucceeds(getDoc(d(staff,inv)));
  await assertSucceeds(getDoc(workerQr)); // QR readable again after reopening
  await assertSucceeds(getDoc(d(owner,lastYear)));
  await assertFails(getDoc(d(staff,lastYear)));
  await assertFails(updateDoc(d(staff,inv),{stock:[999,10,10,10,0,10,10]}));
  await assertFails(setDoc(d(staff,'workspaces/staff'),{
    ownerUid:'staff',name:'NOT AN OWNER'
  }));
  // Another login provider / UID with the SAME worker email must be rejected.
  await assertFails(setDoc(d(staffGoogle,'workspaces/staff-google'),{
    ownerUid:'staff-google',name:'NOT AN OWNER'
  }));

  // A staff member cannot create arbitrary owner-controlled accounts.
  await assertFails(setDoc(d(staff,'staffProfiles/staff'),{
    uid:'staff',role:'OWNER',active:true,ownerUid:'staff',
    branchId:'branch1',email:'staff@example.test',branchName:'Utama'
  }));
  await assertSucceeds(setDoc(d(owner,'staffProfiles/newworker'),{
    uid:'newworker',role:'STAFF',active:true,ownerUid:'owner',
    branchId:'branch1',email:'worker@example.test',branchName:'Utama',createdAt:new Date()
  }));
  // The email lock can only be created by the authenticated Owner after
  // a matching staff profile exists (protecting cross-provider sign-in).
  await assertSucceeds(setDoc(d(owner,'staffEmails/worker@example.test'),{
    email:'worker@example.test',role:'STAFF',workerUid:'newworker',
    ownerUid:'owner',branchId:'branch1',removed:false,updatedAt:serverTimestamp()
  }));
  await assertFails(setDoc(d(staff,'staffEmails/fake@example.test'),{
    email:'fake@example.test',role:'STAFF',workerUid:'fake',
    ownerUid:'staff',branchId:'branch1',removed:false,updatedAt:serverTimestamp()
  }));

  // First worker login binds one device; cannot silently change it afterward.
  const workerProfile=d(staff,'staffProfiles/staff');
  const primaryDevice='employee-installation-unique-00000001';
  await assertSucceeds(updateDoc(workerProfile,{
    deviceId:primaryDevice,deviceBoundAt:serverTimestamp()
  }));
  await assertFails(updateDoc(workerProfile,{
    deviceId:'shared-phone-new-installation-00002'
  }));
  // Owner is authorized to release a worker device after checking identity.
  await assertSucceeds(updateDoc(d(owner,'staffProfiles/staff'),{
    deviceId:deleteField(),deviceBoundAt:deleteField()
  }));
  await assertSucceeds(updateDoc(workerProfile,{
    deviceId:primaryDevice,deviceBoundAt:serverTimestamp()
  }));
  await assertSucceeds(setDoc(d(staff,branch+'/staffLogins/login1'),{
    ownerUid:'owner',branchId:'branch1',employeeUid:'staff',
    email:'staff@example.test',deviceTag:'000001',createdAt:serverTimestamp()
  }));
  await assertFails(setDoc(d(staff,branch+'/staffLogins/impersonation'),{
    ownerUid:'owner',branchId:'branch1',employeeUid:'owner',
    email:'owner@example.test',deviceTag:'fake',createdAt:serverTimestamp()
  }));
  // A closed branch cannot OPEN a shift. GPS and the legacy
  // openingEnabled toggle are no longer part of this contract.
  await assertSucceeds(updateDoc(d(owner,branch),{
    operatingStatus:'OFFDAY',operatingReason:'OFF DAY'
  }));
  await assertFails(setDoc(d(staff,branch+'/liveSessions/'+today),{
    ownerUid:'owner',branchId:'branch1',day:today,
    openedBy:'staff',openedByEmail:'staff@example.test',
    opening:0,open:true,updatedAt:serverTimestamp()
  }));
  await assertSucceeds(updateDoc(d(owner,branch),{
    operatingStatus:'OPEN',operatingReason:''
  }));
  await assertSucceeds(setDoc(d(staff,branch+'/liveSessions/'+today),{
    ownerUid:'owner',branchId:'branch1',day:today,
    openedBy:'staff',openedByEmail:'staff@example.test',
    opening:0,open:true,updatedAt:serverTimestamp()
  }));
  // A colleague with verified access may CLOSE, but may never forge the opener
  // or close under another worker's identity.
  await assertFails(updateDoc(d(staff2,branch+'/liveSessions/'+today),{
    open:false,closing:0,closedBy:'staff',closedByEmail:'staff@example.test',
    updatedAt:serverTimestamp()
  }));
  await assertSucceeds(updateDoc(d(staff2,branch+'/liveSessions/'+today),{
    open:false,closing:0,closedBy:'staff2',closedByEmail:'staff2@example.test',
    updatedAt:serverTimestamp()
  }));
  await assertFails(updateDoc(d(staff2,branch+'/liveSessions/'+today),{
    open:true,openedBy:'staff2',openedByEmail:'staff2@example.test',
    updatedAt:serverTimestamp()
  }));
  await assertSucceeds(setDoc(d(staff,branch+'/loginChecks/outside'),{
    ownerUid:'owner',branchId:'branch1',employeeUid:'staff',
    email:'staff@example.test',day:today,status:'OUTSIDE_BRANCH',
    distanceMeters:4100,createdAt:serverTimestamp()
  }));
  await assertFails(setDoc(d(staff,branch+'/loginChecks/forge'),{
    ownerUid:'owner',branchId:'branch1',employeeUid:'owner',
    email:'owner@example.test',day:today,status:'OUTSIDE_BRANCH',
    distanceMeters:30,createdAt:serverTimestamp()
  }));
  await assertSucceeds(setDoc(d(staff,branch+'/openingChecks/nearby'),{
    ownerUid:'owner',branchId:'branch1',employeeUid:'staff',
    day:today,status:'VERIFIED',distanceMeters:52,createdAt:serverTimestamp()
  }));
  await assertFails(setDoc(d(staff,branch+'/openingChecks/fake-identity'),{
    ownerUid:'owner',branchId:'branch1',employeeUid:'owner',
    day:today,status:'OUTSIDE_BRANCH',distanceMeters:1800,createdAt:serverTimestamp()
  }));

  // A NEW UUID is absent. Worker must be allowed to check nonexistence,
  // but cannot inspect another employee's existing receipt.
  const missingOrder=await assertSucceeds(getDoc(d(staff,ord)));
  assert.equal(missingOrder.exists(),false);
  await assertFails(getDoc(d(outsider,ord)));
  // Atomic cashier reservation: quantity 2 deducted from inventory.
  // Mirror production Android: read inventory only, then update inventory
  // and CREATE a fresh pending-order UUID in the same transaction.
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
  await assertFails(getDoc(d(staff2,ord))); // employee cannot inspect colleague's order

  // Unlimited product index 4: an actual WARISAN POS checkout leaves stock
  // unchanged, but still creates the linked pending order atomically.
  const kuahOrder=branch+'/liveOrders/kuah-only';
  await assertSucceeds(runTransaction(staff,async tx=>{
    const before=await tx.get(d(staff,inv));
    tx.update(d(staff,inv),{
      stock:before.data().stock,lastOrderId:'kuah-only',
      lastStockAction:'RESERVE',updatedAt:serverTimestamp()
    });
    tx.set(d(staff,kuahOrder),{
      ownerUid:'owner',branchId:'branch1',actorUid:'staff',
      deviceOrderId:'kuah-only',deviceId:'phone1',day:today,
      total:100,receiptNo:2,qty:[0,0,0,0,1,0,0],
      status:'PENDING',createdAt:serverTimestamp()
    });
  }));
  assert.equal((await getDoc(d(owner,inv))).data().stock[0],8);

  // Staff can read quantities but cannot edit a branch's absolute balance.
  await assertFails(setDoc(d(staff,branch+'/stockAdjustments/staff-fake'),{
    ownerUid:'owner',branchId:'branch1',actorUid:'staff',
    item:0,name:'Sate Ayam',before:8,after:999,difference:991,
    type:'OWNER_CORRECTION',createdAt:serverTimestamp()
  }));
  // Owner edits an existing Live branch balance with an audit entry.
  await assertSucceeds(runTransaction(owner,async tx=>{
    const snap=await tx.get(d(owner,inv));
    assert.equal(snap.data().stock[0],8);
    tx.update(d(owner,inv),{
      stock:[9,10,10,10,0,10,10],updatedAt:serverTimestamp()
    });
    tx.set(d(owner,branch+'/stockAdjustments/owner-correction'),{
      ownerUid:'owner',branchId:'branch1',actorUid:'owner',
      item:0,name:'Sate Ayam',before:8,after:9,difference:1,
      type:'OWNER_CORRECTION',createdAt:serverTimestamp()
    });
  }));
  assert.equal((await getDoc(d(staff,inv))).data().stock[0],9);
  await assertFails(updateDoc(d(staff,branch+'/stockAdjustments/owner-correction'),{
    after:1000
  }));

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
  // Blocking temporarily doesn't release the email for Owner signup.
  const membership=d(owner,branch+'/members/staff');
  const profile=d(owner,'staffProfiles/staff');
  const tempBlock=writeBatch(owner);
  tempBlock.update(membership,{active:false,removed:false,updatedAt:serverTimestamp()});
  tempBlock.update(profile,{active:false,removed:false,updatedAt:serverTimestamp()});
  await assertSucceeds(tempBlock.commit());
  await assertFails(setDoc(d(staffGoogle,'workspaces/staff-google'),{
    ownerUid:'staff-google',name:'STILL STAFF'
  }));
  // Owner removes a former employee. Archive credentials/access but retain sales history.
  const revocation=writeBatch(owner);
  revocation.update(membership,{
    active:false,removed:true,removedAt:serverTimestamp(),updatedAt:serverTimestamp()
  });
  revocation.update(profile,{
    active:false,removed:true,removedAt:serverTimestamp(),updatedAt:serverTimestamp()
  });
  revocation.update(d(owner,'staffEmails/staff@example.test'),{
    removed:true,updatedAt:serverTimestamp()
  });
  await assertSucceeds(revocation.commit());
  await assertFails(getDoc(d(staff,inv)));
  await assertFails(getDoc(d(staff,daily)));
  await assertFails(updateDoc(d(staff,reqPath),{status:'DENIED'}));
  await assertSucceeds(getDoc(profile));
  await assertSucceeds(getDoc(d(owner,daily))); // historical sales remain
  await assertFails(updateDoc(d(staff,'staffProfiles/staff'),{active:true,removed:false}));
  // After formal removal, this email can create a brand-new Owner workspace.
  await assertSucceeds(setDoc(d(staffGoogle,'workspaces/staff-google'),{
    ownerUid:'staff-google',name:'New business after worker left'
  }));
  const restore=writeBatch(owner);
  restore.update(membership,{
    active:true,removed:false,removedAt:deleteField(),updatedAt:serverTimestamp()
  });
  restore.update(profile,{
    active:true,removed:false,removedAt:deleteField(),updatedAt:serverTimestamp()
  });
  restore.update(d(owner,'staffEmails/staff@example.test'),{
    removed:false,updatedAt:serverTimestamp()
  });
  await assertSucceeds(restore.commit());
  await assertSucceeds(getDoc(d(staff,inv)));

  // Real upgraded shops may retain an extra Cloud stock slot for an old
  // hidden product while the worker app has only seven menu slots.
  // Rule-enforced equality rejects the unpadded receipt; padding zero safely
  // keeps old stock while permitting an otherwise valid Live transaction.
  const current=await getDoc(d(owner,inv));
  const legacyStock=[...current.data().stock,4];
  await assertSucceeds(updateDoc(d(owner,inv),{stock:legacyStock}));
  const wrongPath=branch+'/liveOrders/old-slot-unpadded';
  await assertFails(runTransaction(staff,async tx=>{
    const stock=await tx.get(d(staff,inv));
    tx.update(d(staff,inv),{
      stock:stock.data().stock,lastOrderId:'old-slot-unpadded',
      lastStockAction:'RESERVE',updatedAt:serverTimestamp()
    });
    tx.set(d(staff,wrongPath),{
      ownerUid:'owner',branchId:'branch1',actorUid:'staff',
      deviceOrderId:'old-slot-unpadded',deviceId:'phone1',day:today,
      total:100,qty:[0,0,0,0,1,0,0],
      status:'PENDING',createdAt:serverTimestamp()
    });
  }));
  assert.equal((await getDoc(d(owner,inv))).data().stock.length,8);
  const paddedPath=branch+'/liveOrders/old-slot-padded';
  await assertSucceeds(runTransaction(staff,async tx=>{
    const stock=await tx.get(d(staff,inv));
    tx.update(d(staff,inv),{
      stock:stock.data().stock,lastOrderId:'old-slot-padded',
      lastStockAction:'RESERVE',updatedAt:serverTimestamp()
    });
    tx.set(d(staff,paddedPath),{
      ownerUid:'owner',branchId:'branch1',actorUid:'staff',
      deviceOrderId:'old-slot-padded',deviceId:'phone1',day:today,
      total:100,qty:[0,0,0,0,1,0,0,0],
      status:'PENDING',createdAt:serverTimestamp()
    });
  }));
  assert.equal((await getDoc(d(owner,inv))).data().stock.length,8);
  console.log('PASS: Owner/staff access, atomic sale/refund, legacy stock slots, staff permissions, preserve history.');
}finally{await env.cleanup();}
