# WARISAN POS-1 v3.17 — kemas kini tanpa hilang data

## PENTING: cara update selamat
- APK mesti mempunyai application ID asal `my.warisan.pos`, menggunakan **kunci signing sama**, dan versi lebih tinggi daripada APK dipasang.
- **Install sebagai kemas kini**, jangan uninstall aplikasi lama, jangan tekan **Clear Storage**, jangan reset data.
- Sebelum update, dalam versi lama gunakan **Setting → Account → Backup to phone**. Simpan JSON di tempat selamat di luar folder app. Salinan automatik dalam app BUKAN pengganti backup luar telefon.
- Kalau Android tulis `App not installed` / `signatures do not match`, **jangan uninstall**. Sahkan keystore yang sama dahulu.
- Pada boot pertama v3.17, kod membuat fail backup dalaman sebelum sebarang pembetulan data. Backup lama dalam SharedPreferences tetap tidak dipadam.

## Akaun Owner sedia ada
- Identiti Google/Firebase Owner dan branch aktif disimpan dalam prefs berasingan dan kekal bila APK dikemas kini.
- Owner yang sudah mendaftar branch tak perlu daftar lagi: login Google yang sama, pilih branch lama jika perlu.
- Warisan POS sebelum sistem branch menggunakan prefs asal Android `MainActivity`. Ia terus digunakan selagi tiada branch baharu diaktifkan.
- Pada telefon yang ada data POS lama tetapi belum ada akaun Owner dalam Firebase: buka **Setting → Account → Guna data lama sebagai Owner (tanpa daftar semula)** selepas Google sign-in. Branch `utama_legacy` menggunakan **fail rekod asal** tanpa menyalin atau memadamkannya.
- Owner yang menggunakan branch baharu secara sengaja melihat stok dan jualan berasingan, bukan rekod hilang. Pilih branch Utama untuk kembali kepada rekod asal.

## Password Owner
- **Setting → Account → Tetapkan / Tukar Password Owner**.
- Jika belum pernah menetapkan password tempatan, buat kali pertama sekali. Jika sudah ada, masukkan password lama dan baharu.
- Password disimpan sebagai hash PBKDF2 dengan salt berasingan, **bukan plaintext**, dan tidak dimasukkan dalam backup data POS.
- Password Owner ini **bukan password Google/Firebase**, dan tidak menukar Google sign-in atau rules Firebase.
- Ini ialah tetapan password lokal, bukan mekanisme login semua peranti. Kawalan akses cloud tetap menggunakan Firebase Auth dan Firestore Rules.
- Update versi baharu tidak memadamkan password atau meminta pengguna daftar lagi.

## Data / backup
- Data POS asal `getPreferences(0)` kekal; pendaftaran branch utama lama `utama_legacy` mengekalkan fail asal.
- Sebelum update v3.17, app cuba menyimpan fail `before-update-317-legacy.json` dan, jika ada, `before-update-317-branch.json` dalam internal storage.
- Setting → Account → Pulihkan salinan sebelum update / pulih → pilih salinan yang sesuai (manual dan dengan pengesahan). **Jangan restore** jika angka asal sudah betul.
- Jika ada beberapa telefon untuk branch sama, app ini **BELUM** ada sync jualan/stok realtime; jangan gunakan serentak bagi branch sama sebelum sync dibina dan diuji.

## Had dan ujian
Kejayaan build bukan bukti kemas kini pada semua telefon atau printer. Uji selepas backup: jumlah stok, jualan hari ini, sejarah, order belum bayar, report bulanan, printer resit dan pilihan branch.
