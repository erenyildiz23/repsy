# ADR-005: Redis yalnızca metadata için, cache-aside, fail-open

Tarih: 2026-10-23 · Durum: Kabul edildi (case study, varsayılan kapalı)

## Bağlam

`npm install` her pakette önce paket metadata'sını (`package.json`) ister, Maven de
`maven-metadata.xml`'i. İkisi de her istekte depodan okunuyor: S3'te bu, her istekte bir `HEAD` ve
bir `GetObject` demek. Repsy'de Redis yok.

## Karar

- `CachingStorageStrategy`: herhangi bir `StorageStrategy`'nin önüne konan bir dekoratör
  (`libs/storage/storage-gateway-cache`). Yalnızca adı listede olan (`package.json`,
  `maven-metadata.xml`) ve 1 MB'tan küçük dosyalar önbelleğe alınıyor. 50 MB'lık blob'lar asla.
- Cache-aside: ıskada depodan oku ve önbelleğe koy. Bu dekoratörden geçen her yazma, append,
  rename ya da silme, depo çağrısından **sonra** ilgili anahtarı (dizinse altını da) siliyor.
  Yazma yolları zaten dekoratörden geçtiği için invalidasyon tek yerde.
- TTL 5 dk: kaybolan bir invalidasyonun en kötü bayatlık sınırı. Eşzamanlı okuyucu yarışı
  (eski veriyi silinmeden sonra yazması) da TTL ile sınırlı. Metadata için kabul edildi.
- Fail-open: Redis hatası ıska sayılıyor, istek depodan cevaplanıyor. Komut zaman aşımı 200 ms.
  Bağlantı tembel açılıyor, Repsy Redis olmadan da başlıyor.
- `STORAGE_CACHE=none|redis`, varsayılan `none`. Spring Data Redis starter yerine yalnızca Lettuce:
  Redis auto-configuration uygulamanın geri kalanında açılmıyor.

## Ölçüm (S3 backend, paket metadata okuma, 100 istek/sn, 90 sn, 50 paket)

| | p50 | p95 | p99 | hata | düşen | Kaynak |
| --- | --- | --- | --- | --- | --- | --- |
| Önbelleksiz | 9,1 | 21,7 | 41,0 | 0 | 0 | `results/raw/redis/s3-nocache.json` |
| Redis | 6,3 | 13,3 | 22,6 | 0 | 0 | `results/raw/redis/s3-redis.json` |
| Redis, ölçümün ortasında 30 sn `pause` | 7,1 | 419,5 | 436,9 | **0** | 1 | `results/raw/redis/s3-redis-outage.*` |

- p95 %39, p99 %45 düştü. İsabet oranı %99,6 (Redis `INFO`: 11 948 isabet, 54 ıska,
  `s3-redis-redis-info.txt`; ıskalar her paketin ilk okuması ve ısınma).
- 200 istek/sn'de önbelleksiz S3 yükü taşıyamadı (746 düşen), önbellekli neredeyse taşıdı (61).
  Bu koşular geçersiz, yalnızca eğilim: `results/raw/redis-rate200-overload/`.
- **Kesinti:** hiçbir istek başarısız olmadı (fail-open doğrulandı). Ama Redis donukken her istek
  ~400 ms bekledi: okuma da yazma da 200 ms zaman aşımına kadar bekliyor. Yani kesinti doğruluğu
  bozmuyor, gecikmeyi önbelleksiz durumun ~20 katına çıkarıyor.
- Ölçülmedi: dosya sistemi backend'i (orada okuma zaten page cache'ten geliyor), Maven metadata
  yolu, birden fazla instance.

## Sonuçlar ve sonraki adım

- Kesinti maliyeti için devre kesici: art arda N hatadan sonra Redis'i bir süre hiç sorma. Bu,
  kesintide p95'i önbelleksiz seviyeye (~22 ms) indirir. Uygulanmadı.
- Metadata önbelleği okumayı hızlandırıyor ama ADR-002'nin tutarlılık sorununa dokunmuyor.
  Önbellek, depodaki dosyayı olduğu gibi aynalıyor.
