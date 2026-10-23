# PM-002: Yük ölçümü sırasında disk doldu

Tarih: 2026-10-23 · Durum: Kapandı

## Özet

Başlangıç matrisi her backend'i tek bir stack'te koştu ve yüklenen dosyaları silmedi. 50 MB
yüklemeler (koşu başına ~6 GB) oturumun ~39 GB disk kotasını doldurdu. Son koşular `503` aldı,
50 MB indirme koşuları hiç başlamadı ve oturumun worker süreci yeniden başladı.

## Etki

- 8 koşu geçersiz (`results/raw/baseline-invalid-diskfull/`), ~45 dk ölçüm tekrarlandı.
- Ölçüm verisi kaybolmadı: her koşunun ham dosyası anında yazılıyor ve sık commit'leniyordu.

## Katkıda bulunan faktörler

- Disk bütçesi hesaplanmadan matris tasarlandı: 50 MB × 1/sn × 120 sn × 3 koşu × 2 backend.
- `df` bu ortamda yanıltıcı (kotayı değil makinenin diskini gösteriyor).

## Tespit

`k6` çıktısında `503` ve `setup upload 0 answered 503`. Repsy dolu diski doğru raporladı:
`StorageUnavailableException` → `503`, hiçbir istek yarım dosyayla `200` almadı. Bu, ölçülmemiş bir
disk-dolu arıza deneyi yerine geçti.

## Aksiyonlar

| Aksiyon | Durum |
| --- | --- |
| `run-matrix.sh`: `FRESH=1` ile her koşu taze stack, backend sonunda `down -v` | Yapıldı, `904daa4d` |
| 50 MB hücreleri aynı koşulda yeniden koş, eskileri silmeden ayır | Yapıldı |
