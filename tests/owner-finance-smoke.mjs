import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const main=readFileSync('app/src/main/java/my/warisan/pos/MainActivity.java','utf8');
const cash=readFileSync('app/src/main/java/my/warisan/pos/OwnerCashflow.java','utf8');
const chart=readFileSync('app/src/main/java/my/warisan/pos/ProductSalesChart.java','utf8');

// Profits must preserve their sign; operating and product costs can create a loss.
assert.match(main,/return netSales-productCostBetween\(start,end\)-operatingExpenseTotal\(period,daily\)/);
assert.match(main,/if\(cents>0\)return themeLight\?0xff007D45:0xff4AF0A0/);
assert.match(main,/if\(cents<0\)return themeLight\?0xffB42332:0xffff727B/);
assert.match(main,/if\(cents<0\)[\s\S]*?return muted;/);

// Both finance pages and closing/report cards use the same semantic profit color.
assert.match(cash,/host\.profitColor\(localProfit\)/);
assert.match(cash,/host\.profitColor\(monthlyLocalProfit\)/);
assert.match(cash,/host\.profitColor\(branchLocalProfit\)/);
assert.match(main,/profitColor\(reportProfit\)/);
assert.match(main,/profitColor\(profit\)/);

// Product chart must count daily sold quantities, not revenue or returned orders.
assert.match(main,/void drawProductSalesSummary\(String day\)/);
assert.match(cash,/host\.drawProductSalesSummary\(day\)/);
assert.match(main,/if\(cashDailyMode\)drawProductSalesSummary\(selectedCashDay\)/);
assert.match(chart,/private static final int DAYS=7/);
assert.match(chart,/receipt\.optBoolean\("refunded",false\)/);
assert.match(chart,/quantity\[product\]\[day\]\+=Math\.max\(0,items\.optInt\(product\)\)/);
assert.match(main,/bukan semua cawangan/);

console.log('PASS: profit sign/colors and local chicken/beef chart wiring');
