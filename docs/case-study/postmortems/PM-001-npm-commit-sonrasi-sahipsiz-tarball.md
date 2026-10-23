# PM-001: Commit'i başarısız olan npm publish'i depoda tarball bırakıyor

Tarih: 2026-10-23 · Yazar: case study (Claude Code, Eren Yıldız ile) · Durum: Düzeltildi (commit
`610efa20`), süreç ölümü vakası açık

## Özet

npm publish sürüm satırlarını yazıp dosyaları aynı transaction içinde yazıyor. Dosyalar yazıldıktan
sonra commit başarısız olursa satırlar geri alınıyor ama tarball ve paket metadata'sı depoda
kalıyordu. Düzeltme, Helm'in zaten kullandığı `afterCompletion` geri alma desenini npm'e taşıdı.

## Etki

- Deterministik test (`NpmPublishStorageConsistencyIT#failedCommit*`): commit reddedilen her
  publish için 1 sahipsiz tarball, yeni sürümde paket metadata'sı da değişmiş halde kalıyordu.
  İstemci `500` görüyor (`results/failures/npm-commit-failure-red.txt`).
- Arıza deneyinde (S3, Repsy `SIGKILL`) aynı pencereye düşen 2 publish sahipsiz tarball bıraktı ve
  npm usage sayacı depodaki bayttan 601 784 B az kaldı (`results/raw/chaos/s3-repsy-kill.json`).
  Bu ikinci vaka (süreç ölümü) düzeltmenin kapsamında değil.
- Kendiliğinden düzelme: aynı sürüm tekrar yayınlanırsa `noteOrphanedTarball` sahipsizin üzerine
  yazar. Tekrar yayınlanmazsa sahipsiz kalıcı, onu toplayan bir iş yok.

## Tetikleyici ve katkıda bulunan faktörler

- Tetikleyici: commit anında DB hatası (bağlantı kopması, ertelenmiş constraint) ya da dosyalar
  yazıldıktan sonra süreç ölümü.
- `StoredUpload.storeOrDiscard` yalnızca yazma adımının kendi istisnasında temizlik yapıyordu.
  Commit callback döndükten sonra olduğu için görünmüyordu.
- Spring commit hatalarının çoğunu `STATUS_UNKNOWN` olarak bildiriyor: sonucu bilmeden silmek,
  aslında commit olmuş bir sürümün dosyasını silmek olabilir.
- Aynı sorun Helm'de çözülmüştü (`HelmOciManifestFacade`), npm'e taşınmamıştı. AGENTS.md'nin
  "protokoller arası bak" ilkesinin tek repo içindeki karşılığı.

## Zaman çizelgesi (2026-10-23, UTC)

- 08:3x Keşif (`00-notlar.md` §3b): pencere kod okunarak bulundu.
- 09:2x Kırmızı test yazıldı (gerçek PostgreSQL commit hatası: `DEFERRABLE INITIALLY DEFERRED`
  constraint trigger, mock yok), makine yük ölçümüyle meşgul olduğu için çalıştırılmadı (`0bf615ad`).
- 14:0x Arıza deneyi: S3 + `SIGKILL` 2 sahipsiz tarball üretti.
- 14:1x Kırmızı test çalıştı: 2/2 kırmızı. Düzeltme yazıldı, sınıf 14/14 yeşil.

## Tespit

Üretimde fark edilmezdi: istemci `500` görüp tekrar dener, tekrar denenmezse yalnızca depo
şişer ve usage sayacı sapar. Kodu okuyarak bulundu, sayım aracı (`chaos/check.py`) kanıtladı.

## Ne iyi gitti / ne kötü gitti / nerede şanslıydık

- İyi: Repsy'nin mevcut test altyapısı (`NotSupported` transaction, gerçek PostgreSQL) gerçek bir
  commit hatasını mock'suz üretmeyi mümkün kıldı.
- Kötü: kırmızı test makine meşgulken yazıldı ve saatlerce çalıştırılamadı.
- Şanslı: Helm'de hazır bir desen vardı.

## Aksiyonlar

| Aksiyon | Öncelik | Durum |
| --- | --- | --- |
| Commit edilmeyen publish'in dosyalarını geri al, sonuç bilinmiyorsa DB'ye sor | Yüksek | Yapıldı, `610efa20` |
| Süreç ölümü için dry-run modlu süpürücü (satırı olmayan, T'den eski tarball) | Orta | Açık, ADR-004 |
| Aynı kontrolü diğer "önce satır sonra dosya" protokollerinde yap (PyPI, Cargo, NuGet, Ruby) | Orta | Açık |
| Repsy Cloud'da aynı kod var mı bak (AGENTS.md) | Orta | Açık, erişim yok |
