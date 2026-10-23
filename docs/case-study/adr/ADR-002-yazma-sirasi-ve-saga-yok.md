# ADR-002: Yazma sırası ve neden saga/outbox yok

Tarih: 2026-10-23 · Durum: Kabul edildi

## Bağlam

DB ile depo arasında atomik işlem yok. REFERENCE §4, PENDING/COMMITTED satır durumları ile bir
reconciler öneriyor. Repsy'de tek bir `artifact` tablosu yok. Her protokolün kendi sırası var
(`00-notlar.md` §3):

- **Maven:** önce depo, sonra kayıt. Yalnızca POM satır yazıyor, hata olursa yeni POM geri alınıyor.
- **npm:** önce satır (kilitli), dosya aynı transaction içinde, sonra commit.
- **Docker:** blob önce, satır sonra. Satırsız blob'ları TTL sonrası süpüren bir iş var (RPS-1172).
- **Helm:** npm gibi, ayrıca commit edilmezse dosyayı geri alan bir `afterCompletion` hook'u var.

## Karar

1. **PENDING/COMMITTED durum sütunu eklenmedi.** Repsy'nin mevcut deseni "satırı olmayan dosya
   yoktur" kuralını transaction sınırlarıyla sağlamak. Npm ve Helm satırı dosyadan önce, kilit
   altında yazıyor. Eksik olan, commit'in başarısız olduğu durumdu (PM-001). Bunu kapatmak için
   ikinci bir durum makinesi yerine Helm'in hook'u npm'e taşındı (`610efa20`). Bu, 9 protokolün
   şemasına ve okuma sorgularına dokunmayan en küçük değişiklik.
2. **Saga/outbox yok.** Tek servis, tek DB, tek depo var ve işlemleri dağıtık değil. Telafi
   adımı (dosyayı silmek) aynı süreçte, aynı isteğin içinde yapılabiliyor. Outbox'ın çözdüğü sorun,
   yani mesajın DB commit'iyle birlikte kaybolmadan yayınlanması, burada yok: dosya yazımı mesaj
   değil ve ayrı bir tüketici yok. Saga'nın getireceği şeyler (kalıcı adım günlüğü, yeniden deneme
   işçisi, idempotent telafi) karmaşıklık ekler ve süreç ölümü vakasını yine bir süpürücü olmadan
   çözmez.
3. **Süreç ölümü için cevap süpürücü** (ADR-004): DB'yi doğruluk kaynağı sayıp "satırı olmayan,
   T'den eski dosyayı sil" kuralı. Docker bunu zaten yapıyor.

## Alternatifler

- **PENDING/COMMITTED + reconciler (REFERENCE §4):** okuma API'sinin her sorgusuna `status =
  'COMMITTED'` filtresi demek. 9 protokolde onlarca sorgu, Cloud ile paylaşılan sözleşmeler ve
  migration. Kazanç: süreç ölümü vakası da kapanır. Bedeli case study kapsamını aşıyor.
- **Önce dosya, sonra satır (Maven'ın sırası):** sahipsiz dosya üretir ama hayalet satır üretmez.
  npm'de paket metadata dosyası istemcilere görünür olduğu için bu sıra hayalet sürüm üretebilir.

## Sonuçlar

- Commit hatası npm'de artık tutarsızlık bırakmıyor (14/14 test).
- Süreç ölümü hâlâ sahipsiz dosya bırakıyor (Maven POM, npm tarball). Tekrar denemede düzeliyor
  (Maven doğrulandı: `results/chaos.md`), denenmezse süpürücü gerekir.
