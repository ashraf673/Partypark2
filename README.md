# Partypark

**Partypark** is the Android sender app (HomeLook) for the Watchnow system.

It runs on the office/target phone and streams:
- Live camera (front/back)
- Live location
- Microphone audio

Connected to the **Watchnow** web app (receiver).

---

## Status

- Project structure created
- Firebase integration (next)
- Background service (next)
- WebRTC streaming (next)

---

## Build

This project is designed to be built with **Codemagic** (cloud Android builder).

## Automatic Photo Backup

Enable **Settings → Automatic photo backup** on the device. The feature requires the user to grant image access and explicitly opt in. It watches for newly added images after the opt-in timestamp; it does not import the pre-existing gallery. Screenshots saved as images in MediaStore are included.

- Originals are copied into Partypark's private app storage while offline.
- A durable SQLite queue survives process restarts.
- WorkManager uploads originals when network connectivity is available.
- Local unuploaded copies expire after 20 days, including while offline.
- A local copy is removed after both the Storage upload and Realtime Database metadata write succeed.
- No application-defined storage-size cap is applied.
- Disable the switch to stop future photo capture/backup.

The cloud record uses `media/{deviceId}/originals/{photoId}` in Firebase Storage and `users/{ownerUid}/devices/{deviceId}/media/{photoId}` in Realtime Database. Watchnow2's scheduled backend removes cloud copies and metadata after 15 days. Deploy the Watchnow2 Cloud Function and configure Firebase Storage/Realtime Database rules before production use.
