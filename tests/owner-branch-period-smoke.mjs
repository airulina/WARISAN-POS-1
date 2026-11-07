import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const root = 'app/src/main/java/my/warisan/pos/';
const main = readFileSync(root + 'MainActivity.java', 'utf8');
const cash = readFileSync(root + 'OwnerCashflow.java', 'utf8');
const products = readFileSync(root + 'ProductSalesChart.java', 'utf8');
const chart = readFileSync(root + 'OwnerSalesChart.java', 'utf8');
const pdf = readFileSync(root + 'OwnerReportPdf.java', 'utf8');

// Do not multiply chart height by display density twice; use the established
// MainActivity.add() layout convention and a compact horizontal seven-day plot.
assert.match(main, /new ProductSalesChart\(this,day\),-1,196\)/);
assert.doesNotMatch(main, /new ProductSalesChart\(this,day\),-1,dp\(/);
assert.match(products, /float rowHeight=\(bottom-top\)\/DAYS/);
assert.match(products, /new RectF\(left,barY,left\+length,barY\+barHeight\)/);
assert.doesNotMatch(products, /new RectF\(x,bottom-tall/);

// Branch card opens the report immediately (screen two), without a third
// TOTAL SALE page or a custom Back button on the report itself.
assert.match(cash, /void drawBranchDetail\(\)\s*\{[^}]*drawUnifiedReport\(\)/);
const unified = cash.slice(cash.indexOf('  void drawUnifiedReport(){'), cash.indexOf('  String reportText('));
assert.doesNotMatch(unified, /KEMBALI KE/);
assert.match(main, /@Override public void onBackPressed\(\)/);
assert.match(main, /activePage==17\|\|activePage==19/);
assert.match(main, /activePage=3;draw\(\);return;/);

// Daily, Monday-Sunday weekly, and monthly are controls in the same branch
// report, with its own date selector; no extra Activity/window is created.
assert.match(cash, /private int detailPeriod=0/);
assert.match(cash, /"HARIAN","MINGGUAN","BULANAN"/);
assert.match(cash, /host\.pickDay\(/);
assert.match(cash, /\(week\.get\(Calendar\.DAY_OF_WEEK\)\+5\)%7/);
assert.match(cash, /private String\[\] detailRange\(\)/);
assert.match(cash, /drawDetailPeriodControls\(\)/);

// Opening, Closing, and the historical closing-expense ledger stay available
// alongside the period control, even while Firestore sales are loading.
assert.match(cash, /private void drawBranchSessionControls\(BranchRow chosen\)/);
assert.match(cash, /host\.drawPosSession\(\)/);
assert.match(cash, /host\.live\.showOwnerForcedClosing\(\)/);
assert.match(cash, /showCloudClosingHistory\(chosen\.id\)/);
assert.ok(unified.indexOf('drawBranchSessionControls(chosen)') < unified.indexOf('if(!loadError.isEmpty()||!ready())'));

// Exact selected interval must reach sales/chart, local costs, QR receipts,
// and the PDF export; PDF must filter source dates before summing totals.
assert.match(cash, /new OwnerSalesChart\(host,chosen\.combined\(\),start\(\),end\(\)\)/);
assert.match(cash, /localExpenseInPeriod\(first,last,false\)/);
assert.match(cash, /showQrHistory\(chosen,start\(\),end\(\)\)/);
assert.match(cash, /OwnerReportPdf\.print\(host,chosen,period\(\),start\(\),end\(\),/);
assert.match(chart, /entry\.getKey\(\)\.compareTo\(from\)<0\|\|entry\.getKey\(\)\.compareTo\(to\)>0/);
assert.match(pdf, /if\(entry\.getKey\(\)\.compareTo\(from\)<0\|\|entry\.getKey\(\)\.compareTo\(to\)>0\)continue/);
assert.match(pdf, /total\.add\(entry\.getValue\(\)\)/);
assert.match(pdf, /sale=host\.saleTotalBetween\(from,to\)/);

console.log('PASS: compact horizontal graph, one-tap branch report, period/date, native Back and sessions');
