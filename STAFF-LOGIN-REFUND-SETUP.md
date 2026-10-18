# WARISAN POS-1 v3.18 BETA — Owner, pekerja, password dan kelulusan refund

## Status
Android APK boleh dikompilasi, tetapi ini **BETA**. Integrasi Firebase **perlu dikonfigurasi dan dideploy**. Jangan gunakan pada jualan sebenar beberapa telefon sebelum sync stok/order/jualan antara peranti siap.

## Cara hak akses

### Owner
- Login menggunakan **Google Firebase**, identiti Owner yang sama seperti sebelumnya.
- Semua menu asal masih ada: Stok, Duit, report penuh, Setting, Account, Opening, Closing, Refund Owner.
- Owner memilih branch, kemudian Account → **Daftar pekerja · Email + Password**. Isi email dan password sementara minimum 10 aksara.
- Owner boleh lihat Account → **Refund menunggu kelulusan Owner**, lalu pilih APPROVE atau TOLAK.
- Password Owner tempatan daripada v3.17 masih boleh ditetapkan atau ditukar. Ia **bukan** kata laluan pekerja dan **bukan** login Firebase Google.

### Pekerja
- Menggunakan APK **yang sama**, pilih **Pekerja · Email + Password** dan gunakan akaun yang dicipta Owner.
- Menu bawah: MENU, ORDER, HARI INI, ACCOUNT. Tiada STOK, DUIT lama, laporan bulanan, harga mentah, backup/restore dan Setting.
- HARI INI memaparkan jualan, bilangan transaksi, tunai, QR serta pilihan Opening/Closing.
- History hanya untuk tarikh sesi operasi semasa. Untuk refund, pekerja klik **MOHON REFUND**. Permintaan PENDING dihantar ke Firestore.
- Selepas Owner APPROVE, pekerja buka Account atau HARI INI → **Semak status refund saya**, pilih APPROVED untuk menuntut kelulusan dan merekod refund **secara tempatan**. Transaksi cloud ditanda CLAIMED/COMPLETED; ID kelulusan dicatat pada rekod lokal.
- Employee login memerlukan akaun Firebase Auth aktif serta profil `staffProfiles` yang disediakan oleh fungsi server.

**TIDAK MENGGUNAKAN PASSWORD OWNER PADA TELEFON PEKERJA.** Ini sengaja untuk mengelakkan password Owner tersebar. Pilihan sebenar ialah Owner sendiri login di telefonnya dan approve secara online.

## Setup Firebase yang mesti dibuat sebelum fungsi baru boleh digunakan

1. Simpan backup JSON data POS asal dahulu. **Jangan uninstall app lama.**
2. Di **Firebase Console → Authentication → Sign-in method**, hidupkan **Email/Password** selain Google.
3. Di Firestore → Rules, semak, uji pada Firebase Emulator, dan deploy fail `firestore.rules`. Fail di GitHub sahaja **tidak aktif**. Rules menolak pekerja daripada menukar sendiri role / approve refund.
4. Untuk Owner boleh mencipta akaun pekerja tanpa berkongsi akaun Firebase Admin, gunakan kod Cloud Functions di folder `functions/`. Deploy fungsi `createStaffAccount` dalam region `asia-southeast1` daripada folder root project:
   ```sh
   npm install -g firebase-tools
   firebase login
   firebase use --add
   cd functions && npm install && cd ..
   firebase deploy --only firestore:rules,functions
   ```
   Periksa bahawa pilihan Firebase project betul-betul sepadan dengan `app/google-services.json`. Simpan rahsia/kunci Firebase Admin di server sahaja, jangan masukkan dalam APK atau repository.
5. **Firebase Cloud Functions memerlukan Blaze (pay-as-you-go) dengan billing**, walaupun ada kuota percuma. Google mungkin mengenakan caj. Jangan aktifkan billing atau deploy ke production tanpa Owner bersetuju dan memahami kos.
6. Test di **dua akaun berbeza**: Owner (Google) dan pekerja (Email+Password). Test Firestore Rules/approved/denied termasuk cuba akses tanpa izin.

## Had kritikal sebelum penggunaan
- **Belum ada pangkalan data transaksi pusat / sync masa nyata.** Stok, order, bayaran dan report di telefon berbeza **belum** disatukan. Jika pekerja menjual dengan telefon sendiri, Owner belum automatik nampak rekod tersebut dan baki stok boleh bercanggah. Jangan urus satu branch pada banyak telefon untuk jualan live.
- Approval refund Firestore menjamin **siapa yang approve di server** apabila rules sudah dideploy; ia **tidak menjamin konsistensi ledger antara peranti**. Worker yang berjaya tuntut kelulusan merekod refund pada data lokal telefon itu sahaja. Status CLAIMED dengan simpanan lokal gagal perlukan semakan Owner.
- Password pekerja yang Owner masukkan tidak disimpan dalam Firestore/SharedPreferences POS, hanya diserahkan terus melalui Admin Auth. Owner perlu kongsi password sementara secara selamat kepada pekerja. Jika email sudah didaftarkan di Firebase Auth, fungsi **menolak** untuk mengelakkan menimpa password akaun lain.
- Rules peranan server bukan menggantikan perlindungan aplikasi dari peranti yang diubah suai. Tidak ada pengesahan bank atau pembekal refund sebenar di sini.
- Owner tidak perlu daftar semula jika branch sudah wujud. Branch utama legacy masih membaca rekod asal; pemasangan APK boleh update terus hanya dengan signing key sama.
- Pada peranti lama yang belum pernah mendaftarkan Owner dalam Firebase, log masuk Google sama dan pilih **Guna Owner lama / data asal**. Ini bukan daftar semula; data POS asal kekal.

## Ujian wajib
Sebelum guna sebagai perniagaan sebenar: backup, buka APK update, semak angka stok/jualan semalam, Owner Google login, daftar pekerja, pekerja login tanpa owner, confirm order, opening, closing, akses laporan harian sahaja, refund ditolak, refund diluluskan, printer thermal dan A4.
