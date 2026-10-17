# HCN platform radio (UIS8581A / ZA firmware)

Units of the "hcn" platform have no `android.qf.mcu` service. The FM tuner belongs to the
factory radio app (`com.hcn.autoradio`), which also renders the FM audio. `HcnTuner` uses that app
as a headless engine. Everything below was read from the factory APK and a `radio-probe.txt`
(Settings > Radio > Radio diagnostics).

## Services

| Service | Interface | Used for |
| --- | --- | --- |
| `com.hcn.autoradio/.service.FMPlugService` (started and bound) | `com.hcn.autoradio.IRadioServiceAPI` | tune, seek, band, local, request audio |
| system service `fmradio` | `android.radio.IRadioPlayer` | live state callback, mute |
| callback we register on `fmradio` | `android.radio.IRadioCallback` | `onEvent(int, RadioInfo)`, `onStringEvent(int, String)` |

Starting `FMPlugService` starts the factory audio render (AudioRecord source 1998 to AudioTrack)
and its audio focus handling, so the launcher does not request audio focus itself on this backend.

## IRadioServiceAPI codes (all two-way)

`onBandEvent` 5 (cycles FM1, FM2, FM3, AM1), `onLocDxEvent` 8 (toggle), `onSeekDownEvent` 9,
`onSeekUpEvent` 10, `gotoFreq(int kHz)` 14, `getCurrentBand` 17, `getCurrentFreq` 18, `IsStereo` 23,
`IsDxLocal` 24, `requestPlayAudio` 25.

## IRadioPlayer codes used

`setcallback(IRadioCallback)` 1, `removecallback` 2, `getRadioInfo` 11, `setMute(boolean)` 14 (one-way).

## RadioInfo parcel

`int freqKhz, int scanType, bool stereo, bool local, String uiBand, int index, String ps, String rt,
String rdsState, String pty`. Scan types: 1 auto scan, 2 scan, 3 seek up, 4 seek down, 5 preset scan.
String events: 38 = PS (station name), 41 = RT (radio text).
