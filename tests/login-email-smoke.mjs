import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const main = readFileSync('app/src/main/java/my/warisan/pos/MainActivity.java', 'utf8');

// The existing login and signup entry points remain available when signed out.
assert.match(main, /if\(branchAccounts!=null&&branchAccounts\.blocked\(\)\)/);
assert.match(main, /if\(signed==null\)\{/);
assert.match(main, /staffSecurity\.loginWithCredentials\(/);
assert.match(main, /googleSignInClient\.getSignInIntent\(\)/);
assert.match(main, /showNewUserSignup\(\)/);

// A verified user sees their own Firebase email on the Menu and other pages.
assert.match(main, /void showSignedInEmail\(\)/);
assert.match(main, /FirebaseUser signed=firebaseAuth==null\?null:firebaseAuth\.getCurrentUser\(\)/);
assert.match(main, /String email=signed\.getEmail\(\)/);
assert.match(main, /if\(branchAccounts!=null&&branchAccounts\.active\(\)\)showSignedInEmail\(\)/);
assert.match(main, /branchAccounts\.branchName\(\)\.toUpperCase[\s\S]*?showSignedInEmail\(\)/);
assert.match(main, /\}else\{\s*showSignedInEmail\(\);\s*if\(branchAccounts\.staff\(\)\)/);

console.log('PASS: signed-out login and signed-in account email appear for Owner and Pekerja');
