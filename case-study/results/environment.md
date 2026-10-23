# Ortam

Bu makinede ölçülen her sayı **göreli karşılaştırma** içindir: paylaşımlı bir bulut konteyneri,
gerçek ağ yok, disk overlayfs. Üretim kapasitesi olarak okunmamalı.

| Alan | Değer |
| --- | --- |
| date | 2026-10-23T08:26:33Z |
| kernel | 6.18.44-fc-v153 |
| cpu | Intel(R) Xeon(R) Processor @ 2.10GHz x 4 |
| mem_total | 15Gi |
| docker | 29.9.1 |
| compose | 5.6.0 |
| storage_driver | overlayfs |
| jdk_build | openjdk version "25.0.5" 2026-10-20 LTS |
| repsy_upstream_commit | 76fcbcde |

## Kaynak limitleri (case-study/compose.yml)

| Servis | İmaj | CPU | Bellek |
| --- | --- | --- | --- |
| repsy | repsy-cs:local (eclipse-temurin:25-jre-alpine, digest pinli) | 2 | 1g |
| postgres | postgres:18 (digest pinli) | 1 | 512m |
| s3 | pgsty/silo RELEASE.2026-10-18T00-00-00Z (digest pinli) | 1 | 512m |
| toxiproxy | ghcr.io/shopify/toxiproxy:2.12.0 (digest pinli) | 0.5 | 128m |

Makinede 4 çekirdek var; servis limitleri toplamı 4.5. k6 aynı makinede çalışacak, bu yüzden yük
koşularında k6'nın CPU'su ayrıca kaydedilecek (REFERENCE §9 madde 5).

## Bilinen sapmalar

- Repsy imajı kök `Dockerfile` ile değil, `case-study/docker/Dockerfile` ile üretiliyor: kök
  Dockerfile build içinde npm/Maven indiriyor ve bu ortamın çıkış proxy'si build konteynerinden
  erişime izin vermiyor (`raw/docker-build-root-dockerfile.log`). Jar host'ta JDK 25 ile
  `case-study/build.sh` üzerinden üretiliyor; panel SPA'sı imajda yok.
- Uygulama PostgreSQL'e ve S3'e Toxiproxy üzerinden bağlanıyor (toxic yokken ek bir TCP sıçraması).
  Fs/S3 karşılaştırmasında her iki koşu da aynı yoldan geçtiği için adil; mutlak gecikmeye küçük bir
  ek yük katıyor, ölçülmedi.
