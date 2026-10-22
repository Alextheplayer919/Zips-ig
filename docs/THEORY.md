# Hosting theory: how the original client may work

## Scope and confidence

Our goal is an independent client running rootlessly on Android 13, targeting the user's Minecraft **26.50** installation without rewriting its APK. The user reports that the original ProtoHax client successfully launches that combination. We have not independently captured or reproduced that device run.

The architecture below combines historical static observations from the original ProtoHax APK with a **hypothesis** about its unrecovered native integration. The old trace tools/reports were deliberately removed during the requested cleanup. This document preserves the conclusions and their boundaries, not a complete reproducible reverse-engineering archive.

## The main idea: host the game, rather than attach to it

```text
Installed Minecraft base/split APKs (unchanged)
                     |
         host's guest class loader
         + resources/context/lifecycle adapters
                     |
      guest activity inside the host's process
                     |
    Minecraft native engine + host native library
                     |
       in-process engine integration (unrecovered)
```

This explains the rootless claim without assuming an Android exploit. Native libraries in one process share an address space. Code in that process can address its own mappings, subject to memory protections; it does not need permission to read an unrelated app's process. That does **not** identify the hook technique or establish that every proposed mapping/initialization step succeeds.

## What the original APK inspection established

Historical input: ProtoHax `hotfix1.apk`, SHA-256 `17e563e816f8078d5df1be68309b3abd23ab5ea1fa78c0d9f6fd636200067a19`.

| Observation | What it supports | What it does not establish |
| --- | --- | --- |
| Guest package lookup and collection of `sourceDir` / `splitSourceDirs` | Reads installed game code paths, including splits | Compatibility with every Minecraft packaging/version |
| Custom `DexClassLoader` with guest/host loading policies | Guest classes can be selected inside the host | Arbitrary activities work without framework adaptation |
| Replacement of host package class-loader state; guest Application and provider setup | Substantial app-hosting machinery, beyond a simple library loader | A complete, independently working reimplementation |
| Context/resources and Android `Instrumentation` wrapping | Lifecycle and package-environment redirection | Use of Frida, Xposed or any particular hooking framework |
| Minecraft's activity class declared in the host manifest with `:game` process | The component name can be hosted under the declaring app's process/UID | Execution under the separately installed Minecraft app's UID |
| APK native dependency extraction/loading; recovered `libc++_shared.so` name | A native dependency loader exists | That particular call loads the Minecraft engine |
| `RuntimeInterop.init(String, String)` JNI bridge into a protected native runtime | There is a native initialization boundary | Its engine lookup or hook installation behavior |
| Bounded `libminecraftpe.so` marker in recovered native data | The engine library is a likely native target | A recovered `dlopen` argument, library base, symbol lookup or hook site |

The manifest's `:game` process is private to its declaring package. Naming a guest component `com.mojang.minecraftpe.MainActivity` does not transfer Minecraft's UID, signature or private-data access to the host.

## What remains a hypothesis

The strongest explanation is that the game engine and client native code are brought into the **same hosted process**, then integrated locally. We have not recovered the original engine-loading call chain or the final integration mechanism.

Possible in-process techniques include inline hooks, PLT/GOT interception, vtable/callback replacement or direct calls through resolved addresses. These are alternatives, not findings. We have no established attribution to a particular hook library. The recovered dispatcher, TLS and synchronization code should not be mistaken for the hook installer.

Static tracing reached unresolved runtime bindings and startup-input memory. We stopped expanding that protected VM because the volume of traces was not resolving the engine-integration question.

## Why our new base starts smaller

Our own demo uses a cooperative entry interface, standard `DexClassLoader`, package resources and a JNI library. It intentionally avoids hidden APIs and Android framework replacement.

Its native PID/UID check tests a narrow premise: **code from a separately installed guest APK can execute within our host's process**. A successful demo is useful engineering evidence, but does not reproduce the original client's more extensive hosting layer.

The next Minecraft adapter would still need to solve:

- Original Activity/Application/provider initialization and lifecycle routing.
- Resource, context, package identity and base/split consistency.
- Native dependency search paths, ABI and engine initialization order.
- Package/signature/entitlement assumptions and legitimate sign-in behavior.
- Version-specific engine interfaces, only after the engine actually runs.

Running as our host's UID does not automatically expose the original app's private worlds, account data or credentials. No sandbox escape or entitlement bypass is part of the design.

## Engine input

`libminecraftpe.7z` contains one ARM64 ELF shared library. Its identity and hashes are in [ENGINE_INPUT.json](ENGINE_INPUT.json). We have **not** verified its Minecraft version from a matching APK manifest. It remains a reference input only: neither demo APK extracts, loads or bundles it.

A standalone engine library is insufficient to reconstruct the complete Android game environment; code, resources, dependencies and any required splits must be handled together.
