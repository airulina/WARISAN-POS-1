import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const main = readFileSync('app/src/main/java/my/warisan/pos/MainActivity.java', 'utf8');
const owner = readFileSync('app/src/main/java/my/warisan/pos/OwnerCashflow.java', 'utf8');
const branches = readFileSync('app/src/main/java/my/warisan/pos/BranchAccounts.java', 'utf8');
const gradle = readFileSync('app/build.gradle', 'utf8');

// The Owner v3.46 six-tab navigation is an explicit requirement. Only
// visible labels have been refreshed; destinations and access stay intact.
assert.match(main, /new String\[\]\{"MENU","ORDER","STOCK","SALE","BRANCH","SETTING"\}/);
assert.match(main, /int\[\] pages=worker\?new int\[\]\{0,11,12,5\}:new int\[\]\{0,11,1,3,15,4\}/);
assert.match(main, /new String\[\]\{"MENU","ORDER","SALE","SETTING"\}/);
assert.match(main, /if\(activePage!=0\)\{drawPage\(\);addNavigation\(screen\);return;\}/);
assert.match(main, /else if\(activePage==3&&branchAccounts\.owner\(\)\)\{\s*ownerCashflow\.drawOverview\(\)/);
assert.match(main, /else if\(activePage==1\)\{\s*if\(branchAccounts\.owner\(\)\)branchAccounts\.showStockBranches\(\)/);

// Original v3.46 operational controls must remain reachable, including a
// zero-value Opening, staff Closing, Owner override, stock and pending orders.
assert.match(main, /void drawPosSession\(\)/);
assert.match(main, /action\("CLOSING POS · "\+day,\(\)->requireClosingExpenses\(\)\)/);
assert.match(main, /action\("BUKA POS \/ OPENING BALANCE",\(\)->openPosSession\(\)\)/);
assert.match(main, /live\.showOwnerForcedClosing\(\)/);
assert.match(main, /drawPendingOrders\(\)/);
assert.match(branches, /void showStockBranches\(\)/);
assert.match(branches, /void openStockBranch\(String id,String name\)/);

// All shown branches are active. Closed locations survive in the existing
// archive/monthly report, not in the Owner's live overview.
assert.match(owner, /if\(b\.archived\)continue;/);
assert.match(owner, /new OwnerSalesChart\(host,b\.combined\(\),host\.selectedMonth\)/);
assert.match(owner, /details\.setOnClickListener\(v->\{/);
assert.match(owner, /overallProfitVerified\?rm\(verifiedProfit\):"—"/);
assert.match(owner, /canProfit\?rm\(branchProfit\):"—"/);

// Android may have a v3.47 preview installed. The build must update it
// without changing the v3.46 functional baseline or the package identity.
assert.match(gradle, /applicationId 'my\.warisan\.pos'/);
assert.match(gradle, /versionCode 53/);
assert.match(gradle, /versionName '3\.46\.2'/);

console.log('PASS: v3.46 Owner/Staff tabs, all critical access routes and UI-only branch display');
