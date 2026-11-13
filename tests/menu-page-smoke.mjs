import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const root=new URL('../',import.meta.url);
const read=p=>readFileSync(new URL(p,root),'utf8');
const main=read('app/src/main/java/my/warisan/pos/MainActivity.java');
const live=read('app/src/main/java/my/warisan/pos/LiveBranchStore.java');
const owner=read('app/src/main/java/my/warisan/pos/OwnerCashflow.java');
const gradle=read('app/build.gradle');

// MENU page (activePage 0): products only, no TOTAL SALE card.
const menuPage=main.split('if(activePage!=0){drawPage();addNavigation(screen);return;}')[1]
  .split('void startHeaderClock(')[0];
assert.doesNotMatch(menuPage,/TOTAL SALE/);
assert.doesNotMatch(menuPage,/salesSummary\(/);
assert.match(menuPage,/Pilih menu/);
assert.match(menuPage,/setStoreLogo\(logo\)/);
// TOTAL SALE stays on SALE page and Owner DUIT overview.
assert.match(main,/salesSummary\(body,"TOTAL SALE",money\(sessionSales\(day\)\)/);
assert.match(owner,/salesSummary\(overallCard,"TOTAL SALE"/);

// Add menu: dialog stays open on error, duplicate check, Live publish.
const add=main.split('void addMenu(){')[1].split('void deleteMenu(){')[0];
assert.match(add,/setPositiveButton\("Simpan",null\)/);
assert.match(add,/menuNameError\(/);
assert.match(add,/parseMenuCents\(/);
assert.match(add,/\.commit\(\)/);
assert.match(add,/publishMenuIfLive\(\)/);
assert.match(add,/source.setVisibility\(View.GONE\)/);
// Delete menu keeps price/cost (old reports) and never deletes sales.
const del=main.split('void deleteMenu(){')[1].split('void pickMenuImage(')[0];
assert.doesNotMatch(del,/remove\("price_"/);
assert.doesNotMatch(del,/remove\("cost_"/);
assert.doesNotMatch(del,/stock_sales/);
// loadMenu keeps the order in progress when a Live menu update arrives.
assert.match(main,/int\[\] previousQty=qty;/);

// Live: new menus can be restocked / edited, orders tolerate new zero slots.
assert.doesNotMatch(live,/Menu tak sepadan/);
assert.doesNotMatch(live,/Menu Cloud tidak sepadan/);
assert.doesNotMatch(live,/Menu branch belum diselaraskan/);
assert.match(live,/padStockSlots\(readStock\(snap\),item\+1\)/);
assert.match(live,/fitOrderSlots\(quantities,remaining.size\(\)\)/);
assert.match(live.split('void publishMenu(){')[1].split('private ArrayList<Long> readStock')[0],/runTransaction/);

assert.match(gradle,/versionCode 58/);
assert.match(gradle,/versionName '3\.46\.7'/);
console.log('PASS: MENU page hides TOTAL SALE; add/delete menu and Live stock slot fixes');
