import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const branch = readFileSync('app/src/main/java/my/warisan/pos/BranchAccounts.java','utf8');
const main = readFileSync('app/src/main/java/my/warisan/pos/MainActivity.java','utf8');
const live = readFileSync('app/src/main/java/my/warisan/pos/LiveBranchStore.java','utf8');

const stockOverview = branch.slice(branch.indexOf('  void showStockBranches(){'),
  branch.indexOf('  void loadStockBranches(){'));

// The reference image is a dashboard, not a replacement for operational stock.
assert.match(stockOverview,/drawStockBranchCard\(id,name,stockPrimaryIds\.contains\(id\)\)/);
assert.match(stockOverview,/drawStockHeader\(\)/);
assert.match(stockOverview,/"WARISAN POS-1"/);
assert.match(stockOverview,/stockMetric\(stats,"◇","Item Aktif"/);
assert.match(stockOverview,/stockMetric\(stats,"▲","Stok Rendah"/);
assert.match(stockOverview,/stockMetric\(stats,"!","Rosak Hari Ini"/);
assert.match(stockOverview,/stockBar\(card,/);
assert.match(stockOverview,/stockGoldButton\(card,"☷  Lihat Stok   ›"/);
assert.match(stockOverview,/stockGoldButton\(host\.body,"▣  Restock Cawangan \(Admin Sahaja\)"/);
assert.match(stockOverview,/openArchivedBranchReport\(id\)/);

// Summary uses only a server read; no new writes or stock calculations are persisted.
assert.match(stockOverview,/document\("current"\)\.get\(Source\.SERVER\)/);
assert.doesNotMatch(stockOverview,/\.update\(|\.set\(|\.delete\(|transaction\.set\(/);
assert.match(branch,/void openStockBranch\(String id,String name\)/);
assert.match(main,/else if\(activePage==13\)/);
assert.match(main,/drawLiveBranchStock\(\)/);
assert.match(live,/void adjustStockDelta\(/);
assert.match(live,/void restockDialog\(/);

console.log('PASS: screenshot-style stock cards, live previews, archive and original stock operations');
