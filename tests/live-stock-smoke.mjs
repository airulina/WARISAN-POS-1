import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const main=readFileSync('app/src/main/java/my/warisan/pos/MainActivity.java','utf8');
const live=readFileSync('app/src/main/java/my/warisan/pos/LiveBranchStore.java','utf8');

// Staff access remains read-only and cannot be upgraded to inventory edits.
assert.match(main,/void drawStaffStockBalances\(\)/);
assert.match(main,/PEKERJA · Semak baki sahaja/);
assert.match(main,/if\(activePage==20\)\{\s*drawStaffStockBalances\(\)/);

// Owner Live stock gives paid skewer quantities next to remaining stock.
assert.match(main,/"TERJUAL HARI INI",live\.soldQuantitiesReady\(\)/);
assert.match(main,/"BAKI STOK LIVE",live\.usable\(\)/);
assert.match(main,/JUMLAH JUALAN HARI INI/);
assert.match(main,/live\.soldTotalToday\(\)/);

// Counters aggregate the same branch's PAID orders; exclude pending/refunded.
assert.match(live,/whereEqualTo\("day",selected\)/);
assert.match(live,/if\(!"PAID"\.equals\(order\.getString\("status"\)\)\)continue/);
assert.match(live,/dayPaidUnits=paid;dayPaidUnitsConfirmed=confirmed/);
assert.match(live,/!results\.getMetadata\(\)\.isFromCache\(\)/);
assert.match(live,/void attachDay\(\)/);
assert.match(live,/dayPaidUnits=new long\[0\];dayPaidUnitsConfirmed=false/);

console.log('PASS: Live stock shows confirmed PAID quantities and remaining balance');
