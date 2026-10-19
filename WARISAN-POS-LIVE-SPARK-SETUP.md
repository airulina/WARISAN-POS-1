# WARISAN POS-1 v3.23 — Firebase Spark Live (tanpa Cloud Functions)

## Status & keselamatan
- **Android build lulus**, dan Firestore Emulator menguji hak Owner/pekerja serta transaksi stok, bayaran dan refund dengan berjaya.
- **Firebase projek sebenar belum diaktifkan oleh perbualan ini.** Memasukkan `firestore.rules` ke GitHub tidak secara automatik menukar Firebase Console.
- **Jangan guna untuk operasi kaunter sebenar sebelum ujian pada 2 telefon**, termasuk ujian tanpa internet, printer, refund, closing, dan laporan.

## v3.23 — Pengguna baru, Owner dan pekerja
- Login Owner sedia ada: tekan **Owner · Log masuk Google**, guna email Google Owner asal, kemudian **Owner sedia ada · Pilih Branch**.
- Pengguna baru: selepas Google login tekan **Pengguna Baru · DAFTAR OWNER / SIGN UP** dan isi nama perniagaan / branch. Akaun baharu tidak mengambil data POS lama.
- Owner lama dengan data asal: gunakan **Owner lama · Gunakan data POS asal** secara sengaja; fungsi itu tidak muncul dalam Account pekerja.
- Pekerja yang sudah login hanya boleh melihat akaun pekerja / status refund. Butang untuk pilih Owner, daftar Owner, atau pulihkan data Owner lama tidak dipaparkan.
- **Email pekerja aktif atau cuma DISEKAT tak boleh daftar Owner**, termasuk jika login Google dengan email sama. Owner perlu tekan **BUANG AKSES** dahulu. Setelah profile `removed=true`, email boleh digunakan bagi pendaftaran Owner baru tertakluk kepada kaedah login Firebase.
- Perlindungan ini memerlukan Firestore Rules `firestore.rules` terkini **diterbitkan** pada Firebase Console → Firestore Database → Rules → Publish. Menjalankan GitHub Actions sahaja bukan mengaktifkan rules pada Firebase sebenar.
- Role pekerja dikuatkuasakan berdasarkan dokumen `staffProfiles/{uid}` dan `staffEmails/{email}` untuk pekerja yang didaftarkan melalui versi baharu. Akaun pekerja lama yang sudah wujud sebelum v3.23 masih dilindungi melalui profil UID; semak indeks email pada Firestore apabila mengurus akaun lama. Jangan padam rekod jualan lama.
- Selepas perubahan rules, uji menggunakan akaun pekerja aktif, pekerja disekat, pekerja dibuang, serta Google Owner baharu.
 
## 0. Backup dahulu — JANGAN UNINSTALL
1. Buka APK WARISAN POS yang sedang digunakan, **Setting → Account → Backup to phone**.
2. Simpan salinan JSON di Downloads / Google Drive / tempat selamat **di luar data aplikasi**.
3. Install APK v3.19 **di atas app lama**. Android package kekal `my.warisan.pos` dan sertifikat signing GitHub yang sama.
4. Jika keluar **App not installed** / tandatangan berbeza, **JANGAN UNINSTALL/CLEAR DATA**. Hentikan dan semak sijil APK dahulu.
5. Snapshot dalaman sebelum update masih wujud untuk pilihan recovery. Data lama tidak dipadam dalam migrasi cloud.
6. Pembetulan khas stok pada September 2026 yang dahulu merujuk **tarikh hari ini** telah dinyahaktifkan untuk mengelakkan rekod stok semasa terubah selepas kemas kini.

## 1. Aktifkan Firebase Spark (RM0, tiada kad)
Guna Firebase project **`warisan-pos`** yang sudah ditetapkan dalam `app/google-services.json`. Jangan buat projek Firebase baharu.

1. Buka [Firebase Console — Warisan POS](https://console.firebase.google.com/project/warisan-pos/overview).
2. **Build → Authentication → Sign-in method**: hidupkan **Google** dan **Email/Password**. Pendaftaran email/password mesti diaktifkan untuk Owner cipta akaun pekerja.
3. **Build → Firestore Database → Rules**: salin keseluruhan fail [firestore.rules](./firestore.rules), semak, kemudian tekan **Publish**. Backup rules lama dahulu dan gabungkan jika projek itu melayan aplikasi lain.
4. Pastikan Firestore database sudah wujud dan masih **Spark (no-cost)**. **Tak perlu deploy Cloud Functions, tak perlu Blaze, tak perlu VPS.** Fail `firebase.json` hanya konfigurasi Firestore.
5. Ujian rules sumber kod boleh dilihat di [GitHub Actions: Check Firebase Spark Security Rules](https://github.com/airulina/WARISAN-POS-1/actions/workflows/check-firestore-rules.yml), tetapi ia **bukan** bukti Firebase production sudah diaktifkan.

**FireStore Spark Free (standard):** sehingga 50,000 bacaan dan 20,000 penulisan sehari; setelah had dicapai, operasi mungkin disekat dan perlu menunggu reset kuota / naik taraf secara pilihan. Periksa [harga rasmi Firebase](https://firebase.google.com/pricing).

## 2. Owner aktifkan LIVE
1. Owner login menggunakan **Google yang sama seperti sebelum ini**. Tiada daftar semula untuk akaun dan branch sedia ada.
2. Owner pilih **Branch Utama** atau branch lain dalam **Setting → Account**.
3. Account → **Live branch / Semak jualan sekarang** → **AKTIFKAN**.
4. App menghantar baki stok pada telefon Owner sebagai **stok permulaan Live sekali sahaja**. Sebelum tekan, semak stok sebenar dahulu.
5. Owner boleh guna **Terbit menu terbaru ke Live** untuk berkongsi harga menu/custom menu dan menu yang disembunyikan kepada pekerja.
6. Lepas aktif, stok cloud menjadi rujukan semasa. Transaksi Live menggunakan transaksi atomik Firestore. Jika internet terputus, app **menghalang order Live baharu** bagi mengelakkan jualan senyap-senyap bercanggah.
7. **Stok lama, order lama, duit lama tidak dipadam**, dan laporan lama kekal pada telefon asal.

## 3. Owner daftar pekerja
1. Account → **+ Daftar pekerja · Email + Password**.
2. Masukkan email pekerja dan tetapkan password sementara minimum 10 aksara. Owner Google **tetap login** semasa akaun pekerja dicipta.
3. Akaun pekerja menggunakan Firebase Authentication Email/Password melalui aplikasi sekunder; **password tidak disimpan dalam POS atau Firestore**.
4. Pekerja semak email untuk **pengesahan alamat email**, kemudian install APK **yang sama** pada telefonnya dan pilih **Pekerja · Email + Password**.
5. Owner boleh guna **Urus / sekat pekerja / reset password** untuk mengaktifkan/menyahaktifkan akaun atau menghantar pautan reset password ke email pekerja.
6. Jika pekerja disekat, Firestore Rules menolak akses mereka dan aplikasi log keluar apabila profil menerima perubahan.


## 3A. Owner lihat semua pekerja dan buang akses
1. Dalam app: **Setting → Senarai Pekerja / Buang Akses** atau **Account → LIHAT SEMUA PEKERJA**.
2. Skrin menunjukkan semua pekerja yang sudah mempunyai **rekod keahlian Firestore**, diasingkan mengikut branch dan status **AKTIF / DISEKAT / DIBUANG**.
3. Tekan email pekerja. Pilih:
   - **Sekat akses sementara** — pekerja tidak boleh jalankan operasi Live; Owner boleh aktifkan semula.
   - **BUANG PEKERJA** — sahkan penarikan balik akses. Profil pekerja diarkibkan, bukan dipadam dari Firebase Authentication. Jualan dan rekod refund kekal sebagai audit.
   - **Aktifkan semula** — Owner boleh pulihkan akses jika pekerja masuk bekerja semula.
   - **Reset password** — hantar pautan perubahan password ke email pekerja.
4. **Peraturan Firestore terkini dalam \`firestore.rules\` mesti diterbitkan** ke Firebase Console sebelum fungsi sekat/buang boleh berjaya. Ia bukan aktif hanya kerana APK di-update.
5. Akaun Firebase Authentication milik pekerja tidak boleh dipadam dari aplikasi Owner Spark tanpa kuasa Firebase Admin. Untuk benar-benar memadam rekod Auth, pemilik projek boleh gunakan Firebase Console → Authentication → Users; **pilihan itu berasingan daripada sekatan akses POS**, dan jangan padam rekod transaksi.
6. Pekerja yang belum disimpan sebagai keahlian branch mungkin ada dalam Firebase Authentication tetapi **tidak muncul** dalam senarai app. Semak Firebase Authentication Users bagi akaun yang tersangkut ketika proses pendaftaran; jangan gunakan akaun itu untuk operasi sehingga profil keahlian dipadankan dengan selamat.

## 4. Paparan & pembayaran
- **OWNER**: Menu, Order, Stok, Duit, semua report asal, Setting, Account, tambah branch/pekerja, refund, Opening/Closing. Account ada **Live dashboard branch**, **Pantau semua branch Live**, **Report Live bulanan dan Cetak A4/PDF**.
- **PEKERJA**: Menu, Order, **HARI INI**, Account. Menu STOK/DUIT lama, Report Bulanan, Backup/Restore, Edit Menu/Setting disorok. Firestore Rules juga menolak bacaan laporan harian lama oleh pekerja.
- Pekerja boleh **Opening/Closing**, jualan Tunai/QR dan cetak slip seperti sebelumnya.
- Jualan Live dan stok disahkan di Firestore secara atomik sebelum order/bayaran lokal disimpan. Owner mendapat perubahan dalam dashboard Cloud dan rekod jualan daripada peranti pekerja diimport masuk ke laporan tempatan Owner apabila event muncul.
- Sesetengah operasi lama seperti **EDAR / Stock pulang / hutang pembekal** masih bersifat tempatan dan **belum semuanya dalam lejar Cloud**. Jangan anggap laporan Cloud merangkumi transaksi yang tidak direkod melalui order Live.
- Print thermal Bluetooth dan printer Android A4 tersedia seperti v3.18; **ujian pada model printer sebenar masih diperlukan**.

## 5. Refund memerlukan Owner
1. Pekerja buka history jualan **hari operasi semasa** → pilih jualan Live → **MOHON REFUND**.
2. Owner buka Account → **Refund menunggu kelulusan Owner** → **APPROVE** atau **TOLAK**.
3. Pekerja buka HARI INI / Account → **Semak kelulusan refund**, pilih APPROVED. Firestore menyemak request `CLAIMED`, memulangkan jumlah daripada angka jualan Cloud sekali sahaja, kemudian merekod refund lokal.
4. ID kelulusan direkod bersama refund. Tanpa internet atau tanpa Owner approve, pekerja tidak boleh refund.
5. Refund ini mencatat **pemulangan wang dalam POS**, bukan API pemulangan automatik ke bank pelanggan.

## 6. Ujian sebelum guna di kios
Pastikan semua ini lulus:
- Owner install update, stok/jualan/refund/report lama masih sama.
- Owner aktifkan Live sekali dengan baki yang betul.
- Owner cipta pekerja email + password; email verifikasi; pekerja login tanpa Google Owner.
- Owner tambah stok Live → kedua-dua telefon nampak baki baharu; pekerja tidak ada tab STOK.
- Pekerja Confirm Order → cloud tolak stok; transaksi tunai/QR → report Live Owner meningkat sekali sahaja.
- Cuba 2 order serentak dengan stok terakhir: Firestore transaction mesti menghalang oversell.
- Closing pekerja → sesi di Firestore; laporan harian Owner/pekerja sepadan.
- Refund pekerja tanpa approve ditolak; Owner approve; refund direkod sekali sahaja.
- Matikan Wi-Fi: app tidak membuat order Live offline.
- Printer thermal cetak teks lengkap, dan A4/PDF berfungsi.
- Cuba disable pekerja dan pastikan dia tak boleh terus menjual.
- Keluarkan JSON backup dan simpan secara berasingan.

## Batas / penyelenggaraan
- Live transaksi Cloud memerlukan capaian internet. Backend Spark percuma sesuai kios kecil, tetapi perlu pantau penggunaan.
- Rekod sebelum Live diaktifkan **tidak dimigrasi sebagai transaksi sejarah cloud**. Ia masih dalam telefon Owner/backup. Ini melindungi sejarah daripada import berganda.
- Nombor resit/order **lokal** masih boleh sama antara dua telefon; ID cloud `liveOrderId` ialah ID unik bagi mengelakkan transaksi stok dan bayaran berganda.
- Transaksi berjaya di cloud tetapi gagal disimpan pada telefon dipulihkan melalui semakan order cloud selepas app dibuka semula; ini perlu diuji secara nyata.
- Rules diuji oleh emulator; ia mesti **diterbitkan ke Firebase Console** untuk berkuat kuasa.

## Off Day / Stock Habis — Owner mengunci branch (tanpa GPS)
- **Setting / Account / Duit (Cashflow)** → **URUS STATUS BRANCH · BUKA / OFF DAY / STOK HABIS**.
- Pilih **nama branch tertentu**; Owner boleh set **BUKA**, **OFF DAY (cuti)**, atau **TUTUP · STOK HABIS**. Setiap branch bebas: Jenjarom boleh OFF DAY sementara Jalan Kebun kekal BUKA.
- Status disimpan pada `workspaces/{ownerUid}/branches/{branchId}.operatingStatus` dalam Firestore. App versi lama yang tidak ada medan ini dianggap BUKA bagi keserasian.
- Pekerja yang ditetapkan pada branch OFF DAY/STOK HABIS **tidak dapat melengkapkan login POS**, walaupun Firebase Authentication sempat menerima email/password. Tiada paparan jualan, Opening, atau transaksi Live dibenarkan.
- Jika Owner menutup branch ketika pekerja sudah login, aplikasi pekerja baharu menerima kemas kini server dan mengeluarkan sesi. Security Rules turut menolak akses kepada order, jualan dan stok ketika status tertutup.
- **Tidak menggunakan GPS**. Tiada kebenaran lokasi diperlukan. Peranti pekerja masih terikat seperti polisi peranti sedia ada (Owner boleh reset peranti).
- Dalam **Duit / Cashflow → Status Opening Semua Branch**, Owner lihat nama branch, status operasi (OFF DAY/STOK HABIS/BUKA) serta rekod Opening/Closing yang berasingan.
- **PENTING**: Jika branch sudah buat Opening sebelum Owner menutupnya, transaksi dan Closing lama tidak dipadam. Owner mesti semak dan lengkapkan Closing seperti prosedur kaunter sebelum membuka sesi baharu.
- Pekerja yang offline tidak dapat mengesahkan status server. App terkunci sehingga menerima pengesahan branch BUKA daripada Firebase, dan order Live kekal memerlukan internet.
- **WAJIB Publish `firestore.rules` terkini kepada Firebase production**. Fail di GitHub atau APK sahaja tidak mengubah Rules yang diterbitkan.
- Selepas Publish, uji dua akaun pada telefon: Owner set OFF DAY → pekerja cuba login dan pekerja yang sedang login disekat; Owner tukar kepada BUKA → pekerja login semula. Jangan uji dengan transaksi sebenar sebelum semak baki stok dan data lama.
