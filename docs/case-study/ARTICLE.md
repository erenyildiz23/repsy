# Repsy: depolama, ölçek ve arıza

Repsy açık kaynak bir paket deposu: Maven, npm, Docker ve 6 format daha, Spring Boot 4, Java 25,
PostgreSQL 18. Bu çalışma dört soru soruyor: (1) dosya sistemi ile nesne deposu aynı yükte nasıl
davranıyor, (2) bilerek arıza yaratınca ne bozuluyor, (3) en ciddi tutarsızlığı nasıl düzelttik ve
nasıl kanıtladık, (4) metadata okumalarında Redis önbelleği ne kazandırıyor. Her sayının yanında
geldiği ham dosya var. Kod: `erenyildiz23/repsy`, branch `case-study`.

## Bağlam

- Repsy'de nesne deposu desteği yoktu: `StorageStrategy` arayüzünün tek implementasyonu dosya
  sistemi (`00-notlar.md` §0). Karşılaştırma için bir **S3 implementasyonu yazdım**. Arayüz
  değişmedi, iki implementasyon da aynı 16 durumluk sözleşme testini geçiyor (ADR-001).
- MinIO'nun imajı Docker Hub'dan kalkmış, Quay anonim çekmeyi reddediyor. Yerine MinIO fork'u
  Silo'yu digest ile sabitledim (ADR-003, `results/raw/pull-*.log`).
- Ortam: 4 çekirdekli bir bulut VM'i, çekirdekler servislere sabitlendi (Repsy 0-1, DB/S3 2, k6 3).
  Sayılar bu makinede **göreli** karşılaştırma (`results/environment.md`, ADR-006).

## Problem

DB ile depo arasında atomik işlem yok. Kod okuması üç farklı yazma sırası gösterdi (`00-notlar.md`
§3): Maven önce dosyayı yazıyor sonra kaydediyor, npm satırı önce yazıp dosyayı aynı
transaction'da yazıyor, Docker sahipsiz blob'ları zaten süpürüyor (Harbor #23199'un düzeltilmiş
hali). Bu, iki pencere bırakıyor: süreç dosya ile satır arasında ölürse, ve commit dosyadan sonra
başarısız olursa.

## Kararlar ve gerekçeleri

| Karar | Neden | Kaynak |
| --- | --- | --- |
| S3'ü arayüzün arkasına sığdır, arayüzü değiştirme | 9 protokol ve Repsy Cloud aynı arayüzü kullanıyor. `append`/`rename`/çöp kutusu S3'te taklit edildi, bedelleri yazılı | ADR-001 |
| PENDING/COMMITTED sütunu yok, saga/outbox yok | Tek servis, tek DB. Repsy'nin deseni transaction sınırı. Eksik olan commit hatası penceresiydi, Helm'in çözümü npm'e taşındı | ADR-002 |
| Süreç ölümü için süpürücü: tasarlandı, uygulanmadı | Dry-run, hayalet satırı asla silme, T ≥ 1 saat. Bütçe nedeniyle sonraya kaldı | ADR-004 |
| Redis yalnızca küçük metadata için, fail-open | Dekoratör iki backend'i de sarıyor, protokol kodu değişmedi | ADR-005 |

## Ölçülen sonuç: aynı yük, iki strateji

Medyan p50 / p99 (ms), 3 koşu, açık yük modeli, hiçbir sayılan koşuda hata ya da düşen iterasyon
yok (`results/baseline.md`, ham: `results/raw/baseline/`).

| | dosya sistemi p50 / p99 | S3 (Silo) p50 / p99 |
| --- | --- | --- |
| Yükleme 1 KB @50/sn | 9 / 24 | 21 / 158 |
| Yükleme 1 MB @20/sn | 12 / 335 | 34 / 88 |
| Yükleme 50 MB @1/sn | 98 / 550 | 578 / 1735 |
| İndirme 1 KB @100/sn | 3 / 10 | 6 / 21 |
| İndirme 1 MB @50/sn | 6 / 14 | 12 / 40 |
| İndirme 50 MB @2/sn | 101 / 258 | 161 / 350 |

- S3 her hücrede daha yavaş. En büyük fark büyük yüklemede: 50 MB'da medyan ~6 kat. Sebep:
  Maven yolu yazmadan önce birkaç HEAD/exists çağrısı yapıyor ve her biri S3'e bir gidiş-dönüş.
  50 MB, 8 MiB'lık parçalarla multipart olarak gidiyor.
- Bir istisna: 1 MB yüklemede p99 dosya sisteminde daha kötü (335 / 88 ms), üç koşuda da
  tutarlı. Sebebini ölçmedim.
- S3 sunucusu aynı makinede ve arada ağ yok, yani gerçek bir nesne deposunda fark **daha büyük**
  olur (ADR-006).
- Soğuk önbellek denendi ama bu VM'de üretilemedi: host'un disk önbelleği erişim dışında
  (ADR-006, `download_cold` satırları).

## Arıza ve post-mortem

5 senaryo, düzeltmeden önceki imajla (`results/chaos.md`):

| Senaryo | İstemci | Sonra |
| --- | --- | --- |
| S3 bağlantısı 5 MB'da kesildi | `503` | tutarlı, yarım nesne yok |
| PG bağlantıları RST | Maven `500` 0,6 sn, npm `500` **30 sn** | tutarlı |
| S3 cevap vermiyor | **120 sn+ cevap yok** | tutarlı |
| Repsy `SIGKILL` (S3) | 13 istek bağlantı koptu | **10 POM satırsız, 2 npm tarball satırsız**, usage sapması |
| Repsy `SIGKILL` (fs) | istekler öldürmeden önce bitti | tutarlı |

**En ciddi tutarsızlık (PM-001):** commit'i başarısız olan npm publish'i depoda tarball
bırakıyordu ve paket metadata'sını değiştirmiş olabiliyordu. `npm install` bu dosyayı okuduğu için
istemcilere hayalet bir sürüm görünebilir. Kanıt: gerçek bir PostgreSQL commit hatası (ertelenmiş
constraint trigger, mock yok) ile iki test önce **kırmızı**
(`results/failures/npm-commit-failure-red.txt`). Helm'in `afterCompletion` geri alma deseni npm'e
taşındı. Commit sonucu bilinmiyorsa DB'ye soruluyor. Sonra sınıfın 14 testinin hepsi **yeşil**
(`...-green.txt`, commit `610efa20`).

Disk dolması da yaşandı (PM-002): ölçüm verisi diski doldurdu. Repsy dolu diske `503` ile cevap
verdi, hiçbir istek yarım dosyayla `200` almadı.

## Redis

S3 backend'de paket metadata okuması, 100 istek/sn (ADR-005, `results/raw/redis/`):

| | p50 | p95 | p99 | hata |
| --- | --- | --- | --- | --- |
| Önbelleksiz | 9,1 | 21,7 | 41,0 | 0 |
| Redis (isabet %99,6) | 6,3 | 13,3 | 22,6 | 0 |
| Redis 30 sn donduruldu | 7,1 | 419,5 | 436,9 | 0 |

Önbellek p95'i %39 düşürdü. 200 istek/sn'de önbelleksiz S3 yükü taşıyamadı, önbellekli neredeyse
taşıdı (`results/raw/redis-rate200-overload/`, geçersiz koşular, yalnızca eğilim). Redis kesintisinde
hiçbir istek başarısız olmadı (fail-open), ama her istek iki zaman aşımı (~400 ms) bekledi. Bir
devre kesici bu maliyeti kaldırır, uygulanmadı.

## Farklı yapsaydım

- Matrisin disk bütçesini önceden hesaplardım (PM-002) ve kırmızı testi makine boşken yazardım.
- S3 istemcisine toplam çağrı süresi sınırı (`apiCallTimeout`) koyardım: soket zaman aşımı tek
  başına asılı bir depoyu 120 sn'nin altına indirmedi (`raw/chaos/s3-s3-hang.txt`).
- Süpürücüyü (ADR-004) uygulardım: süreç ölümü vakası hâlâ açık.

## Sınırlamalar

- Tek makine, ağsız S3, sıcak önbellek. Arıza deneyleri 5 senaryo ve tek koşu.
- `row_without_pom=v2` (kill senaryosu) tekrar üretilemedi, sebebi bilinmiyor.
- Düzeltmenin süreç ölümü vakası için "sonra" arıza koşusu yapılmadı, çünkü düzeltme o vakayı
  kapsamıyor. Kanıt deterministik testtir.
- Repsy Cloud'da aynı kod olup olmadığına bakılamadı (erişim yok).
