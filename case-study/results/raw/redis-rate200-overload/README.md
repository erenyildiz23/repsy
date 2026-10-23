# Redis ölçümü, 200 istek/sn: geçersiz (aşırı yük)

`load/run-redis.sh` (RATE=200). Üç koşuda da `dropped_iterations > 0` (önbelleksiz 746, Redis 61,
Redis + kesinti 539), yani ADR-006'nın kuralına göre geçersiz. Rakamlar yalnızca eğilim için:
önbelleksiz S3 bu hızı taşıyamadı (en çok düşen), önbellekli neredeyse taşıdı. Hiçbir koşuda hatalı
istek yok. Geçerli ölçüm aynı betikle RATE=100'de: `../redis/`.
