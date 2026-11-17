# Partypark2 Photo Backup v2 — Implementation Specification

## Safety invariant
Partypark may delete only files it created inside its private app storage (the Partypark backup directory). It must never delete, move, overwrite, or modify MediaStore/gallery originals. The source URI is used for detection and deduplication only.

## Capture and local staging
- Continue consent-gated MediaStore observation; do not import the existing gallery when backup is enabled.
- For each newly detected image, copy the source bytes into a private original file and create a separate compressed thumbnail file.
- Persist both private paths, original byte size, thumbnail byte size, source URI, filename, and capture timestamp in the local queue database.
- Do not delete the private original after thumbnail creation.
- A failed staging operation cleans up only the partial private files and leaves the gallery source untouched.

## Ordered upload pipeline
1. Upload every queued thumbnail first. A batch must not begin any original upload until all thumbnails in that batch have been confirmed committed by the server.
2. Once thumbnails are ready, originals are eligible only according to the owner's upload decision.
3. On successful original upload and server commit, delete that private original immediately.
4. If the original has not uploaded within seven days of capture, delete only the private original. Keep the cloud thumbnail.
5. Keep thumbnail and original state separate so an original can replace the thumbnail in the same cloud gallery record; never create a duplicate gallery item for the original.

## 100 MB decision threshold
- Calculate the threshold from the total bytes of pending private originals, not thumbnail bytes.
- At 100 MB or above, publish pending-original metadata to the backend for Watchnow2 to display the red decision panel.
- The thumbnail pipeline continues while the original queue is awaiting a decision.

## Owner decisions
- Upload Everything: authorize all pending originals; upload when the device can reach the backend.
- Cancel: discard all pending private originals; keep cloud thumbnails.
- Custom Upload: the owner selects photo IDs in Watchnow2 and commits the selection. Partypark retains only selected private originals and immediately deletes all unselected private originals. Selected originals remain queued for up to seven days from capture and upload when backend connectivity is available. After each successful original commit, delete that private original.
- Commands must be durable on the server so a device that is offline when the owner acts can retrieve the command later. Selection application must be idempotent and safe across app restarts.

## Connectivity and retry
- WorkManager network constraints are only a coarse gate, not proof of internet access.
- Before starting/retrying a network operation, Partypark must make a bounded request to the actual Partypark backend health/command endpoint and require a successful valid response.
- Airplane mode, disabled radios, captive/no-internet Wi-Fi, mobile data without internet, DNS failure, and backend unreachability must not cause upload retry loops.
- Pause on loss of backend reachability; resume from persisted state when connectivity returns. Upload chunks must remain resumable/idempotent.

## Local database migration
- Preserve existing queued thumbnail rows and upload history.
- Add original path/size and thumbnail upload status without discarding pending work.
- Treat legacy rows with no private original as thumbnail-only; do not try to reconstruct an original from a MediaStore URI because it may have been deleted or changed.
- Retain source URI deduplication.

## Logging and validation
- Log staging, thumbnail upload/commit, command receipt/application, original upload progress/commit, local private-file deletion, seven-day expiry, and backend reachability decisions.
- Tests must assert that no code path calls ContentResolver.delete or otherwise mutates MediaStore originals.
- Test offline command delivery, duplicate commands, partial uploads, process death, migration, and seven-day expiry.
