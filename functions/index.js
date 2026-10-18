"use strict";
const {onCall, HttpsError} = require("firebase-functions/v2/https");
const admin = require("firebase-admin");
admin.initializeApp();
const store=admin.firestore();
const REGION="asia-southeast1";

exports.createStaffAccount=onCall({region:REGION,enforceAppCheck:false},async (request)=>{
  if(!request.auth)throw new HttpsError("unauthenticated","Log masuk Owner dahulu.");
  const ownerUid=request.auth.uid;
  const args=request.data||{};
  const branchId=String(args.branchId||"").trim();
  const email=String(args.email||"").trim().toLowerCase();
  const password=String(args.password||"");
  if(!/^[a-zA-Z0-9_-]{1,130}$/.test(branchId))
    throw new HttpsError("invalid-argument","Branch tidak sah.");
  if(email.length>254||!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email))
    throw new HttpsError("invalid-argument","Email tidak sah.");
  if(password.length<10||password.length>128)
    throw new HttpsError("invalid-argument","Password minimum 10, maksimum 128 aksara.");
  const workspace=store.collection("workspaces").doc(ownerUid);
  const ownerDoc=await workspace.get();
  if(!ownerDoc.exists||ownerDoc.get("ownerUid")!==ownerUid)
    throw new HttpsError("permission-denied","Hanya Owner yang berdaftar boleh daftar pekerja.");
  const branch=workspace.collection("branches").doc(branchId);
  const branchDoc=await branch.get();
  if(!branchDoc.exists||branchDoc.get("ownerUid")!==ownerUid)
    throw new HttpsError("permission-denied","Branch bukan milik Owner.");
  const ownUser=await admin.auth().getUser(ownerUid);
  if((ownUser.email||"").toLowerCase()===email)
    throw new HttpsError("invalid-argument","Email Owner tidak boleh dijadikan akaun pekerja.");
  // Never modify a user's existing Google/Owner credentials.
  try{
    await admin.auth().getUserByEmail(email);
    throw new HttpsError("already-exists","Email sudah didaftarkan. Gunakan akaun pekerja baharu. Jangan reset akaun Google sedia ada.");
  }catch(error){
    if(error instanceof HttpsError)throw error;
    if(error.code!=="auth/user-not-found")throw new HttpsError("internal","Gagal semak email.");
  }
  const u=await admin.auth().createUser({
    email,password,disabled:false,emailVerified:false,
    displayName:"WARISAN POS Staff"
  });
  const profile=store.collection("staffProfiles").doc(u.uid);
  const membership=branch.collection("members").doc(u.uid);
  const details={
    uid:u.uid,role:"STAFF",email,ownerUid,branchId,
    branchName:String(branchDoc.get("name")||"Branch"),active:true,
    createdAt:admin.firestore.FieldValue.serverTimestamp()
  };
  try {
    const batch=store.batch();
    batch.create(profile,details);
    batch.create(membership,{
      uid:u.uid,email,role:"STAFF",active:true,
      createdAt:admin.firestore.FieldValue.serverTimestamp()
    });
    await batch.commit();
  }catch(e){
    // Avoid orphaning a partially provisioned user.
    await admin.auth().deleteUser(u.uid).catch(()=>{});
    throw new HttpsError("internal","Akaun pekerja gagal direkod.");
  }
  // Never return the password or log request.data.
  return {ok:true,uid:u.uid,branchId,branchName:details.branchName};
});
