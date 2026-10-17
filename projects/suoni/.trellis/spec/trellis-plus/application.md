# Sony Controller Application Boundaries

## Current Evidence and Decisions

Approved direction: an independently developed Sony device controller APK, using Kotlin and Flask. No active application source tree, Flask manifest, API contract, database, CI or supported-device matrix exists yet. Do not present an inferred architecture as already approved.

Reference sources are `archive/analyse/docs/sony-mdr-protocol.md`, `archive/analyse/sony_mdr.py`, and `archive/SonyMdrTile/`. They are retained reference implementations, not the new application's build roots. Do not edit or move them during initialization. The original Sound Connect APK is research input and must not become an app resource or build dependency.

Proposed boundary for the first implementation: Kotlin owns on-phone Bluetooth and native UI; Flask provides a development/laboratory API or simulator. Whether Flask is required at runtime, hosts Bluetooth itself on Linux, or supports another use case needs a user decision. Do not require a remote server for basic headphone control based only on the chosen language stack. Jetpack Compose is a candidate for the new UI, not an established project dependency.

## Protocol Contracts to Carry Forward

- Determine v1/v2 from the protocol handshake and supported features, not a model-generation name. The analysis's §12 records WH-1000XM4 as v1; this overrides earlier statements grouping all XM4 models as v2. §10–11 record WF-1000XM4 and WF-1000XM6 as v2. These are archived observations, not tests rerun in this session or guarantees for every firmware.
- The archived protocol query fixture is `3E 0C 00 00 00 00 02 00 00 0E 3C`. Preserve the frame markers, interior escaping, big-endian length and pre-escape additive checksum described in §3. New parsers must reject inconsistent lengths and malformed frames with bounded buffering; do not copy permissive prototype parsing without review.
- Keep framing separate from transport/session/commands. Reference examples: `MdrProtocol.kt` for frame bytes, `MdrSession.kt` for ACK and retransmission, and `SonyMdr.kt` for commands. Python counterparts are `build_frame`, `parse_frame`, `MdrSession`, `SonyMdr` and `SonyMdrV1` in `sony_mdr.py`. Treat these as behavior references, not code automatically licensed for redistribution.
- DATA packets need ACK handling and duplicate suppression; SHOT notifications do not. Preserve incoming notifications, serialized writes, bounded waits/retries, and cleanup on cancellation or disconnect. An ACK alone is not evidence that a requested setting took effect; use the corresponding response, notification or readback.
- Discover RFCOMM services through device UUID/SDP; channel 9 is an archived observation, not a universal constant. The Kotlin prototype handles normal and byte-reversed service UUIDs. BLE GATT is a separately validated future transport, not an assumed working fallback.
- Query supported functions and parameter ranges. §11 documents false NCASM capability responses on WF-1000XM6: use function mapping and GET_PARAM validation rather than trusting GET_CAPABILITY alone. Preserve unrelated fields when applying NC/ASM read-modify-write updates.
- §12 corrects §8's v1 EQ assumption: measured WH-1000XM4 band bytes are unsigned step indices, not signed dB. Derive the offset/range from capabilities. Do not extrapolate one device's number of bands, volume range or NCASM layout to all models.
- WH-1000XM4's MDR volume query is documented as unsupported; an Android system-volume path is a separate implementation choice. Unknown/unsupported battery, case and control values must remain distinguishable from zero, off or successful writes.

## Kotlin Conventions for New Code

Keep the first app small. Separate byte parsing, Bluetooth I/O/session ownership, typed device state and UI responsibilities; do not duplicate a session per screen or tile. Use coroutines and structured cancellation for blocking I/O outside the main thread. Reference `HeadsetManager.kt`'s serialization pattern, but review its error handling rather than copying it wholesale.

Represent connection, permission, unsupported, loading, error and confirmed device state explicitly. Request runtime Bluetooth permissions through an Activity; tiles cannot replace that flow. Only request scan permissions if scanning is implemented. Keep socket ownership and reconnect behavior bounded. Avoid automatically replaying non-idempotent writes after ambiguous failures. Model identity and Bluetooth addresses must not appear in default logs without redaction.

Archive SDK values (min 29, target/compile 34), Kotlin 2.0.20, AGP 8.5.2, Java 17 and coroutines 1.8.1 are prototype inputs, not version recommendations. Select and verify compatible toolchain versions when creating the new build, then pin them with a Gradle wrapper and record executable checks.

## Flask Conventions for the First Implementation

Before writing Python, load the available Python coding skill. Establish the backend's role and a minimal typed request/response contract first. Keep routes separate from protocol/session ownership and inject a fake transport for tests. Validate input at the boundary, return actionable failure responses and avoid broad exception-to-success conversions. Do not introduce a database, authentication subsystem, task queue or Flask extension without a concrete need.

There is currently no Flask code example or project command to cite. Once implemented, record the actual package layout, configuration loader, dependency lock, error schema, logging redaction and tests here; do not fill generic backend templates with fictional existing conventions.
