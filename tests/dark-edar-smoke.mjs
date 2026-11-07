import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
const main=readFileSync('app/src/main/java/my/warisan/pos/MainActivity.java','utf8');
const theme=readFileSync('app/src/main/java/my/warisan/pos/WarisanTheme.java','utf8');
const owner=readFileSync('app/src/main/java/my/warisan/pos/OwnerCashflow.java','utf8');
// Run the actual classifier and supplement bodies with JSON/preferences adapters.
// This is a source-level regression, not an Android device/runtime test.
let classifier=main.match(/int paymentKind\(JSONObject e\)\{([^\n]+)\}/)[1]
  .replace('String m=','let m=').replaceAll('.contains(','.includes(').replaceAll('Locale.US','undefined');
let supplement=main.split('long localEdarSupplement(String day,int kind,boolean count){')[1].split('  int sessionSales(')[0].trim();
supplement=supplement.slice(0,-1).replace('long total=0;JSONArray list=','let total=0;let list=')
  .replace('for(int i=','for(let i=').replace('JSONObject e=','let e=').replaceAll('list.length()','list.length');
const wrap=e=>e==null?null:{optString:(k,d='')=>String(e[k]??d),optBoolean:(k,d)=>e[k]??d,...e};
const records=[
  {source:'edar',method:'Edar',total:600,businessDay:'2026-11-08'},
  {source:'edar',method:'QR',total:200,businessDay:'2026-11-08'},
  {source:'edar',method:'Edar',total:300,businessDay:'2026-11-08',liveOrderId:'cloud-paid-1'},
  {source:'edar',method:'Edar',total:400,businessDay:'2026-11-08',refunded:true},
  {source:'edar',method:'Edar',total:500,businessDay:'2026-11-07',paymentDate:'2026-11-08'},
  {source:'pos',method:'Tunai',total:900,businessDay:'2026-11-08'}
];
const ctx=vm.createContext({entries:()=>Object.assign(records.map(wrap),{optJSONObject(i){return this[i];}}),
 entryDay:e=>e.businessDay,saleTotal:e=>e.total});
vm.runInContext(`String.prototype.equals=function(s){return String(this)===s;};
 String.prototype.isEmpty=function(){return this.length===0;};
 function paymentKind(e){${classifier}};
 function localEdarSupplement(day,kind,count){${supplement}};`,ctx);
for(const [method,kind] of [['Edar',1],['Tunai',1],['cash',1],['QR',2],['DuitNow',2],['Bank',2],['',0]]){
 ctx.e=wrap({method});assert.equal(vm.runInContext('paymentKind(e)',ctx),kind,method);
}
assert.equal(vm.runInContext('localEdarSupplement("2026-11-08",0,false)',ctx),800);
assert.equal(vm.runInContext('localEdarSupplement("2026-11-08",1,false)',ctx),600);
assert.equal(vm.runInContext('localEdarSupplement("2026-11-08",2,false)',ctx),200);
assert.equal(vm.runInContext('localEdarSupplement("2026-11-08",0,true)',ctx),2);
assert.equal(vm.runInContext('localEdarSupplement("2026-11-07",1,false)',ctx),500);
assert.match(main,/checkedCloudCents\(live.sales\(\)\+localEdarSupplement\(day,0,false\)\)/);
assert.match(main,/checkedCloudCents\(live.cash\(\)\+localEdarSupplement\(day,1,false\)\)/);
assert.match(main,/checkedCloudCents\(live.qr\(\)\+localEdarSupplement\(day,2,false\)\)/);
// EDAR's only publisher remains the local atomic paid ledger; Cloud order sales
// receive liveOrderId on both payment and mirror ingestion paths.
const pay=main.split('void payDistribution(int index)')[1].split('\n')[0];
assert.match(pay,/e.optBoolean\("paid",false\)/);
assert.match(pay,/Math.max\(0,e.optInt\("qty"\)-e.optInt\("returned"\)\)/);
assert.match(pay,/if\(sold<=0\)/);
assert.doesNotMatch(pay,/live\./);
assert.match(pay,/putString\("stock_sales"/);
assert.match(pay,/putString\("stock_distribution"/);
assert.match(main,/source.setVisibility\(View.GONE\)/);
assert.doesNotMatch(main,/action\("Pilih sumber menu/);
assert.match(main,/setStoreLogo\(logo\)/);
assert.match(main,/sharedPaymentQr.attach/);
assert.match(main,/live.attachDay/);
assert.match(main,/void closingHistory\(/);
assert.match(main,/TOTAL SALE · CAWANGAN INI/);
assert.match(owner,/salesSummary\(overallCard,"TOTAL SALE"/);
assert.match(theme,/dark_orange_v3466/);
assert.match(theme,/previous_theme/);
console.log('PASS: dark display and actual EDAR classifier/supplement source fixtures; cash/QR, refunds, mirror IDs and original dates');
