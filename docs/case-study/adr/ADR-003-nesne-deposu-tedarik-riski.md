# ADR-003: Nesne deposu tedarik riski ve S3 sunucusu seçimi

Tarih: 2026-10-23 · Durum: Kabul edildi

## Bağlam

Case study, Repsy'nin dosya sistemi depolamasını bir S3 uyumlu nesne deposuyla karşılaştırıyor.
Varsayılan seçim MinIO'ydu. 2026-10-23'te bu ortamda denendi:

| İmaj | Sonuç | Ham çıktı |
| --- | --- | --- |
| `minio/minio:latest` (Docker Hub) | `pull access denied ... repository does not exist` | `case-study/results/raw/pull-minio_minio_latest.log` |
| `quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z` | `401 Unauthorized` (anonim çekme reddedildi) | `case-study/results/raw/pull-quay.io_minio_minio_RELEASE.2025-09-07T16-13-09Z.log` |
| `pgsty/silo:latest` | çekildi, `/minio/health/live` 200 döndü | aşağıdaki digest |
| `dxflrs/garage:v1.0.1` | çekildi, çalıştırılmadı | `sha256:a5706cf1...` |

REFERENCE.pdf §3'teki iki "DOĞRULA" maddesi (Docker Hub'dan kaldırılma, Quay'in anonim çekmeyi
reddetmesi) bu ortamda doğrulandı. Kaldırılma tarihini doğrulamadım, sadece imajın şu an yok olduğunu.

## Karar

`pgsty/silo` kullanılıyor, digest ile sabitlenmiş:
`pgsty/silo@sha256:2636fc4155d9a7e41ff58cdb65ddd13ba9304e67184e13c25bc5cb34016269d7`
(`org.opencontainers.image.version` = `RELEASE.2026-10-18T00-00-00Z`).

Gerekçe:

- MinIO kodundan türetilmiş (açılış logu "Copyright 2015-2025 MinIO, Inc. / Modifications 2025-2026
  PGSTY" diyor), `MINIO_ROOT_USER`/`MINIO_ROOT_PASSWORD` ve `/minio/health/live` aynı. Referanstaki
  `mc` komutları ve Testcontainers `MinIOContainer` büyük ihtimalle değişmeden çalışır (Testcontainers
  tarafı henüz denenmedi).
- Repsy'nin yazacağımız S3 implementasyonu yalnızca S3 API'sine (AWS SDK) bağlı olacak; sunucu
  değişirse kod değişmez, yalnızca imaj değişir.

## Alternatifler

- **Garage:** S3 uyumlu, aktif. Sağlık yolu ve yönetim modeli farklı (bucket/anahtar oluşturma
  ayrı CLI adımı), referanstaki MinIO komutları uymaz. Silo bozulursa yedek seçenek.
- **Kaynaktan MinIO build etmek:** arşivlenmiş bir kod tabanını kendimiz bakımla taşımak demek.
- **Quay'e kimlikli çekme:** kimlik bilgisini depoya koymamız gerekir, tekrarlanabilirliği bozar.

## Sonuçlar

- Silo AGPL-3.0; yalnızca test altyapısı olarak çalıştırılıyor, Repsy koduna bağlanmıyor.
- Silo'nun MinIO'dan ayrıştığı davranışlar (ör. çok parçalı yüklemelerin temizlenmesi, REFERENCE §6)
  gözlemlenmeden MinIO'ya genellenmeyecek. Makalede "MinIO" değil "Silo (MinIO fork'u)" yazılacak.
- Tüm imajlar digest ile sabit: `case-study/compose.yml`.
