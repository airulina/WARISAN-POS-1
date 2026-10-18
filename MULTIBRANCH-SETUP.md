# WARISAN POS-1 v3.16 — Printer + Owner / Branch / Pekerja (BETA)

## Apa yang sudah ada
- Satu APK: pemilik dan pekerja menggunakan aplikasi sama.
- Google Sign-in (Firebase Auth).
- Owner daftar perniagaan dan branch pertama; tambah branch tanpa membuang data asal.
- Pemilik daftar jemputan pekerja melalui **alamat Gmail**. Pekerja login dengan akaun Google yang sepadan dan terima jemputan.
- Pilih branch; data **tempatan pada telefon** disimpan berasingan per branch.
- Menu pekerja terhad kepada Menu, Order dan Account. Owner boleh akses stok, tetapan dan cloud backup.
- Printer: thermal Bluetooth ESC/POS atau A4/warna/Wi-Fi melalui Android Print Service.
- Cetak laporan **bulanan A4/PDF** untuk bulan yang dipilih.
- Cetakan thermal text-first, logo dimatikan secara default untuk elak printer berhenti selepas cetak logo.

## Penting sebelum digunakan sebagai POS pekerja sebenar
**Belum ada penyelarasan transaksi masa nyata di antara beberapa telefon.** Branch pada telefon pekerja mempunyai pangkalan data tempatan sendiri. Jangan jalankan jualan serentak pada beberapa telefon untuk satu branch; laporan, stok dan order tidak akan digabung secara automatik. Struktur branch + jemputan ini ialah fasa awal, bukan multi-device POS siap.

Role staff yang disekat pada UI bukan mekanisme keselamatan cloud; rules Firebase di bawah perlu aktif.

## 1. Firebase project / Google Login
Project Firebase sedia ada daripada `app/google-services.json` digunakan.
Pastikan Android package `my.warisan.pos` dan SHA-1 signing key aplikasi padan pada Firebase Authentication. Aktifkan Google sign-in di Firebase Authentication.
Jika ada error API Google Sign-In, semak OAuth SHA-1 / SHA-256, kemudian rebuild APK selepas download google-services.json baharu.

## 2. Firebase Firestore Rules (WAJIB)
1. Buka Firebase Console → projek WARISAN POS → Firestore Database → Rules.
2. Semak dan terbitkan peraturan daripada fail [firestore.rules](./firestore.rules). **Buat backup rules lama**; gabungkan dengan koleksi lain yang benar-benar digunakan sebelum publish.
3. Uji sebagai Owner dan Google pekerja berbeza. Pengguna pekerja hanya boleh terima jemputan yang sama email Google yang disahkan. Owner sahaja boleh mengubah metadata branch, dan membaca/memulihkan backup branch.
4. Rules yang berada di GitHub **tidak aktif secara automatik** sehingga diterbitkan ke Firebase Console.

Firestore queries bagi jemputan perlu gunakan `whereEqualTo("email", alamatGmailLogin)`.

## 3. Owner daftar
1. Install APK v3.16 (signing key mesti sama seperti versi asal untuk update).
2. Pergi **Setting → Account**, login Google.
3. Tekan **Daftar sebagai Owner baharu** → isi nama bisnes dan branch pertama.
4. Data POS tempatan terdahulu disalin ke branch pertama; backup lama dikekalkan (tak dipadam).
5. Untuk tambah branch: Account → **Tambah Branch**.

## 4. Owner daftar pekerja
1. Owner pilih branch (Account → Pilih Branch).
2. Account → **Daftar/Jemput pekerja** → masukkan Gmail pekerja.
3. Pekerja install **APK yang sama**, login Google dengan email dijemput.
4. Account → **Semak jemputan pekerja** → pilih branch.
5. Jika Owner belum menetapkan stok pada peranti pekerja, pekerja tidak dapat membuat jualan stok yang tiada. Tunggu fungsi sync realtime sebelum guna untuk operasi berbilang telefon.

## 5. Printer
1. Setting → Bill / Resit → **Printer A4 / WiFi / Thermal Bluetooth**.
2. **A4/warna/Wi-Fi:** pilih Android Print Service. Telefon akan memaparkan dialog untuk memilih printer yang sistem Android kenal pasti. Printer mungkin memerlukan aplikasi driver/print service pengeluar.
3. **Resit thermal:** pair printer Bluetooth dalam Android Settings, benarkan Nearby Devices, kemudian pilih printer. ESC/POS dihantar kepada printer itu sahaja.
4. **Logo thermal OFF secara default.** Hidupkan jika printer ESC/POS benar-benar menyokong imej raster. Test Print sebelum cetak order sebenar.
5. Laporan bulanan → **PRINT Laporan Bulanan A4**. Ini menggunakan Android Print Framework dengan halaman A4 PDF. Android tetap membenarkan pilihan media dalam dialog; sesetengah driver tak melaporkan saiz kertas dengan tepat.

## Perhatian
- Satu akaun pekerja mesti diberi jemputan menggunakan email Google, bukan password Owner.
- Google sign-in dan Firestore memerlukan internet.
- Uji stok, order, bayaran, closing dan print dengan data ujian terlebih dahulu.
- Belum ada ujian fizikal printer A4 atau thermal pada hardware pengguna.
