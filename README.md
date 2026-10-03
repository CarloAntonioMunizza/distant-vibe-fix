# Angelica Vulkan fixes

Local patches for **Angelica** on its SDL-GPU/Vulkan backend, tested with Minecraft 1.7.10 / GTNH 2.9.0-RC-1, Complementary Unbound r5.9.3 + Euphoria Patches 1.10.5, and Distant Horizons 3.3.3-dev (SDL-GPU hotfix build).

These are custom builds, not official Angelica releases. All of them retain SDL-GPU/Vulkan and persistent geometry streaming. Performance differences have not been measured.

## Current: Angelica 2.2.27 sampler build

Angelica 2.2.27 includes upstream versions of both earlier fixes in this repository: four-vertex alignment of quad uploads in `TessellatorStreamingDrawer`, and invalidation of stale parse trees after image-store extraction ([Angelica#2195](https://github.com/GTNewHorizons/Angelica/pull/2195)). The 2.2.23 builds below are superseded.

2.2.27 still limits SDL-GPU to 16 samplers per shader stage and reports 17 texture units to Iris. With Distant Horizons installed, Complementary + Euphoria Patches adds `dhDepthTex0` and `dhDepthTex1` to its composite passes, which pushes them past that limit:

| Shader settings, with DH | Composite fragment samplers | Result on official 2.2.27 |
| --- | --- | --- |
| Advanced colored lighting off | 17 | Iris accepts the program; the first draw throws `program N has 17 fragment samplers; MAX=16` |
| Advanced colored lighting on (adds `floodfill_sampler`, `floodfill_sampler_copy`) | 20 | Iris throws `No more available texture units while activating sampler shadowtex0` and disables shaders |

Without DH, the 17-sampler pass needs 15. The BetterCrashes recovery loop that follows the draw-time exception can also exhaust VRAM and end in a native driver crash.

| Build | Changes | Verification |
| --- | --- | --- |
| 2.2.27 sampler build | Official 2.2.27 plus [sampler-slots.patch](patches/sampler-slots.patch); requires the 32-sampler SDL in [`sdl/`](sdl) | `VerifySamplers` passed; user reported shaders and Distant Horizons working in-world. **Known issue:** water flickers; not yet investigated |

### Install

1. Close Minecraft normally and back up the existing Angelica JAR.
2. Disable the official Angelica JAR by moving it out of `mods` or changing its extension to `.disabled`.
3. Put the generated `angelica-2.2.27-samplers32.jar` in `mods`. Keep only one active Angelica JAR.
4. Copy [`sdl/SDL3-angelica-samplers32.dll`](sdl/SDL3-angelica-samplers32.dll) (Windows x64) to a folder whose path has no spaces.
5. In Prism Launcher, edit the instance's Java arguments through its settings UI. Keep `-Dangelica.sdlgpu.enable=true` and add `-Dorg.lwjgl.sdl.libname=C:/path/to/SDL3-angelica-samplers32.dll` with the absolute path from step 4. Edit through the UI, not `instance.cfg`: Prism keeps instance settings in memory while it is running.
6. Start Minecraft and check the log for `Sampler slots per shader stage: 32 (SDL revision: SDL-3.4.12-release-3.4.12 (angelica-samplers32-fencefix))`.

If the log reports 16, LWJGL loaded its bundled SDL. The patched JAR then keeps the official limits (16 per stage, 17 texture units), because stock SDL overflows its binding arrays past 16.

To roll back, close Minecraft, remove the custom JAR and the `-Dorg.lwjgl.sdl.libname` argument, and restore the official JAR.

### What changed

**Angelica.** The [source patch](patches/sampler-slots.patch) adds a `SamplerSlots` helper that reads `SDL_GetRevision()` once. It returns 32 sampler slots per stage when the revision contains `angelica-samplers32`, and 16 otherwise. Sampler binding buffers are sized for 32. The per-stage limit checks in `SamplerBinder` and `FBOClearTracker` and the reported `GL_MAX_TEXTURE_IMAGE_UNITS` / `GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS` use the runtime value. Iris sizes its texture-unit pool from `GL_MAX_TEXTURE_IMAGE_UNITS`, so the same value fixes both failures in the table above.

`PatchSamplers` applies the same change to the official JAR. Against official 2.2.27 it changes exactly seven class entries (base and Java 17 variants of `SDLGPURenderBackend`, `ContextState` and `SamplerBinder`, plus `FBOClearTracker`) and adds `SamplerSlots`. Before patching, `ScanSamplerLimits` was run on 2.2.26 and 2.2.27. Its only difference is a hard-coded 16 that 2.2.27 removed from `bindVoxelizationRegion`. 2.2.27 adds no new limit sites that the patcher would miss.

**SDL.** The DLL is SDL 3.4.12 (the version bundled with LWJGL 3.4.2) with two patches, built with `SDL_VENDOR_INFO=angelica-samplers32-fencefix`:

- [sdl-3.4.12-samplers.patch](patches/sdl-3.4.12-samplers.patch) raises `MAX_TEXTURE_SAMPLERS_PER_STAGE` from 16 to 32.
- [sdl-3.4.12-fence-ownership.patch](patches/sdl-3.4.12-fence-ownership.patch) gives the fence returned by `SDL_SubmitGPUCommandBufferAndAcquireFence` its own reference. A fence the application releases early is then never returned to the pool and reset while its submission is still in flight.

`SamplerSlotTest` and `FenceReuseTest` exercise these SDL changes directly. They need LWJGL with the SDL and shaderc bindings on the classpath.

### Build and verify

With `PatchSamplers.java`, `VerifySamplers.java`, `ScanSamplerLimits.java`, the ASM 9.8 JARs (see below) and the official `angelica-2.2.27.jar` in one directory:

```powershell
javac -cp 'asm-9.8.jar;asm-tree-9.8.jar;asm-analysis-9.8.jar' PatchSamplers.java VerifySamplers.java ScanSamplerLimits.java
java -cp '.;asm-9.8.jar;asm-tree-9.8.jar;asm-analysis-9.8.jar' PatchSamplers angelica-2.2.27.jar angelica-2.2.27-samplers32.jar
java -Xverify:all -cp '.;asm-9.8.jar;asm-tree-9.8.jar;asm-analysis-9.8.jar' VerifySamplers angelica-2.2.27-samplers32.jar
```

Before rebasing onto a newer release, diff `java -cp '.;asm-9.8.jar;asm-tree-9.8.jar' ScanSamplerLimits <jar>` for the old and new official JARs. The patcher rejects inputs that are already patched and inputs with unexpected site or class counts.

## Angelica 2.2.23 builds (superseded)

| Build | Changes | Verification |
| --- | --- | --- |
| Tool fix | Align shader-extended persistent uploads to four vertices | Sword, pickaxe, and hammer rendered correctly; user confirmed no blinking while switching tools and looking around |
| Tool + tracing fix | Tool fix plus invalidation of stale shader parse trees after image-store extraction | Code checks passed; installed and restarted; Advanced Color Tracing startup compilation succeeded; final in-world visual check pending |

The tracing build includes the tool fix. This repository publishes the patches and reproduction tools; build the JARs using the commands below. Install **one** build, not both.

### Install

1. Close Minecraft normally and back up the existing Angelica JAR.
2. Disable the existing Angelica JAR by moving it out of `mods` or changing its extension to `.disabled`.
3. Put the chosen generated JAR in `mods`. Keep only one active Angelica JAR.
4. In Prism Launcher, edit the instance's Java arguments through its settings UI. Keep `-Dangelica.sdlgpu.enable=true`
5. Start Minecraft and test held tools while switching slots and looking around.

The tested instance also used `-XX:+UseZGC -XX:+UseCompactObjectHeaders` with Java 25. Those JVM options are not part of the rendering fix and should not be copied to an incompatible Java version.

For the tracing build, enable real-time shadows and set **Advanced Color Tracing to 4 Chunks** initially. The tested value is `COLORED_LIGHTING=128`. World-Space Reflections remained off and have not been verified. Higher tracing ranges have not been tested.

To roll back, close Minecraft, remove or disable the custom JAR, and restore the backed-up official JAR. The previously confirmed fallback was Vulkan with `-Dangelica.debug.forceOrphanStreaming=true`; it bypasses persistent streaming.

### What changed

#### Tinkers' Construct held-tool blinking

Shader-extended persistent uploads could start at a vertex index not divisible by four. This selected the temporary quad-index-buffer path, whose contents could be overwritten before deferred Vulkan draws consumed them. The [source patch](patches/quad-alignment.patch) requests four-vertex alignment from `PersistentStreamingBuffer.upload`.

Both base and Java 17 versions of `TessellatorStreamingDrawer` are patched. Against the official 2.2.23 JAR, the tool-only build changes exactly these two class entries. Vulkan is not disabled and the orphan-streaming fallback is not enabled.

#### Advanced Color Tracing shader crash

Image-store extraction rewrites a graphics shader and generates a compute pre-pass. Angelica retained the graphics parse trees from before extraction, whose token offsets no longer matched the returned source. Vulkan prewarming then failed with `IndexOutOfBoundsException` in `GlslVulkanPreprocess.applyEdits` while transforming the shadow shader.

The [source patch](patches/tracing-artifact-invalidation.patch) invalidates the graphics artifacts for programs that gained an extracted compute pre-pass. Prewarming then parses the actual returned shader source. Ordinary programs retain their existing artifacts. The binary patch implements the same behavior through a small helper in `ShaderTransformer`.

Against the tool-only build, the combined build changes exactly the base and Java 17 entries of `ShaderTransformer`. Startup logs confirmed the active shader pack, a `128 x 64 x 128` tracing voxel image, extracted voxel-writing compute pre-passes, and creation of Vulkan shaders. The original transformation exception did not recur during that check. This does not establish visual correctness or measured performance.

### Reproduce and verify

Upstream source: [GTNewHorizons/Angelica tag 2.2.23](https://github.com/GTNewHorizons/Angelica/tree/2.2.23). Apply the patches from this repository to that tag to obtain the corresponding modified source. Its license and bundled third-party notices continue to apply.

The `tools` directory contains the exact Java/ASM patchers and verification programs used for the custom JARs. They require a JDK and ASM 9.8 (`asm`, `asm-tree`, and `asm-analysis`) on the classpath. Download these from the official Maven Central repository into a separate working directory.

On Windows, with the four Java source files, ASM JARs, and official Angelica JAR in that directory:

```powershell
javac -cp 'asm-9.8.jar;asm-tree-9.8.jar;asm-analysis-9.8.jar' PatchAlignment.java PatchTracing.java VerifyAlignment.java VerifyTracing.java
java -cp '.;asm-9.8.jar' PatchAlignment angelica-2.2.23.jar tool-fix.jar
java -cp '.;asm-9.8.jar;asm-tree-9.8.jar;asm-analysis-9.8.jar' PatchTracing tool-fix.jar tracing-fix.jar
java -cp '.;asm-9.8.jar;asm-tree-9.8.jar;asm-analysis-9.8.jar' VerifyAlignment tracing-fix.jar
java -Xverify:all -cp '.;asm-9.8.jar;asm-tree-9.8.jar' VerifyTracing tracing-fix.jar
```

Checks cover operand stacks, the aligned upload call in both class variants, unchanged ordinary shader artifacts, invalidated extracted artifacts, and null artifact maps. The patchers reject unexpected call/class counts. Use them only with the specified upstream version. `VerifyAlignment` expects the 2.2.23 patch exactly and reports a missing alignment on 2.2.27, whose upstream fix aligns conditionally on the draw mode.

## SHA-256

```text
Official Angelica 2.2.27 input:
7f76fac9dd59303614799ed373ad3f5163c712b86fc780907cc607364e812152

2.2.27 sampler build (PatchSamplers, JDK 26):
1d71678d5fc071a4201c47a5f86210d9d1361c7137c0e1ccfe40e6cd77a2b5d9

sdl/SDL3-angelica-samplers32.dll:
28092962e44f63823269e8d567a69a2c81d0a8ccce1d6c27e0e6e79101aebd1d

Official Angelica 2.2.23 input:
a7b82ca3a193f6737ebb36650f7287b883cf6985a30608c8fed0a6a9c1d4a5f0

2.2.23 tool fix:
801ddf74f66a202ab8a27a1a38000f75ec8d8bbd41ce2609d5c488306ad00acc

2.2.23 tool + tracing fix:
cf34ad42ab63168c393ed75bf02552c5d7c0dd21260e15478ee33ed7a7039d70
```

## License and attribution

Angelica is developed by GTNewHorizons and its contributors. See [the upstream license](LICENSE) and notices inside the distributed JARs. The patches and patching tools here are provided under LGPL-3.0, consistent with Angelica. See [upstream source](https://github.com/GTNewHorizons/Angelica/tree/2.2.27) together with the included patches for corresponding source. SDL is distributed under the zlib license; see [`sdl/LICENSE.txt`](sdl/LICENSE.txt).
