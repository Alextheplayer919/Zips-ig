# First phone test — 2026-10-22

**Source:** user-pasted output from a single manual load on the user's phone. Android 13 is the previously stated device environment; this output does not independently identify the OS, ABI, installed APK version or build run.

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

The user reports tapping **Load trusted demo** multiple times; the status stayed `PASS`. Repeated loading therefore passes as reported. No new PID/UID readings or rotation result were supplied.

## Still to check on the phone
- Rotate the host, then load again without a duplicate-native-library error.
- Optionally open the demo separately and compare its execution UID with its installation UID.

Rotation and standalone comparison have not been reported. Android 13 x86_64 emulator integration already passed in [the CI run](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39840759440), but emulator results do not substitute for these phone checks.

The next implementation stage remains the [hosting-only Minecraft adapter](STATUS.md#gate-2--hosting-only-minecraft-adapter), not gameplay features or engine hooks.
