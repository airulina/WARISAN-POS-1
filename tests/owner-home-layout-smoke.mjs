import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const main=readFileSync('app/src/main/java/my/warisan/pos/MainActivity.java','utf8');
const finance=readFileSync('app/src/main/java/my/warisan/pos/OwnerCashflow.java','utf8');
const chart=readFileSync('app/src/main/java/my/warisan/pos/OwnerBranchTrendChart.java','utf8');
const ownerHome=finance.slice(finance.indexOf('void drawOwnerHome()'),finance.indexOf('void drawOverview()'));

assert.match(main,/branchAccounts\.owner\(\)&&activePage==0\)\{\s*ownerCashflow\.drawOwnerHome\(\)/,
  'Owner Utama must show the owner dashboard, not the product Menu');
assert.match(main,/activePage!=0&&activePage!=23/,
  'Owner must still have a reachable branch product Menu');
assert.match(main,/"UTAMA","DUIT","CAWANGAN","TETAPAN"/,
  'Owner nav should show precisely the four approved sections');
assert.match(main,/new int\[\]\{0,3,15,4\}/,
  'Owner nav sections must land on Home, Money, Branches and Settings');
assert.match(ownerHome,/Keseluruhan · Semua Cawangan/);
assert.match(ownerHome,/Cawangan Aktif/);
assert.ok(ownerHome.indexOf('Keseluruhan · Semua Cawangan') < ownerHome.indexOf('Cawangan Aktif'),
  'Overall must precede branch cards');
assert.match(ownerHome,/if\(!b\.archived\)active\.add\(b\)/,
  'Archived branches must be excluded from the Owner home');
assert.match(ownerHome,/Lihat Butiran/);
assert.match(ownerHome,/Untung Bersih/);
assert.match(ownerHome,/host\.profitColor\(net\)/);
assert.match(ownerHome,/new OwnerBranchTrendChart/);
assert.match(ownerHome,/MENU TERLARIS · ORDER LIVE/);
assert.match(finance,/whereEqualTo\("status","PENDING"\)/,
  'Unpaid orders should come from actual Cloud records');
assert.match(finance,/whereEqualTo\("day",day\)/,
  'Branch-specific product counts must use orders from selected day');
assert.match(finance,/host\.activePage=23;host\.draw\(\)/,
  'Selected branch should keep its original Menu accessible');
assert.match(finance,/host\.activePage=22;host\.draw\(\)/,
  'Owner must retain Opening and Closing within branch operations');
assert.match(chart,/for\(int index=0;index<7;index\+\+\)/);
assert.match(chart,/history\.get\(current\)/,
  'The chart must use recorded sales, never invented sample data');

console.log('PASS: Owner Utama matches supplied overall/branch-card layout, live product counts and four-tab navigation');
