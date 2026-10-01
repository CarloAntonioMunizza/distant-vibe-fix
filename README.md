# Angelica Vulkan fixes

Local patches for **Angelica 2.2.23**, tested with Minecraft 1.7.10 / GTNH 2.9.0-RC-1 and Complementary Reimagined r5.9.3 + Euphoria Patches 1.10.5.

These are custom builds, not official Angelica releases. Both retain SDL-GPU/Vulkan and persistent geometry streaming. Performance differences have not been measured.

## Builds and status

| Build | Changes | Verification |
| --- | --- | --- |
| Tool fix | Align shader-extended persistent uploads to four vertices | Sword, pickaxe, and hammer rendered correctly; user confirmed no blinking while switching tools and looking around |
| Tool + tracing fix | Tool fix plus invalidation of stale shader parse trees after image-store extraction | Code checks passed; installed and restarted; Advanced Color Tracing startup compilation succeeded; final in-world visual check pending |

The tracing build includes the tool fix. This repository publishes the patches and reproduction tools; build the JARs using the commands below. Install **one** build, not both.

## Install

1. Close Minecraft normally and back up the existing Angelica JAR.
2. Disable the existing Angelica JAR by moving it out of `mods` or changing its extension to `.disabled`.
3. Put the chosen generated JAR in `mods`. Keep only one active Angelica JAR.
4. In Prism Launcher, edit the instance's Java arguments through its settings UI. Keep `-Dangelica.sdlgpu.enable=true`
5. Start Minecraft and test held tools while switching slots and looking around.

The tested instance also used `-XX:+UseZGC -XX:+UseCompactObjectHeaders` with Java 25. Those JVM options are not part of the rendering fix and should not be copied to an incompatible Java version.

For the tracing build, enable real-time shadows and set **Advanced Color Tracing to 4 Chunks** initially. The tested value is `COLORED_LIGHTING=128`. World-Space Reflections remained off and have not been verified. Higher tracing ranges have not been tested.

To roll back, close Minecraft, remove or disable the custom JAR, and restore the backed-up official JAR. The previously confirmed fallback was Vulkan with `-Dangelica.debug.forceOrphanStreaming=true`; it bypasses persistent streaming.

## What changed

### Tinkers' Construct held-tool blinking

Shader-extended persistent uploads could start at a vertex index not divisible by four. This selected the temporary quad-index-buffer path, whose contents could be overwritten before deferred Vulkan draws consumed them. The [source patch](patches/quad-alignment.patch) requests four-vertex alignment from `PersistentStreamingBuffer.upload`.

Both base and Java 17 versions of `TessellatorStreamingDrawer` are patched. Against the official 2.2.23 JAR, the tool-only build changes exactly these two class entries. Vulkan is not disabled and the orphan-streaming fallback is not enabled.

### Advanced Color Tracing shader crash

Image-store extraction rewrites a graphics shader and generates a compute pre-pass. Angelica retained the graphics parse trees from before extraction, whose token offsets no longer matched the returned source. Vulkan prewarming then failed with `IndexOutOfBoundsException` in `GlslVulkanPreprocess.applyEdits` while transforming the shadow shader.

The [source patch](patches/tracing-artifact-invalidation.patch) invalidates the graphics artifacts for programs that gained an extracted compute pre-pass. Prewarming then parses the actual returned shader source. Ordinary programs retain their existing artifacts. The binary patch implements the same behavior through a small helper in `ShaderTransformer`.

Against the tool-only build, the combined build changes exactly the base and Java 17 entries of `ShaderTransformer`. Startup logs confirmed the active shader pack, a `128 x 64 x 128` tracing voxel image, extracted voxel-writing compute pre-passes, and creation of Vulkan shaders. The original transformation exception did not recur during that check. This does not establish visual correctness or measured performance.

## Reproduce and verify

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

Checks cover operand stacks, the aligned upload call in both class variants, unchanged ordinary shader artifacts, invalidated extracted artifacts, and null artifact maps. The patchers reject unexpected call/class counts. Use them only with the specified upstream version.

## SHA-256

```text
Official Angelica 2.2.23 input:
a7b82ca3a193f6737ebb36650f7287b883cf6985a30608c8fed0a6a9c1d4a5f0

Tool fix:
801ddf74f66a202ab8a27a1a38000f75ec8d8bbd41ce2609d5c488306ad00acc

Tool + tracing fix:
cf34ad42ab63168c393ed75bf02552c5d7c0dd21260e15478ee33ed7a7039d70
```

Angelica 2.2.24 does not include the four-vertex upload change in the source reviewed during this session. These patches remain based on 2.2.23; they have not been rebased onto 2.2.24.

## License and attribution

Angelica is developed by GTNewHorizons and its contributors. See [the upstream license](LICENSE) and notices inside the distributed JARs. The patches and patching tools here are provided under LGPL-3.0, consistent with Angelica. See [upstream source](https://github.com/GTNewHorizons/Angelica/tree/2.2.23) together with the included patches for corresponding source.
