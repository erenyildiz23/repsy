# ADR-006: Ölçüm metodolojisi

Tarih: 2026-10-23 · Durum: Kabul edildi

## Bağlam

Soru 1 (iki strateji aynı yükte nasıl davranıyor?) sayılarla cevaplanacak. Yerel bir bulut
konteynerinde (4 çekirdek, 15 GiB, overlayfs, `results/environment.md`) ölçülüyor; amaç mutlak
kapasite değil, aynı koşullarda göreli karşılaştırma.

## Karar

| Konu | Karar | REFERENCE |
| --- | --- | --- |
| Yük modeli | Açık model: `constant-arrival-rate`. Sunucu yavaşlarsa gelen yük azalmaz, p99 iyimser çıkmaz | §9.1 |
| Geçersiz koşu | `dropped_iterations > 0` ya da hata oranı ≥ %1 olan koşu medyana katılmaz, tabloda "geçersiz" diye görünür | §9.2 |
| Isınma | Her koşuda 30 sn `warmup` senaryosu (`phase=warmup` etiketi), metrikler yalnızca `phase=measure`. Ayrıca her backend'in taze stack'inde kayda geçmeyen bir JIT ısınma koşusu | §9.3 |
| Boyutlar | 1 KB, 1 MB, 50 MB ayrı koşular, ayrı satırlar. 1 KB depolamayı değil HTTP/framework/DB yükünü ölçer | §9.4, §9.8 |
| CPU izolasyonu | Çekirdekler sabitlendi: Repsy 0-1 (`cpus: 2`), PostgreSQL + S3 + Toxiproxy 2, k6 3. k6 uygulamayla aynı çekirdeği paylaşmıyor | §9.5 |
| Tekrar | Hücre başına 3 koşu, medyan ve [min-max] | §9.10 |
| Yükleme yolu | Maven `PUT` ham gövde (`application/java-archive`). Multipart yok, yani referansın "multipart çift yazım" tuzağı bu ölçümde yok | §9.9 |
| İndirme seti | `setup()` 20 dosya yükler, her istek rastgele birini çeker | §8 |
| Hızlar | İki stratejide aynı. Kalibrasyonla (`results/raw/calib/`) iki stratejinin de düşen iterasyon olmadan taşıdığı seviyede seçildi: yükleme 1 KB 50/sn, 1 MB 20/sn, 50 MB 1/sn; indirme 1 KB 100/sn, 1 MB 50/sn, 50 MB 2/sn | §8 |

## Bilinçli sapmalar

- **Süre:** referans 1 dk ısınma + 5 dk ölçüm öneriyor. 36 koşu × 6 dk = 3,6 saat olurdu. 30 sn +
  90 sn seçildi (36 koşu ≈ 85 dk). Kuyruktaki nadir olaylar (p99'un ötesi) daha az örnekle ölçülüyor.
  50 MB satırları 90 sn'de yalnızca 90-180 istek, bu yüzden p99 oralarda birkaç örneğe dayanıyor.
- **Soğuk önbellek denendi, bu ortamda üretilemedi.** Kullanıcı onayıyla (2026-10-23)
  `load/run-cold.sh` indirme koşularını her saniye `sync; echo 3 > /proc/sys/vm/drop_caches` ile
  tekrarladı (backend × boyut başına 1 koşu, kullanım bütçesi için). Sonuç sıcak koşularla aynı
  aralıkta (`results/baseline.md`, `download_cold` satırları; ör. 50 MB fs p50 101 → 103 ms, s3 161
  → 156 ms). En olası açıklama: `drop_caches` yalnızca bu Firecracker VM'inin page cache'ini
  boşaltıyor, VM'in diski host tarafında da önbellekleniyor ve o katmana erişim yok. Yani bu
  ortamda gerçek bir disk okuması ölçülemedi. Sonuç "soğuk önbellek fark etmiyor" diye değil,
  "soğuk okuma bu ortamda üretilemedi" diye okunmalı. Gerçek soğuk ölçüm için bellek limiti
  çalışma setinden küçük bir makine ya da host'a erişim gerekir.
- **Toxiproxy her iki yolda:** Repsy PostgreSQL'e ve S3'e Toxiproxy üzerinden bağlanıyor (toxic yok).
  Dosya sistemi yolunda yalnızca DB sıçraması var, S3 yolunda DB + nesne deposu. Bu sıçramanın
  maliyeti ölçülmedi.
- **Aynı makine:** S3 sunucusu Repsy ile aynı host'ta ve ağ yok (loopback köprü). Gerçek bir nesne
  deposunun ağ gecikmesi (ms mertebesi, her istek için birden fazla HEAD) burada görünmüyor. S3'ün
  maliyeti burada **olduğundan az** ölçülüyor.

## Sonuçlar

- `load/run-matrix.sh` tek komutla matrisi koşar, `load/report.py` tabloyu ham JSON'lardan üretir.
  Makaledeki her sayı `results/baseline.md`'deki bir satırdan gelir, o satır da ham dosya adlarını
  listeler.
