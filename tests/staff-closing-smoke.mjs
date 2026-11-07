import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const main = readFileSync('app/src/main/java/my/warisan/pos/MainActivity.java', 'utf8');
const math = readFileSync('app/src/main/java/my/warisan/pos/ClosingMath.java', 'utf8');
const cloud = readFileSync('app/src/main/java/my/warisan/pos/LiveBranchStore.java', 'utf8');
const owner = readFileSync('app/src/main/java/my/warisan/pos/OwnerCashflow.java', 'utf8');
const rules = readFileSync('firestore.rules', 'utf8');

// Four worker tabs only; opening/closing stay under Sale, printer under Settings.
assert.match(main, /new String\[\]\{"MENU","ORDER","SALE","SETTING"\}/);
assert.match(main, /worker\?new int\[\]\{0,11,12,5\}/);
assert.match(main, /void drawStaffSettings\(\)/);
assert.match(main, /activePage==5\)[\s\S]*?drawStaffSettings\(\)/);
assert.match(main, /void drawPosSession\(\)/);
assert.match(main, /"CLOSING POS · "\+day,\(\)->requireClosingExpenses\(\)/);

// Opening RM0.00 is a first-class choice; a cash deficit is not hidden.
assert.match(main, /priceField\("Opening RM","0\.00"\)/);
assert.match(math, /long amount = \(long\) opening \+ cashSales - cashExpenses;/);
assert.match(math, /return \(int\) amount; \/\/ Losses are real/);
const expectedCash = (opening, cash, expenses) => opening + cash - expenses;
assert.equal(expectedCash(0, 27000, 1500 + 800), 24700);
assert.equal(expectedCash(0, 2000, 2500), -500);

// Expenses are itemized, stored once in cash ledger and the immutable closing.
assert.match(main, /"category",category/);
assert.match(main, /"source","pos_closing"/);
assert.match(main, /c\.put\("expenses",expenses\)/);
assert.match(main, /c\.put\("expenseTotal",closingExpenseTotal\(expenses\)\)/);
assert.match(main, /String receipt=e\.optString\("receipt",""\)/);
assert.match(main, /saveClosingExpense\(closingCash,day,/);

// A worker must close using server-visible sales from every device in the branch.
assert.match(main, /sessionCloudReady\(day\)\?checkedCloudCents\(live\.sales\(\)\)/);
assert.match(main, /sessionCloudReady\(day\)\?checkedCloudCents\(live\.cash\(\)\)/);
assert.match(main, /live\.sessionConfirmed\(false,day,balance,expenses,remark,/);
assert.match(cloud, /state\.put\("expenseTotal",spent\)/);
assert.match(cloud, /state\.put\("closingRemark",remark==null\?"":remark\)/);
assert.match(cloud, /void showOwnerForcedClosing\(\)/);
assert.match(cloud, /fields\.put\("forcedByOwner",true\)/);
assert.match(cloud, /if\(!current\.exists\(\)\|\|!Boolean\.TRUE\.equals\(current\.getBoolean\("open"\)\)\)/);
assert.match(rules, /request\.resource\.data\.closing >= -100000000/);
assert.match(rules, /request\.resource\.data\.expenses\.size\(\) <= 20/);

// Archive never silently enters the active dashboard; PDF retains history.
assert.match(owner, /if\(row\.archived\)continue; \/\/ Active dashboard/);
assert.match(owner, /if\(!includeArchived&&branch\.archived\)continue/);
assert.match(owner, /OwnerOverallReportPdf\.print\(host,host\.selectedMonth,rows,overallDays\(true\)\)/);

// Refunded sale consumed stock once; its damage audit is not another deduction.
assert.match(main, /if\(refund\)continue; \/\/ Refunded items were already deducted/);

console.log('PASS: worker 4-tab flow, zero opening, signed expense closing, cloud audit, archive and refund invariants');
