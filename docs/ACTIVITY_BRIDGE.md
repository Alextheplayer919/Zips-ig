# Framework Activity bridge — first implementation

## Scope

This is developer-side implementation work toward original Bedrock hosting, **not another phone demo request** and not an enabled Minecraft launch. The successful host 0.4.3 bootstrap remains the last real Bedrock phone milestone. No additional game file is currently needed.

## Startup review actually performed

Selected constructors and lifecycle methods from the supplied MainActivity (`classes3.dex`) and GameActivity (`classes2.dex`) were disassembled locally, without running game code. Output is kept ignored; no whole-game trace is committed. MainActivity is public/non-final in this input; no MainActivity or GameActivity `attachBaseContext` override was found among their declared methods.

The important startup order is:

1. MainActivity installs AndroidX SplashScreen and enters GameActivity's `onCreate`.
2. GameActivity creates its SurfaceView/window setup, reads `android.app.lib_name` through `PackageManager.getActivityInfo(getIntent().getComponent(), GET_META_DATA)`, resolves the library using its `BaseDexClassLoader`, and uses normal `System.loadLibrary`.
3. It obtains files/OBB/external-files paths, AssetManager, saved native state and resource Configuration from its attached Activity context, then calls the **original** `initializeNativeCode` binding. A zero native handle is an error, not success.
4. It configures native input before entering AppCompatActivity's `onCreate`. Later original lifecycle methods forward start/resume/pause/stop and Surface changes to native code.
5. MainActivity then performs additional real SDK/device setup (including FMOD, input/network monitoring and other application work). A mapped engine alone does not supply this environment.

This rules out simply calling `onCreate` on an unattached Java object, using a second unrelated loader, or treating a resource lookup as a complete Activity environment.

## Implemented code

- `HostComponentFactory`: an ordinary public Android AppComponentFactory. Only one exact, non-exported host fixture component is mapped to one fixed, signature/protocol-gated cooperative guest Activity. Other host components follow the normal factory path; no class names from Intent extras or arbitrary packages are accepted.
- `FrameworkActivityRuntime`: reuses the existing trusted guest loader/native owner, verifies Activity/contract loader identity, configures the Activity **before attachment**, then returns it to ActivityThread. It does not call `Activity.attach`, lifecycle methods or native game entrypoints.
- `ActivityResourceContext`: Activity-local guest resources/assets/class loader/theme plus a context-cloned LayoutInflater and configuration-context handling. Host package identity, Application, permissions, services and storage are retained. No hidden API, global Resources overlay, spoofed UID or private-game-data access.
- `FrameworkGuestActivity`: our own developer fixture. Android creates/attaches its real window and delivers lifecycle and actual Surface callbacks. XML layout, string and theme are read from the separate demo APK. JNI calls share the same cached guest loader across Activity recreation.
- A fail-closed placeholder throws if factory substitution does not occur. The fixture has no launcher UI or new user phone-test instruction. Its lifecycle observations are bounded to 128 events.

The small `HostedActivity`/`ActivityEnvironment` interfaces belong to our cooperative fixture. **Original Minecraft does not implement them.** The later original-game adapter needs a separately reviewed attachment mechanism (a host-side subclass is a candidate because the supplied MainActivity is non-final); this commit does not substitute fake Minecraft classes or bundle proprietary stubs/DEX.

## Automated checks

Four new Android integration tests exercise framework-created Activity identity/resources/theme/JNI, recreation with the same loader, actual Surface creation/destruction, and rejection of an invalid component while preserving the ordinary host creation path. They do not execute Minecraft. Two additional local source guards check the fixed/private mapping and that the resource context does not override host identity/storage/permission methods.

Android compilation/emulator outcome must be recorded separately after CI; adding tests is not itself validation. The existing demo, archive/hash, native-loader, initializer and exit-diagnostic tests remain enabled.

## Remaining implementation

The primitive above is not a Minecraft Activity adapter yet. Original-game attachment still needs the same-loader subclass/factory path, exact component metadata/theme mapping, process-scoped preparation and lifetime, original Application/provider/SDK context requirements, and existing diagnostic integration. In particular, the fixture keeps the host Application unchanged and does not prove that returning that Application/resources is adequate for Bedrock's SDKs.

No user-facing launch button is enabled until those requirements are implemented and guarded. Gate 2 remains a **real hosted Bedrock title screen**, not this fixture passing or a returned bootstrap. No hook/entitlement/account bypass or speculative new permission is introduced.
