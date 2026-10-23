# Missing default Firebase app: notification-token JNI abort

## Phone evidence and limits

The user supplied an explicit fatal excerpt (no PID/UID/build header supplied; associated with the 0.5.3 test by conversation, not independently verified):

```text
JNI GetStringUTFChars called with pending exception IllegalStateException:
Default FirebaseApp is not initialized in this process ...:game_host.
Make sure to call FirebaseApp.initializeApp(Context) first.
FirebaseApp.getInstance -> FirebaseMessaging.getInstance
-> NotificationListenerService.retrieveDeviceToken:71
-> NotificationListenerService.getDeviceRegistrationToken:60
Exit reason SIGNALED (2), signal 6 / SIGABRT
```

The concrete failing prerequisite is Firebase's default app, reached by Minecraft's notification-token path. ART's abort follows a pending Java exception; it is not proof of an engine ELF import failure. The prior Play Games error is not in this excerpt; that absence alone is not a complete independent Play Games validation.

Other observed errors include EventsSDK SQLite open failures and SELinux denial of writes to `tmp` with shell_data_file labeling. These are distinct storage observations, **not established causes of this fatal stack**. Do not grant shell access, disable SELinux, redirect every database speculatively, suppress CheckJNI, clear the exception, return a fake token or bypass Firebase backend checks. Swappy/vendor/graphics warnings also remain separate. Cached device identifier and Breakpad session/dump identifiers are deliberately omitted here.

## Inspection of existing supplied manifest/DEX

Static Androguard inspection only, no original game/SDK execution locally:

- Original `FirebaseInitProvider` in classes3.dex calls **FirebaseApp.initializeApp(Context)**. Manifest initOrder is **100**, ahead of Play Games **99**.
- initializeApp(Context) returns the existing default app, or reads **FirebaseOptions.fromResource(Context)**; it can return **null** when default options are missing. It then constructs/registers the default app and initializes APIs.
- FirebaseOptions uses StringResourceValueReader. The reader derives the resource package from the original GMS R string and reads named values from that Resources instance. Our already installed original game Application resource view is therefore important. No Firebase identifiers/API-key values are copied into the host source or docs.
- FirebaseApp's constructor discovers lazy ComponentRegistrars from **ComponentDiscoveryService** metadata, using `new ComponentName(context, SDKServiceClass)` and the real PackageManager. Since context retains host package identity, that lookup must resolve under the **host** package.
- Original manifest discovery service declares exactly three registrars: **FirebaseMessagingRegistrar**, **TransportRegistrar**, **FirebaseInstallationsRegistrar**, with the standard ComponentRegistrar value. Calling initializeApp alone without these could merely replace the first failure with a missing Messaging component.

## Host 0.5.4 / code 12 correction

After unchanged startup/engine pin checks, on main in **:game_host**, before original class initialization/construction/lifecycle:

1. Call original **FirebaseApp.initializeApp(actual host Application)** via the prepared guest loader, before Play Games to preserve these two providers' original init order. Original resource/assets/loader views are already installed; real host UID/package/storage remain unchanged.
2. Validate class ownership/public static method signatures, reject a null initialization result, verify **getInstance() is the exact initialized default app**, and require non-null original **FirebaseMessaging.getInstance()**. Do not request/forge a token in the host adapter. Restore thread context loader even on failure; entered/ready/blocked stages propagate exact causes.
3. Declare a real, non-exported **ComponentDiscoveryService** under the host in **:game_host**, with the original three registrar entries and directBootAware setting. This is ordinary metadata resolution, **not PackageManager spoofing**. Normally Firebase only reads this metadata, not starts the service. A fixed AppComponentFactory mapping supports only this exact original SDK service, requiring the prepared pinned game session and dedicated process, and loads the actual service from that loader if Android ever constructs it. No substitute Firebase service/class is shipped in host code.

Original init providers themselves remain unregistered. This is a narrow reconstruction of verified Firebase/Play Games startup effects, not all-provider/SDK virtualization. No new platform permission, SDK dependency bundled in the host, Google services configuration file requested, JNI/native patch, authentication bypass or whole-device logging is added. Messaging receiver/service registration, backend configuration/package restrictions, network/services/token delivery and other original SDK requirements are **not proven complete**. Startup can fail early with an honest Java diagnostic instead of the previous native abort.

## Validation plan

Five new Android tests use our own explicitly named demo initializer/component fixtures, never fake Firebase/Minecraft classes: real Application/guest CCL identity; null options/wrong default/missing Messaging/exception propagation; exact real PackageManager discovery declaration and registrar set; factory UI/wrong-component rejection; and wrong thread/owner/signature/missing class rejection. One new source guard checks ordering, ownership, return checks and private metadata declaration. Existing Play Games and other regressions remain enabled. Actual original SDK/ARM64 game outcome requires the phone, not the fixture.

## Validated correction

[Android 13 CI run 39988805056](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39988805056) passed for app/test commit **287e04bb542a4c0227abfb32aa041fdd91bd7103**, job **120535581013**, **5m30s**, host **0.5.4 / code 12**. Build, actual APK definition-exclusion checks and **48 Android 13 x86_64 regression cases** passed, including the five new Firebase invocation/discovery tests. **25 local source/native guards** pass. No tests disabled. Original Firebase/game code is inspected statically; CI executes our actual invocation seam using our own demo fixture, not a fake Firebase implementation or ARM64 Bedrock.

- [APK ZIP](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39988805056/artifacts/12516682587): **client-base-debug-80**, **6,207,135 bytes**. Install only **client-base-host-debug.apk**.
- [CI reports](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39988805056/artifacts/12516722958): **client-base-device-tests-80**, **223,715 bytes**. Passing CI conclusion recorded; independent local artifact XML parsing not claimed.
- Retained engine archive blob **c78f6e8642d334916d247487897eb871ad27c160**, all startup/engine pins and permissions unchanged. Only the private Firebase discovery service/registrar metadata is added; no original init provider or push receiver/service is registered.

Next phone attempt: update only the host, keep Minecraft unchanged, **Launch Bedrock Activity (experimental)** once, report visible output and **Save Bedrock Activity report (.txt)** after exit collection if it exits. Preserve diagnostics before uninstalling only the host if debug keys conflict. No Firebase configuration file, SDK download, engine import, demo/link/bootstrap repeat or new user file is needed. Corrected real Firebase/default Messaging startup, token delivery, EventsSDK storage and title-screen outcome remain unproven until actual device evidence.
