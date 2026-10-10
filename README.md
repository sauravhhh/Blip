# Blip

Offline Bluetooth mesh chat between phones. No internet, no accounts, no servers.

Every phone can connect to several others at once. Each message carries a
unique id and a hop limit — a phone that receives a new message relays it to
all its other peers, so texts hop phone-to-phone across the mesh and reach
devices beyond direct Bluetooth range.

## How to use

1. Install the APK on all phones.
2. Turn on Bluetooth on all of them.
3. One phone taps **Host** (becomes visible for 5 minutes).
4. The others tap **Scan**, then tap phones in the device list to connect.
   Tapping an already-connected phone disconnects it.
5. Type and send — messages flood across every connected phone.

## Details

- Package: `com.sauravhhh.blip`
- Min Android 8.0 (API 26), targets Android 14
- Bluetooth Classic (RFCOMM), JSON message protocol with id/TTL dedup
- ~25 KB

## Download

[blip-v2.0.apk](https://cdn.jsdelivr.net/gh/sauravhhh/Blip@main/releases/blip-v2.0.apk)

## Web version

A browser version (same chat over local WiFi/hotspot via WebRTC, since
browsers can't do phone-to-phone Bluetooth) lives in `docs/` and is served
at https://sauravhhh.github.io/Blip/.
