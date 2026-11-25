# SUFFER SHADOW – Redeem kod doğrulama sunucusu

Uygulamadaki **REDEEM CODE** bölümü kodları yalnızca bu sunucuya sorar. Uygulama içinde kod listesi, "geçerli kod" kontrolü
ya da sahte başarı yoktur. **PUBG UC / oyun içi ödül kodları desteklenmez** (bunun için PUBG'nin resmi yetkili sistemi gerekir).

## Kurulum
```
ADMIN_TOKEN=<en az 24 karakter> CODE_PEPPER=<en az 16 karakter, sabit kalmalı> node server.js
```
TLS'i ters vekil (nginx/Caddy) sağlamalıdır. Sonra uygulamada `BoosterUi.kt > RedeemConfig.API_URL`
değerini `https://alan-adiniz/v1/redeem` yapın.

## Kod oluşturma (yönetim)
```
curl -X POST https://alan-adiniz/v1/admin/codes -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"rewardType":"session_minutes","value":60,"maxUses":1,"expiresAt":"2026-12-31T23:59:59Z"}'
```
- `value`: ödül dakikası (1–1440) · `maxUses`: toplam kullanım limiti · `expiresAt`: son kullanma · `code`: verilmezse rastgele üretilir.
- Kodlar diskte düz metin değil HMAC-SHA256 özeti olarak tutulur; düz metin yalnızca oluşturma yanıtında bir kez görünür.

## Uygulama API'si — `POST /v1/redeem`
İstek: `{"code","deviceId","nonce","appVersion","supports":["session_minutes"]}`
Yanıt (hep `nonce` geri yansıtılır; uygulama eşleşmeyen yanıtı reddeder):

| status | anlamı |
|---|---|
| `ok` + `reward:{type,value}` | kod kabul edildi, tüketildi |
| `invalid` | kod yok / biçim hatalı |
| `expired` | süresi dolmuş |
| `used` | kullanım limiti doldu ya da bu cihaz zaten kullandı |
| `unsupported` | ödül türü uygulama sürümünde yok — **kod harcanmaz** |
| `rate_limited` (HTTP 429) | fazla deneme (IP başına 30/dk, cihaz başına 20/10 dk, yanlış kod 5/10 dk) |

Yeni ödül türü eklemek: sunucuda `SUPPORTED` kümesine ve uygulamada `RedeemClient`'a ekleyin; eski sürümler `supports` listesi sayesinde güvenle reddedilir.

## Sınırlar
- Hız sınırı bellek içidir; birden çok sunucu örneğinde Redis gibi ortak depo kullanın. Depolama tek JSON dosyasıdır (küçük ölçek).
- 2 saatlik VPN süresi **istemci tarafında** uygulanır; değiştirilmiş bir APK bunu aşabilir. Gerçek zorlama için sunucunun
  süreli VPN kimlik bilgisi (ör. süreli UUID) üretmesi gerekir.
  
