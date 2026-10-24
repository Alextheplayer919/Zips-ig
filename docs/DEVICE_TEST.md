# First phone test — 2026-10-22

**Source:** user-pasted output from the initial manual load, followed by repeated-load and rotation reports from the user's phone. Android 13 is the previously stated device environment; this output does not independently identify the OS, ABI, installed APK version or build run.

```text
PASS: guest JNI executes inside the host
Host PID: 13512
Native PID: 13512
Host UID: 11060
Native UID: 11060
Guest installation UID: 11061
APK paths loaded: 1
```

## Result

**First-load core check: passed as reported.** Guest JNI executes with the host's process/user identity, not the guest installation's UID. One APK code path was loaded; this does not test split-APK handling. The IDs are observations for this installation/run, not stable identifiers.

This is device-side evidence for our cooperative guest mechanism. It does not establish arbitrary app lifecycle virtualization, Minecraft engine loading, hooks, access to Minecraft's private data or Minecraft 26.50 compatibility for our implementation. The demo does not use the supplied engine archive.

## Follow-up: repeated loads

The user reports tapping **Load trusted demo** multiple times; the status stayed `PASS`. Repeated loading therefore passes as reported. No new PID/UID readings were supplied.

## Follow-up: rotation

The user reports rotating the phone and still seeing `PASS`. Rotation therefore passes as reported, with no native-library error reported. No lifecycle trace or fresh PID/UID readings were supplied, so the phone report does not independently prove Activity recreation occurred.

**Gate 1 is complete at the user-reported functional-check level:** initial load, repeated loads and rotation all pass. The optional standalone-demo UID comparison has not been reported and is not required to close this gate.

Android 13 x86_64 emulator integration, including instrumented Activity recreation, also passed in [the CI run](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39840759440). This does not expand the result to arbitrary app or Minecraft hosting.

The next implementation stage remains the [hosting-only Minecraft adapter](STATUS.md#gate-2--hosting-only-minecraft-adapter), not gameplay features or engine hooks.
