# 00 - Keşif notları (Aşama 1, salt okunur)

Kod tabanı: `repsyio/repsy` @ `76fcbcde` (2026-10-23). Her iddianın yanında kaynak dosya var.
REFERENCE.pdf'teki yer tutucu adlar (`/api/packages`, `artifact`, `storage_key`, `status`) Repsy'de
**yok**; gerçek adlar aşağıda.

## 0. Referansla çelişen temel gerçekler

| Referans varsayımı | Repsy'de gerçek | Kaynak |
| --- | --- | --- |
| Spring Boot 3.5.x, Java 21 | Spring Boot 4, Java 25 | `AGENTS.md` "Requirements" |
| PostgreSQL 16 | `postgres:18` zorunlu | `AGENTS.md` "Database" |
| İki strateji: dosya sistemi + MinIO | **Yalnızca dosya sistemi.** `StorageBackend` enum'unda `S3` adı var ama hiçbir yerde kullanılmıyor, implementasyonu yok | `libs/storage/storage-gateway/.../dtos/StorageBackend.java`, `grep -rn S3` |
| Tek bir `POST /api/packages` yükleme ucu | 9 paket formatı, her biri kendi wire protokolüyle (Maven `PUT`, npm `PUT` JSON, PyPI multipart, Docker OCI...) | `AGENTS.md` "Two request paths" |
| Tek `artifact` tablosu, `storage_key` sütunu | Format başına tablolar (`maven_artifact`, `maven_artifact_version`, `npm_package`, `npm_package_version`, `docker_layer`...). Depolama anahtarı = `repo.id` (UUID) + format yolu | `V0001__Initial_Schema.sql`, `RepoMapper.java:27` (`storageKey <- id`) |

Karar (kullanıcı onaylı): S3 implementasyonunu case study kapsamında biz yazacağız (Aşama 2).

## 1. Strateji soyutlaması

- Arayüz: `io.repsy.libs.storage.core.services.StorageStrategy` (`libs/storage/storage-gateway`).
  Yol tabanlı: `write`, `append`, `appendStream`, `renameObject(path, digest)`, `get`, `delete`
  (yumuşak: çöp kutusuna taşır), `clearTrash`, `listDirectoryContents`, `listStorageItems`,
  `listStaleFiles`, usage hesapları. 14 metot, referanstaki 5 metotluk `put/get/delete/list/exists`
  değil.
- Tek implementasyon: `FileSystemStorageStrategy` (`libs/storage/storage-gateway-fs`, 835 satır).
- Seçim: `FileSystemStorageBeanRegistrar` her `RepoType` için `osStorageStrategy<Format>` adlı bir
  bean kaydeder ve **her zaman** `new FileSystemStorageStrategy(...)` döner. Yapılandırmayla seçim
  yok. Yollar `os.app.storage.file-system.<type>.base-path|trash-path` (`application.yml:332+`).
- S3 için zorluklar (Aşama 2'de ele alınacak): `append`/`appendStream` (Docker/Helm parçalı
  yükleme), `renameObject` (S3'te rename yok: copy + delete), yumuşak silme ve çöp kutusu,
  `listStaleFiles` (mtime yerine `LastModified`), dizin kavramı.

## 2. Dosya sistemi yazımı: ne garanti ediliyor

`FileSystemStorageStrategy.write` (satır 297-338):

1. Aynı dizinde `.repsy-write-<uuid>.tmp` oluşturur, akışı oraya kopyalar (`transferTo`).
2. `Files.move(tmp, target, ATOMIC_MOVE)`; desteklenmezse `REPLACE_EXISTING` (satır 417-423).
3. Hata olursa geçici dosyayı siler. JVM ölürse geçici dosya kalır; `clearTrash` 1 günden eski
   olanları temizler (`ORPHANED_TEMP_FILE_AGE`).

**Eksik: `fsync` yok.** Ne `FileChannel.force(true)` ne dizin `force`. Atomik rename, okuyucunun yarım
dosya görmemesini sağlar ama güç kesintisinde (çekirdek çökmesi, VM reset) rename kalıcı olup
içerik olmayabilir: sıfır uzunluklu ya da çöp içerikli dosya. Bu ortamda güç kesintisi simüle
edilemez (bkz. Sınırlamalar); not olarak kalıyor, deneyle kanıtlanmayacak.

## 3. Yükleme yolları: DB ve depo yazım sırası

Üç format üç farklı sıra kullanıyor. Hepsi `ProtocolRouterController` (`/**`, port 9090) üzerinden
gelir.

### 3a. Maven `PUT /{repo}/{g}/{a}/{v}/{file}`: önce depo, sonra DB

`AbstractMavenUploadProtocolMethodHandler.handle` -> `AbstractMavenProtocolFacade.upload`
(`repsy-protocols/maven/.../AbstractMavenProtocolFacade.java:227-277`):

1. Kurallar (deploy rules, imza) depoya dokunmadan kontrol edilir.
2. `store(...)`: dosya depoya yazılır.
3. `register(...)`: `ArtifactDeploymentService.createOrUpdateArtifact` (satır 173). **Yalnızca POM**
   (ve doğrulanan imza) DB satırı yazar (`maven_artifact`, `maven_artifact_version`). Jar, checksum,
   `maven-metadata.xml` hiçbir satır yazmaz: onlar için doğruluk kaynağı depodur, indirme DB'ye
   sormadan depodan sunulur.
4. DB adımı hata verirse: dosya *yeni* bir POM/imzaysa `takeBack` ile silinir (satır 517-540);
   silme de başarısız olursa loglanır ve "depolanmış sayılır". Var olan dosyanın üstüne yazılan
   (redeploy) geri alınamaz.
5. İstemciye `200` döner (201 değil). Transaction yok: her DB yazımı kendi transaction'ında
   (`Propagation.SUPPORTS`, RPS-2176 notu).

Tutarsızlık pencereleri:

- 2 ile 4 arasında **süreç ölürse** (`SIGKILL`, OOM): POM depoda, `maven_artifact_version` yok.
  Maven istemcisi dosyayı indirebilir, panel sürümü göstermez. Bunu bulan ya da süpüren bir iş yok.
- `takeBack`'in kendisi başarısız olursa aynı durum, bu kez loglu.
- `repo.disk_usage` sayacı ayrı bir post-processor'da (`UsagePostProcessor`) güncellenir; o adımdan
  önce ölüm, sayaç ile diskteki baytlar arasında sapma bırakır.

### 3b. npm publish: önce satır, dosya aynı transaction içinde

`AbstractNpmProtocolFacade.publish` (satır 461-519) -> `NpmPackageService.publishVersion`
(`@Transactional`, satır 89-91), dosyaları callback içinde yazar (`storeVersion`, satır 526-571):

1. Paket satırı kilitlenir, sürüm satırları yazılır (henüz commit yok).
2. Callback içinde tarball ve paket metadata'sı depoya yazılır.
3. Yazım hata verirse `StoredUpload.storeOrDiscard` dosyaları siler, transaction geri alınır.
4. Callback döndükten sonra transaction commit edilir.

Tutarsızlık penceresi: **dosyalar yazıldı, commit başarısız** (DB bağlantısı commit anında koptu)
ya da 2 ile 4 arasında süreç öldü. `storeOrDiscard` yalnızca yazma adımının kendi istisnasında
silme yapar, commit hatasını görmez. Sonuç: depoda tarball, DB'de sürüm yok (sahipsiz). Kod bunu
biliyor: `noteOrphanedTarball` (satır 582-597) aynı sürüm tekrar yayınlanınca sahipsizi loglayıp
üzerine yazar; ama **süpüren bir iş yok**, tekrar yayınlanmayan sahipsiz kalıcıdır.

### 3c. Docker blob: Harbor vakasına Repsy'nin cevabı zaten var

- Parçalı yükleme UUID adlı geçici dosyaya `append` edilir, `PUT ?digest=` ile `renameObject`
  digest adına taşınır (`AbstractDockerStorageService.java:147`), sonra `docker_layer` satırı
  güncellenir (`AbstractDockerProtocolTxFacade.finalizeLayerUpload`, satır 133-142).
- Sahipsiz blob'lar için iki mekanizma var: `OrphanLayerService` (önce satırları siler, blob'ları
  commit'ten **sonra** siler, RPS-1318) ve `AbandonedBlobUploadCleanupService` (TTL'den eski UUID
  adlı yüklemeleri **ve satırı olmayan digest adlı blob'ları** siler, RPS-1172). Bu, Harbor
  #23199'daki "GC yalnızca DB'ye bakar" hatasının düzeltilmiş hali.
- Sonuç: Docker, referanstaki reconciler'ın (b) sınıfını zaten uyguluyor. Case study'de "zaten
  çözülmüş" olarak anılacak, yeniden yazılmayacak.

## 4. Silme

- Strateji düzeyinde yumuşak: `delete` dosyayı `<trash>/<gün>/<zaman>/<yol>` altına taşır
  (`FileSystemStorageStrategy.java:571-590`), `clearTrash` tutma süresinden eskileri siler
  (`os.app.storage.trash-cleanup`, varsayılan 24 saatte bir).
- npm ve Docker: önce satırlar, sonra dosyalar (yorumlar: RPS-1280, RPS-1318). Satır silinip dosya
  silinemezse dosya sahipsiz kalır (Docker'da süpürücü toplar, npm'de toplayan yok).

## 5. Gerçek uçlar ve tablolar (deneylerde kullanılacak)

| Şey | Gerçek değer |
| --- | --- |
| Protokol portu / panel API portu | 9090 / 8080 |
| Maven yükleme | `PUT http://localhost:9090/maven/<g yol>/<a>/<v>/<dosya>`, Basic auth (`admin`) |
| Maven indirme | `GET` aynı yol |
| Varsayılan repolar (seed) | `maven`, `npm`, `pypi`, `docker`, `cargo`, `go`, `helm`, `nuget`, `ruby` (`repo` tablosu) |
| Fiziksel yol | `/app/data/storage/<format>/<repo.id>/<yol>` |
| Maven satırları | `maven_artifact`, `maven_artifact_version` (yalnızca POM'dan) |
| npm satırları | `npm_package`, `npm_package_version` |
| Kullanım sayacı | `repo.disk_usage` (`CHECK >= 0`) |

Canlı stack üzerinde doğrulandı (2026-10-23): 1 MiB jar `PUT` -> 200, POM `PUT` -> 200, jar `GET` ->
200 / 1048576 bayt, `repo.disk_usage` = 1048781 (= 1048576 + 205 bayt POM),
`maven_artifact_version` = 1 satır.

## 6. Testler ve çalıştırma

- `*Test` birim (Docker'sız, `mvn test`), `*IT` Testcontainers (`mvn verify`, `postgres:18`).
  Her IT `AbstractIT`'yi genişletir; `CommittedRowsGuard` sızan satırı yakalar.
- Depolama testleri: `FileSystemStorageStrategyAtomicWriteTest`, `...WriteFailureTest`,
  `...UsageTest`, `...ListingTest`, `FileSystemStorageStrategyIT`. Arayüz düzeyinde bir sözleşme
  test paketi **yok**: Aşama 2'de bu testlerden türetilecek.
- Tek IT sınıfı komutu `AGENTS.md` "Testing" bölümünde. Kod stili: Google Java Format, Checkstyle,
  NullAway, RAT; yeni dosyalara Apache lisans başlığı gerekir.

## 7. docker-compose

- `e2e/docker-compose.stack*.yml`: `postgres:18` + kök Dockerfile'dan build edilen Repsy. Nesne
  deposu yok.
- Case study stack'i: `case-study/compose.yml` (Aşama 0, ADR-003).

## 8. Case study kapsamı için sonuç

1. **Soru 1 (FS ile S3):** S3 stratejisi yazılınca Maven `PUT`/`GET` ile ölçülecek (ham gövde,
   multipart yok; referans §7'deki "multipart çift yazım" tuzağı Maven'da yok).
2. **Soru 2-3 (tutarsızlık):** En ciddi aday **npm publish'te commit anında kopan DB**: depoda kalıcı
   sahipsiz tarball ve onu toplayan bir iş yok. İkinci aday Maven POM'da süreç ölümü. Docker zaten
   korunuyor, karşılaştırma için anılacak.
3. Referanstaki PENDING/COMMITTED tasarımı Repsy'nin şemasına birebir oturmuyor (tek `artifact`
   tablosu yok). Düzeltme, Repsy'nin mevcut desenine uyarlanacak: Docker'daki "satırı olmayan
   dosyayı TTL sonra süpür" yaklaşımının npm'e taşınması, dry-run ile. Karar ADR-002/004'te.

## Bilmediklerimiz

- S3 yazımında `UsagePostProcessor` ve çöp kutusu semantiğinin nasıl eşleneceği (Aşama 2).
- Silo'nun MinIO'dan ayrıştığı davranışlar, gözlemlenmedi.
- Commit anında bağlantı kopmasını Toxiproxy ile güvenilir biçimde zamanlamak mümkün mü (Aşama 5).
