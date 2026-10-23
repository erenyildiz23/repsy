# ADR-001: Depolama strateji arayüzü ve S3 implementasyonu

Tarih: 2026-10-23 · Durum: Kabul edildi

## Bağlam

Repsy'de artifact dosyaları `StorageStrategy` arayüzünün arkasında (`libs/storage/storage-gateway`).
Tek implementasyon `FileSystemStorageStrategy`; `StorageBackend.S3` enum değeri var ama
kullanılmıyor (`00-notlar.md` §1). Case study iki stratejiyi aynı yükte karşılaştırmak istiyor,
bu yüzden S3 implementasyonu yazıldı.

Arayüz referanstaki 5 metotluk `put/get/delete/list/exists` değil, 14 metot ve bunların bir kısmı
S3'te doğrudan karşılığı olmayan dosya sistemi kavramları: `append`, `renameObject`, yumuşak
`delete` + `clearTrash`, boş dizin, `listStaleFiles` (mtime).

## Karar

1. **Arayüz değişmedi.** Çağıranların (9 protokol modülü) hiçbiri dosyaya doğrudan dokunmuyor
   (`getFile()` ya da `Path` kullanımı yok), yalnızca arayüzü kullanıyor. Bu yüzden S3 tarafı
   arayüzün arkasına sığdırıldı. Arayüz değişseydi Repsy Cloud'a kadar uzanan bir sözleşme
   değişikliği olurdu (AGENTS.md "Change shared contracts on both sides").
2. **Sözleşme testi:** `AbstractStorageStrategyContractTest` (`storage-gateway` test-jar), 16 durum.
   `FileSystemStorageStrategyContractTest` (Docker'sız) ve `S3StorageStrategyContractIT` (Silo
   container'ı) aynı paketi çalıştırıyor. İkisi de geçiyor (`mvn verify -pl
   libs/storage/storage-gateway,libs/storage/storage-gateway-fs,libs/storage/storage-gateway-s3 -am`).
3. **Seçim:** `os.app.storage.backend` (`STORAGE_BACKEND`) = `file-system` (varsayılan) | `s3`.
   `StorageStrategyBeanRegistrar` (eski adı `FileSystemStorageBeanRegistrar`) her `RepoType` için
   seçilen implementasyonu kaydediyor. Bean adları değişmedi.
4. **S3 eşlemeleri** (`S3StorageStrategy` javadoc'unda da yazılı):

| Dosya sistemi | S3 karşılığı | Bedeli |
| --- | --- | --- |
| Geçici dosya + `ATOMIC_MOVE` | 8 MiB'a kadar tek `PutObject`, üstü multipart; hata olursa `AbortMultipartUpload` | Parça başına 8 MiB tampon, yükleme başına bir tampon |
| `append` (O_APPEND) | Eski nesne + yeni baytlar tek bir yüklemeyle yeniden yazılıyor | Her chunk nesnenin tamamı kadar trafik. Docker/Helm blob'u genelde tek chunk'ta geliyor; çok chunk'lı yüklemede maliyet O(n²) |
| `renameObject` (move) | `CopyObject` + `DeleteObject` | Atomik değil: arada çökme kaynağı bırakır (UUID adlı upload dosyası; abandoned-upload temizliği TTL sonra siler). `CopyObject` 5 GB ile sınırlı |
| Yumuşak silme (çöpe `move`) | `trash/<tip>/<gün>/<an>/<yol>` altına kopya, sonra silme | Önce kopyala sonra sil: yarıda kalırsa ikisi birden var, hiçbiri yok değil |
| `clearTrash` + 1 günlük geçici dosya temizliği | Eski günleri silme + 1 günden eski multipart yüklemeleri iptal etme | MinIO lifecycle `AbortIncompleteMultipartUpload` kuralını saklamıyor (REFERENCE §6, minio#19115). Bu yüzden lifecycle kuralına değil koda güveniliyor |
| Boş dizin | `<key>/` adında sıfır baytlık işaretçi | Listelemelerde gizleniyor |
| Disk `IOException` -> 503 | `SdkException` -> `StorageUnavailableException` -> 503 | Okumalar sarılmıyor (dosya sistemindeki gibi) |

5. **Zaman aşımları:** bağlantı 5 sn, soket (okuma) 30 sn, havuz 64 bağlantı (`S3_*`). Okuma zaman
   aşımı olmasaydı cevap vermeyen bir depo istek thread'ini sonsuza kadar tutardı. Bunu Toxiproxy
   `timeout` toxic'iyle Aşama 4'te deneyeceğim.

## Alternatifler

- **Arayüzü S3'e göre sadeleştirmek** (`append` yerine çok parçalı yükleme API'si): Docker/Helm
  protokol kodunu ve Cloud tarafını değiştirmek demek. Case study'nin konusu dışında.
- **Spring Content / MinIO Java SDK:** AWS SDK v2 her S3 uyumlu depoyla çalışıyor ve sunucuya bağlı
  değil (ADR-003: sunucu değişebilir).
- **`append`'i parça nesneleri olarak tutmak** (`<key>.parts/N`, okurken birleştirmek): `get`,
  `renameObject` ve usage hesabını karmaşıklaştırıyor; ölçülmüş bir ihtiyaç yok.

## Sonuçlar

- AWS SDK sürümü (`2.57.3`) `repsy-core`'un BOM'unda değil, modülün pom'unda sabit. Upstream'e
  taşınırsa sürüm core-bom'a gitmeli (AGENTS.md "repsy-core version").
- Paket adı: S3 sınıfları `io.repsy.libs.storage.gateway.s3` kök paketinde. `services`/`configs`
  katman paketleri yalnızca `*Service`/`*Config` adlı sınıfları kabul ediyor (`NamingArchRuleTest`)
  ve freeze listesi genişletilemiyor.
- Uçtan uca doğrulama (Silo, Toxiproxy arkasında): Maven 1 MiB ve 50 MiB jar `PUT` 200, 50 MiB
  `GET` bayt bayt aynı (`cmp`), `repo.disk_usage` = 53477581 (= 1048576 + 52428800 + 205),
  nesneler `repsy` bucket'ında `maven/<repo id>/...` altında, lokal disk boş.
- Ölçülmedi: Docker/Helm çok chunk'lı yüklemenin S3'teki maliyeti; >5 GB rename.
