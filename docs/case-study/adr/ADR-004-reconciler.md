# ADR-004: Süpürücü (reconciler) tasarımı

Tarih: 2026-10-23 · Durum: Önerildi, **uygulanmadı** (kullanım bütçesi)

## Bağlam

Süreç ölümü, dosya yazılıp satır yazılmadan önce gelirse sahipsiz dosya bırakıyor: S3'te bir
`SIGKILL` 10 Maven POM ve 2 npm tarball bıraktı (`results/raw/chaos/s3-repsy-kill.json`). ADR-002'nin
düzeltmesi bu vakayı kapsamıyor.

## Önerilen karar

Docker'ın `AbandonedBlobUploadCleanupService` desenini (RPS-1172) npm ve Maven'a genişletmek:

- `@Scheduled` iş, tekil kilitle (birden fazla instance için, ör. ShedLock ya da PostgreSQL advisory
  lock).
- **Dry-run varsayılan:** ilk sürümde yalnızca sayar ve loglar, silmez. Bir metrik üretir.
- **(a) Satırı olmayan dosya:** depodaki npm tarball / Maven POM, satırı yok ve `LastModified`
  T'den eski → sil (önce çöpe, `StorageStrategy.delete` zaten yumuşak).
- **(b) Dosyası olmayan satır (hayalet):** **silme.** Logla, metrik/alarm üret, insan karar versin.
  Satır kullanıcı verisidir (açıklama, yazar), dosya kaybı başka bir arızanın işareti olabilir.
- **T ≥ en uzun makul yükleme süresi**, başlangıç 1 saat. Daha kısası, süren bir yüklemenin
  dosyasını siler.
- **Usage sayacı:** silinen dosyanın baytı `repo.disk_usage`'dan düşülür. Kill senaryosundaki
  sapma (maven +1 046 937 B, npm −601 784 B) için ayrıca periyodik bir yeniden hesaplama gerekir.

## Neden uygulanmadı

Kullanıcı, kullanım bütçesi için test ve koşuları en azda tutmamı istedi. Bu iş yeni bir zamanlanmış
iş, dağıtık kilit ve yeni testler demek. Değeri, süreç ölümü vakasının ne sıklıkla olduğuna bağlı
ve bu ölçülmedi.

## Alternatifler

- MinIO/S3 lifecycle kuralı: nesnenin DB'de satırı olup olmadığını bilemez. Yalnızca yarım
  multipart yüklemeler için işe yarar (o da MinIO'da saklanmıyor, minio#19115).
