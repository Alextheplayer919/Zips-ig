# GameActivity preflight — phone result and empty-initializer correction

**Evidence:** user-pasted host 0.4 report, not an independently captured device trace. [Complete normalized metadata](GAMEACTIVITY_DEVICE_TEST.json) retains all 24 native declarations and DEX provenance. Escaped slashes and HTML arrows were decoded for readability.

- Probe PID **6386**, host UID **11066** (observations from this run only).
- Installed Minecraft **1.26.50.4 / 972605004**, **1 APK**, supplied engine fingerprint matched.
- MainActivity's known **45-unit** initializer matched.
- Actual GameActivity extends **`androidx.appcompat.app.AppCompatActivity`**.
- **24 native declarations**, not truncated; no declaration was called.
- Source **`classes2.dex`**, **1,655,112 bytes**, ZIP CRC **3014425259** (provenance, not authentication).
- `PROBE_BLOCKED` at `JAVA_BOOTSTRAP_PREFLIGHT`, before guest-loader creation or any game class/native initialization.

## Why host 0.4 stopped

The actual GameActivity contains a `<clinit>`, but it consists of exactly one DEX code unit:

```text
registers: 0
code_units_total: 1
code_units_hex: 000e
truncated: false
references: []
reference_scan_complete: true
```

DEX opcode **`0x0e` is `return-void`**. This initializer does no work; it does not load a library, call a method or read/write a field. Host 0.4's gate only accepted an *absent* initializer, so it rejected this explicitly empty initializer too. That was an overly strict host policy, **not evidence of a Minecraft JNI/linker failure**. The stop happened before the initialization attempt.

## Narrow correction in host 0.4.1

Continue accepting an absent initializer. Also accept **this exact observed no-op form**: zero registers, one code unit `000e`, no truncation, complete reference scan and an empty references array. Changed/longer instructions, NOPs, extra registers, references, partial or missing metadata and a different class remain blocked. All version, engine-hash, MainActivity code/reference, loader-ownership and consent gates remain unchanged.

Regression cases cover the positive no-op/absent forms and negative mutations. Real original Java/JNI initialization remains **untested on the phone** until the corrected host is run. No Activity, lifecycle, Surface or title-screen success is claimed.
