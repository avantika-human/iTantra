# iTantra - Walkie-Talkie Neural Transceiver

Low-latency, offline-first push-to-talk radio for the Smart India Hackathon.
Speech is transcribed on-device with sherpa-onnx (STT), relayed as raw UTF-8
lines over a TCP socket on the local Wi-Fi hotspot, and spoken aloud on the
peer device with on-device TTS. No internet. No cloud. No carrier.

## Field topology (2 phones + 1 hotspot)

    Device A (Receiver / TTS) <-- hotspot LAN 192.168.43.x --> Device B (Transmitter / STT)

1. Device A enables Portable Hotspot, launches iTantra and picks Receiver (TTS).
   iTantra binds a ServerSocket on port 50005 and waits for peers.
2. Device B joins that hotspot, launches iTantra and picks Transmitter (STT).
   iTantra dials the hotspot gateway 192.168.43.1:50005.
3. Hold the PTT button and speak; release to transmit the transcript.
4. Toggle Alert Override (100% Vol) to flag traffic as [EMERGENCY] - the
   receiver then forces STREAM_MUSIC to maximum volume before playback.

Note: some newer Android builds assign the hotspot gateway 192.168.49.1.
Update P2PSocketManager.DEFAULT_HOST in that case.

## Neural models

Drop sherpa-onnx artifacts into:

    app/src/main/assets/sherpa-stt/  (encoder/decoder/joiner onnx + tokens.txt)
    app/src/main/assets/sherpa-tts/  (vits onnx + lexicon + tokens + espeak data)

Until then, SherpaEnginePlaceholder keeps the full TX -> RX -> TTS loop
testable with a stub transcript. Real integration points are documented
inline in com.sih.itantra.engine.SherpaEnginePlaceholder.

## Build

Open this folder in a recent Android Studio and let Gradle sync finish, or
build from a terminal:

    gradlew.bat assembleDebug
