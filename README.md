# MetalCraft

A Fabric mod that replaces Minecraft's OpenGL renderer with a native **Metal** backend on Apple Silicon Macs.

Since 1.21.5, Minecraft draws everything through an internal abstraction (Blaze3D). MetalCraft implements that
abstraction on Metal, so the game never creates an OpenGL context. On macOS, OpenGL is deprecated and runs as a
translation layer on top of Metal anyway.

> **Status: early pre-release.** It has been tested on one machine (M5, macOS 27) with a fixed test world. Expect
> rough edges in the Nether/End, with resource packs, and with other rendering mods. Bug reports are welcome.

## Requirements

- Apple Silicon Mac, macOS 14 or newer
- Minecraft **1.21.11**, Fabric Loader **0.19.5+**, Fabric API
- Optional: Sodium **0.8.14** (0.8.x) — its terrain renderer is ported to Metal too

## Install

Download the jar from [Releases](https://github.com/Im-Fran/MetalCraft/releases) and put it in your `mods` folder
next to Fabric API.

## Performance

Measured at a fixed 60 fps, render distance 16, same scene, one run per configuration:

| | Render thread CPU | GPU time per frame |
|---|---|---|
| OpenGL (vanilla) | 21.2% | — |
| Metal | 12–13% | ~4.7 ms |
| Sodium + Metal | ~11% | ~4.0 ms |

MetalCraft's own GPU overhead (clears, copies, GUI, presenting) is under 0.5 ms per frame; the rest is Minecraft's
terrain shaders, which are the same work on either API.

## How it works

- **Backend:** `MetalDevice` implements Blaze3D's `GpuDevice`, backed by a thin Objective-C++ JNI layer
  (`src/main/native/metalcraft.mm`). Buffers use unified memory, so mapping them is just a pointer.
- **Shaders:** Minecraft's GLSL (including resource-pack core shaders) is translated at runtime,
  GLSL → SPIR-V (shaderc) → MSL (SPIRV-Cross), and cached on disk in `metalcraft/shader-cache`.
- **Sodium:** mixins route Sodium's OpenGL buffer, shader, uniform and draw calls to Metal.

## Known limitations

- Color logic ops (only usable by other mods; no vanilla pipeline uses them) are ignored.
- Mods that call OpenGL directly (outside Blaze3D) are not supported; there is no OpenGL context.

## Building

Requires Xcode (for `clang`) and any JDK to start Gradle; Gradle provisions Java 21/25 itself.

```sh
./gradlew build          # jar in build/libs, runs the shader translation tests
./gradlew runClient      # dev client with Metal API validation enabled
```

Development flags for `runClient`: `-Psodium` (add Sodium), `-PopenGL` (same build on OpenGL, for comparisons),
`-PautoScreenshot=N` (screenshot + fps/GPU log every N ticks), `-PgpuProfile` (GPU time per render pass),
`-Pbench` (disable Metal validation).

## License

[GPL-3.0](LICENSE)
