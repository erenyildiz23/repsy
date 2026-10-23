# Disk dolduğu için geçersiz koşular

2026-10-23, ilk matris (`load/run-matrix.sh`, commit 55458966): her backend tek bir stack'te
koşuldu, yüklenen dosyalar silinmedi. 1 MB (20/sn) ve 50 MB (1/sn) yüklemeleri oturumun disk
kotasını (~39 GB) doldurdu.

- `fs-upload-50mb-run3`, `s3-upload-50mb-run3`: isteklerin %38 / %50'si `503` (Repsy'nin
  `StorageUnavailableException` cevabı: depolama yazılamıyor, `Retry-After` ile).
- `{fs,s3}-download-50mb-run{1,2,3}`: `setup()` yüklemesi `503` aldı, ölçüm hiç başlamadı.

Bu dosyalar performans ölçümü olarak kullanılmıyor. Aynı hücreler `FRESH=1` ile (her koşu taze
stack'te, öncekinin yüklemeleri silinmiş) yeniden koşuldu. Geçerli olan eski 50 MB yükleme
koşuları 1-2 de tutarlılık için yeniden koşuldu; eskileri `../baseline-superseded/` altında.

Yan bulgu: disk dolduğunda iki strateji de istemciye `503` döndü ve hiçbir istek yarım dosyayla
200 almadı. Bu, Aşama 4'teki disk-dolu senaryosunun ölçülmemiş bir ön gözlemi.
