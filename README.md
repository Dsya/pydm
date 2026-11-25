# PyDM untuk Android

Download manager ala 1DM+: browser WebView asli, mesin unduh Python (Chaquopy), daftar unduhan berbasis HTML.

```
WebView (browser)  --URL + cookie + User-Agent + referer-->  Python server.py (Chaquopy)
WebView (daftar)   <---------- http://127.0.0.1:8080 -------  mesin unduh multi-segmen
```

## Fitur
- Browser penuh (JavaScript, login, cookie) dengan kolom alamat, pencarian Google, dan tombol home/back/forward/reload.
- **Block Ads** (tombol `AD` di bar browser, default aktif, pilihan tersimpan): memblokir permintaan ke domain iklan/pelacak, menyembunyikan banner umum, memblokir popup yang muncul tanpa ketukan, serta pengalihan ke halaman iklan atau aplikasi lain.
  Jika sebuah situs rusak atau menolak menampilkan tautan unduhan, ketuk `AD` untuk mematikannya (halaman dimuat ulang otomatis).
- Unduhan terdeteksi otomatis lewat DownloadListener. Tombol ⬇ menampilkan media yang terdeteksi (mp4, mp3, zip, apk, iso, dll).
- Cookie, User-Agent, dan referer ikut dikirim, jadi tautan yang butuh login tetap bisa diunduh.
- Multi-segmen, jeda/lanjut/stop, antrean, kecepatan dan ETA, status tersimpan.
- Berjalan sebagai foreground service, jadi tetap hidup saat aplikasi ditutup.
- Folder default: `Download/PyDM` (bisa diubah di ikon pengaturan).

---

## Langkah 1 - Siapkan GitHub (sekali saja)
1. Buka github.com, login, lalu **New repository**. Nama: `pydm`. Jangan centang README, .gitignore, atau license.
2. Buat token: **Settings -> Developer settings -> Personal access tokens -> Tokens (classic) -> Generate new token (classic)**.
   Centang scope **repo** dan **workflow**, lalu salin tokennya. Token dipakai sebagai kata sandi saat `git push`.

## Langkah 2 - Upload dari Termux
```bash
pkg update -y && pkg install -y git unzip
termux-setup-storage            # izinkan akses penyimpanan

cd ~
unzip ~/storage/downloads/pydm-android.zip
cd pydm-android

git init -b main
git config user.name  "NamaAnda"
git config user.email "emailanda@contoh.com"
git config --global credential.helper store   # opsional: simpan token

git add .
git commit -m "PyDM: versi awal"
git remote add origin https://github.com/USERNAME/pydm.git
git push -u origin main
```
Ganti `USERNAME` dengan nama akun GitHub. Saat diminta:
- **Username**: nama akun GitHub Anda
- **Password**: tempel token dari Langkah 1 (tidak tampil saat ditempel, itu normal)

Jika zip tidak ada di `~/storage/downloads`, sesuaikan lokasinya (cek dengan `ls ~/storage/downloads`).

## Langkah 3 - Build di GitHub Actions
1. Buka repo `pydm` -> tab **Actions**. Build **Build APK** berjalan otomatis setelah push.
   Build pertama sekitar 10-15 menit (mengunduh Gradle, Android SDK, dan Python untuk Android).
2. Setelah centang hijau, klik run tersebut, lalu bagian **Artifacts** -> unduh **PyDM-debug-apk**.
3. Ekstrak zip tersebut untuk mendapat `app-debug.apk`.
4. Bisa juga jalankan manual: Actions -> Build APK -> **Run workflow**.

## Langkah 4 - Pasang dan pakai
1. Buka `app-debug.apk`, izinkan **Install dari sumber tidak dikenal** jika diminta.
2. Saat pertama dibuka:
   - Izinkan **notifikasi** (agar service unduhan terlihat).
   - Anda dibawa ke halaman **Akses semua berkas**: aktifkan untuk PyDM. Tanpa ini unduhan disimpan di folder privat aplikasi `Android/data/com.pydm/files/PyDM`.
   - Kembali ke aplikasi dengan tombol back.
3. Ketuk ikon 🌐 untuk membuka browser, buka situs, lalu ketuk tautan unduhan. Dialog nama berkas muncul, ketuk **Unduh**.
4. Ikon **+** di daftar dipakai untuk menempel URL langsung.
5. Disarankan: matikan optimasi baterai untuk PyDM (Pengaturan -> Aplikasi -> PyDM -> Baterai -> Tanpa batasan).

## Memperbarui aplikasi
Ubah file, lalu dari folder proyek di Termux:
```bash
git add . && git commit -m "pesan perubahan" && git push
```
Build baru berjalan otomatis. File yang paling sering diubah:
- `app/src/main/python/server.py` : mesin unduh
- `app/src/main/assets/index.html` : tampilan daftar unduhan
- `app/src/main/java/com/pydm/MainActivity.kt` : browser dan layanan Android

## Jika build gagal
Buka run yang merah -> klik step yang gagal untuk membaca log. Penyebab umum:
- **Gagal mengunduh dependensi**: tekan **Re-run jobs**, biasanya masalah jaringan sementara.
- **Versi Python tidak cocok**: Chaquopy 17 memakai Python 3.12, sama dengan `setup-python` di `.github/workflows/build.yml`. Jika mengubah satu, ubah keduanya.
- **Push ditolak (workflow scope)**: token Anda kurang scope **workflow**, buat token baru.

## Catatan teknis
- Chaquopy 17.0.0, Python 3.12, AGP 8.7.3, Gradle 8.9 (dipasang di CI, tidak perlu `gradlew`), minSdk 24, hanya arm64-v8a.
- APK debug ditandatangani otomatis dengan kunci debug. Untuk rilis resmi perlu keystore sendiri.
- Daftar domain iklan ada di `MainActivity.kt` (`AD_HOSTS`), tambahkan domain baru dipisah spasi. Block Ads tidak melewati hitung mundur atau captcha situs.
- Belum didukung: stream HLS (`.m3u8`), YouTube.
- Server hanya mendengarkan `127.0.0.1`, tidak terbuka ke jaringan.
- Mesin unduh bisa dijalankan sendiri di Termux/PC: `python app/src/main/python/server.py`.
