# Arıza matrisi (Aşama 4, düzeltmeden önce)

Kullanım bütçesi için 5 senaryo koşuldu (`chaos/run.sh`). Her biri taze stack'te, düzeltmeden önceki
imajla. Ham çıktılar `raw/chaos/<backend>-<senaryo>.{txt,json}`. Kontrol: `chaos/check.py`.

| Senaryo | Strateji | İstemci ne gördü | Sonra tutarsızlık | Kaynak |
| --- | --- | --- | --- | --- |
| PG bağlantıları RST (`reset_peer`) | fs | Maven jar/POM `500` (0,6 sn); npm publish `500` **30 sn** sonra (Hikari bağlantı zaman aşımı) | yok | `raw/chaos/fs-pg-reset.*` |
| Repsy `SIGKILL` | fs | tüm istekler öldürmeden önce bitti (yerel disk hızlı), öldürme boşa düştü | yok (`jar_without_pom=v1` senaryonun kendi jar'ı, POM'u hiç gönderilmedi) | `raw/chaos/fs-repsy-kill.*` |
| Repsy `SIGKILL` | s3 | 7 istek `200`, 13 istek bağlantı koptu (`000`), 50 MB jar yarıda | **10 POM depoda, sürüm satırı yok**; **2 npm tarball satırsız** (`kill9`, `kill10`); usage sapması maven +1 046 937 B, npm −601 784 B; 1 yarım multipart yükleme; `row_without_pom=v2` (aşağıya bakın) | `raw/chaos/s3-repsy-kill.*` |
| S3 bağlantısı 5 MB sonra kesildi (`limit_data`) | s3 | `503` (0,7 sn) | yok, yarım nesne yok | `raw/chaos/s3-s3-cut.*` |
| S3 cevap vermiyor (`timeout 0`) | s3 | **120 sn'de cevap yok** (curl `-m 120` kesti) | yok | `raw/chaos/s3-s3-hang.*` |

## Doğrulamalar (aynı gün, ayrı bir S3 stack'i)

- **Maven yetim POM tekrar denemede düzeliyor:** satırı olmayan bir POM depoya doğrudan konup aynı
  POM tekrar `PUT` edildi: `200`, sürüm satırı oluştu, tutarsızlık kalmadı. Yani bir istemci
  yeniden denerse kendiliğinden düzeliyor; denemezse POM indirilebilir ama panelde görünmez.
- **`row_without_pom=v2` tekrar üretilemedi:** taze stack'te `v2` jar+POM deploy'u tutarlı çıktı.
  Kill senaryosundaki bu satırın sebebini bilmiyorum (yeniden başlatma sonrası ilk istekler veya
  kontrol aracı olabilir). Açık soru olarak duruyor.

## Bulgular

1. **Süreç ölümü iki protokolde de sahipsiz dosya bırakıyor** ve onları toplayan bir iş yok
   (Docker hariç, RPS-1172). Tekrar denemede düzeliyor, denenmezse kalıcı.
2. **Commit anında DB hatası npm'de sahipsiz tarball bırakıyor**: deterministik olarak kanıtlandı,
   `failures/npm-commit-failure-red*.txt` (Aşama 5).
3. **Asılı S3 isteği 120 sn'den uzun sürüyor**, soket zaman aşımı 30 sn olmasına rağmen. Muhtemel
   sebep: SDK'nın tekrar denemeleri (3 deneme) ve Maven yolunun birden fazla S3 çağrısı yapması
   (HEAD, exists, put), her biri ayrı ayrı zaman aşımına uğruyor. Toplam çağrı süresi sınırı yok.
4. **DB'ye ulaşılamazken npm publish 30 sn bekliyor**, Maven 0,6 sn'de düşüyor: npm transaction'ı
   bağlantı havuzundan bağlantı beklerken tıkanıyor.
