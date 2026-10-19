# WARISAN POS v3.25 — Login, pekerja ikut branch, GPS dan Cashflow Live

## Penting sebelum kemas kini
1. Backup JSON melalui Owner → Account → Backup to phone.
2. **Jangan uninstall / Clear Data**. Pasang APK v3.25 terus di atas versi sebelum ini; Android perlu sijil signing yang sama.
3. **Publish fail [firestore.rules](./firestore.rules) terkini** dalam Firebase Console → Firestore → Rules. Rules dalam GitHub tidak terpakai sehingga dipublish. Simpan salinan rules lama sebelum ganti.
4. Aktifkan Firebase Authentication: Google serta Email/Password seperti sedia ada. **Tidak perlukan Cloud Functions / pelan Blaze**.

## Login tunggal
Skrin pertama:
- Email
- Password
- LOG IN
- atau Teruskan dengan Google
- **SIGN UP** di bawah untuk pengguna baharu sahaja.

Pengguna email/password yang sudah ada workspace Owner akan disemak sebagai Owner. Akaun pekerja disemak melalui staffProfiles, dan Owner hanya boleh mendaftarkan pekerja kepada branch yang dipilih. Pengguna baharu boleh Sign Up melalui email/password (sahkan email dahulu) atau Google, kemudian daftar Owner/perniagaan baru. Akaun pekerja aktif/dibekukan tidak boleh dinaik taraf sendiri kepada Owner.

## Owner daftar pekerja
Owner → Account → **Daftar Pekerja**: isi **Email, Password, Confirm Password dan Branch Tetap** (contohnya Jenjarom). Pilih branch daripada senarai semua branch milik Owner. Pekerja tidak boleh ubah branch dari telefon sendiri; Firebase membership/role disimpan ikut UID dan branch.

**Satu peranti pekerja:** Login pertama mengikat akaun pekerja pada pemasangan telefon yang sah. Jika password dikongsi dan telefon lain cuba Login, app akan menolak. Owner → Senarai Pekerja → pilih email → **RESET PERANTI** hanya apabila menukar telefon. Ini kawalan aplikasi, bukan bukti identiti fizikal atau perlindungan terhadap telefon yang telah diubah suai; perlu pengujian pada dua telefon.

## Owner tetapkan lokasi kios
1. Pergi ke lokasi kios sebenar **Jenjarom**.
2. Owner pilih branch Jenjarom → Account → **Tetapkan Lokasi GPS Branch**.
3. Benarkan **Precise Location**. App simpan koordinat **pusat kios** di dokumen branch Firebase (Owner sahaja boleh tetapkan), dengan radius tetap **250 meter**.
4. Ulang sekali bagi setiap branch aktif termasuk Jalan Kebun. Jangan set koordinat Jenjarom semasa berada di rumah atau kios lain.
5. Owner boleh pilih **Buka / Tutup Opening Branch**. Bila ditutup, Firebase Rules sendiri menolak Opening baharu oleh pekerja.

## Login di tempat berlainan & Opening
- Pekerja boleh Login menggunakan email/password, tetapi akaun tetap terikat kepada branch yang Owner berikan. Dia tidak boleh memilih Jalan Kebun jika akaun didaftarkan untuk Jenjarom.
- Selepas Login, aplikasi membuat **satu semakan GPS latar depan** jika diberi izin; cuba Login di lokasi lain direkod sebagai `OUTSIDE_BRANCH` untuk semakan Owner. Tiada koordinat GPS pekerja disimpan; yang disimpan status, jarak anggaran, UID, email dan masa.
- Sebelum pekerja buat **Opening**, semakan GPS dibuat lagi. Jika melebihi radius / GPS tidak cukup tepat / branch telah ditutup / lokasi belum ditetapkan / ada mock location, Opening disekat dan dicatat bila boleh.
- GPS telefon boleh dimanipulasi (spoofing), dan aplikasi pada peranti yang diubah suai boleh dipintas. **Jangan menganggap semakan ini bukti muktamad lokasi atau pengganti pengurusan pekerja**. Owner boleh menyemak bukti tambahan sekiranya meragukan. Tiada tracking sepanjang hari atau lokasi latar belakang.

## Owner Cashflow
Owner → **DUIT / CASHFLOW** akan memaparkan **STATUS BRANCH HARI INI LIVE**: nama branch, belum Opening / sudah Opening / sudah Closing, email akaun yang buka/tutup serta masa kemas kini. Status menggunakan Firestore snapshot listeners.
- Butang **CASHFLOW LIVE · Status Opening Semua Branch** untuk senarai.
- Butang **Semak Cubaan Opening Di Luar Kawasan** menunjukkan status GPS pada Login dan Opening yang gagal, disusun mengikut branch.
- **Opening pekerja mesti disahkan Cloud terlebih dahulu**, barulah flag Opening disimpan pada telefon; jika sambungan tidak ada, tidak akan buat Opening.

## Had semasa
- Ujian build Android & emulator Security Rules **bukan** ujian peranti sebenar / Firebase projek production.
- Firestore Rules **tidak dapat mengesahkan sumber GPS** yang datang daripada aplikasi mudah alih; pencegahan spoofing tahap tinggi memerlukan sokongan attestation/mesin kios tepercaya.
- Kunci peranti menggunakan pengecam pemasangan aplikasi; ia membantu mengesan perkongsian email/password pada telefon biasa, tetapi boleh direset dengan persetujuan Owner dan bukan cryptographic device attestation.
- Rekod login/Opening bergantung pada internet & permission GPS; jika GPS disekat, pekerja masih boleh Log In tetapi **tidak boleh Opening**.
- Sekiranya aplikasi sudah ada sesi Opening lama, Owner perlu semak sebelum menukar cara live.
- Data stok, jualan dan resit lama tidak dipadam oleh perubahan UI login/lokasi.
