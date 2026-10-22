import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const themeSource = readFileSync('app/src/main/java/my/warisan/pos/WarisanTheme.java', 'utf8');
const mainSource = readFileSync('app/src/main/java/my/warisan/pos/MainActivity.java', 'utf8');
const themeMatches = [...themeSource.matchAll(
  /new Palette\("([^"]+)","[^"]+","[^"]+",([\s\S]*?),(?:true|false)\)/g
)];
assert.equal(themeMatches.length, 5, 'All five selectable themes must remain available');

function luminance(hex) {
  const rgb = [1, 3, 5].map(i => parseInt(hex.slice(i, i + 2), 16) / 255);
  const linear = rgb.map(v => v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4);
  return linear[0] * 0.2126 + linear[1] * 0.7152 + linear[2] * 0.0722;
}
function contrast(a, b) {
  const first = luminance(a);
  const second = luminance(b);
  return (Math.max(first, second) + 0.05) / (Math.min(first, second) + 0.05);
}

for (const [, id, body] of themeMatches) {
  const colors = [...body.matchAll(/"(#[0-9A-Fa-f]{6})"/g)].map(x => x[1]);
  assert.equal(colors.length, 11, `${id}: expected the complete palette`);
  const [background, card, cardAlt, text, subtle, accent, primary,
    onPrimary, onAccent, navUnselected, navUnselectedText] = colors;
  for (const [label, foreground, backdrop] of [
    ['primary text on card', text, card],
    ['secondary text on card', subtle, card],
    ['primary text on background', text, background],
    ['text on primary button', onPrimary, primary],
    ['text on accent button', onAccent, accent],
    ['unselected navigation', navUnselectedText, navUnselected],
  ]) {
    assert.ok(contrast(foreground, backdrop) >= 4.45,
      `${id}: low contrast ${label} (${contrast(foreground, backdrop).toFixed(2)}:1)`);
  }
  assert.notEqual(background.toLowerCase(), cardAlt.toLowerCase());
}

assert.match(mainSource, /AlertDialog\.THEME_DEVICE_DEFAULT_LIGHT/,
  'Light theme must use light dialog list text');
assert.match(mainSource, /WarisanTheme\.readableText\(color,surface,ink\)/,
  'Legacy labels need automatic contrast protection');
assert.match(mainSource, /SYSTEM_UI_FLAG_LIGHT_STATUS_BAR/,
  'Light themes need dark system-bar icons');
console.log('PASS: all five palettes and cross-theme readability safeguards');
