import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const main=readFileSync('app/src/main/java/my/warisan/pos/MainActivity.java','utf8');
const live=readFileSync('app/src/main/java/my/warisan/pos/LiveBranchStore.java','utf8');

// Staff access remains read-only and cannot be upgraded to inventory edits.
assert.match(main,/void drawStaffStockBalances\(\)/);
assert.match(main,/PEKERJA · Semak baki sahaja/);
assert.match(main,/if\(activePage==20\)\{\s*drawStaffStockBalances\(\)/);

// Owner Live stock gives paid skewer quantities next to remaining stock.
assert.match(main,/"TERJUAL HARI INI",hasSold\?/);
assert.match(main,/"BAKI STOK LIVE",live\.usable\(\)/);
assert.match(main,/JUMLAH JUALAN HARI INI/);
assert.match(main,/long\[\] soldUnits=live\.soldQuantities\(\)/);
assert.match(main,/SEMAK SEMULA REKOD JUALAN CLOUD/);
assert.match(main,/SUMBER: REKOD JUALAN LIVE TELEFON INI/);

// Counters aggregate the same branch's PAID orders; exclude pending/refunded.
assert.match(live,/whereEqualTo\("day",selected\)/);
assert.match(live,/addSnapshotListener\(MetadataChanges\.INCLUDE,\(results,error\)->/);
assert.match(live,/if\(!"PAID"\.equals\(order\.getString\("status"\)\)\)continue/);
assert.match(live,/dayPaidUnits=paid;dayPaidUnitsConfirmed=confirmed/);
assert.match(live,/!results\.getMetadata\(\)\.isFromCache\(\)/);
assert.match(live,/void attachDay\(\)/);
assert.match(live,/dayPaidUnits=new long\[0\];dayPaidUnitsConfirmed=false/);
assert.match(live,/\.get\(Source\.SERVER\)\.addOnSuccessListener/);
assert.match(live,/void refreshPaidOrders\(\)/);
assert.match(live,/sale\.optBoolean\("refunded",false\)/);
assert.match(live,/sale\.optString\("liveOrderId",""\)\.isEmpty\(\)/);
assert.match(live,/activeDay\.equals\(host\.entryDay\(sale\)\)/);
// Paid-unit reconciliation may NEVER alter available inventory: pending
// reservations already deducted it at confirmation time.
const reconciliation=live.slice(live.indexOf('private void acceptPaidOrders('),live.indexOf('boolean mergeRemoteOrder('));
assert.doesNotMatch(reconciliation,/tx\.(?:update|set)\(/);

console.log('PASS: Live stock reconciles server PAID orders, with labelled local fallback and no second stock deduction');
