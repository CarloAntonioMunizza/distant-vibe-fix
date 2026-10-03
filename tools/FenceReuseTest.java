import java.nio.ByteBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.sdl.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.sdl.SDLError.SDL_GetError;
import static org.lwjgl.sdl.SDLGPU.*;
import static org.lwjgl.sdl.SDLInit.*;
import static org.lwjgl.sdl.SDLVersion.SDL_GetRevision;
import static org.lwjgl.util.shaderc.Shaderc.*;

/**
 * Replays Angelica's fence pattern: submit slow GPU work with an acquired fence, release that fence
 * while the work is still running, then submit again so SDL takes a fence from its pool.
 * Usage: FenceReuseTest <iterations> [wait]   ("wait" also blocks on each second submission's fence)
 */
public class FenceReuseTest {
    static ByteBuffer spirv(String src, int kind, String name) {
        long compiler = shaderc_compiler_initialize();
        long options = shaderc_compile_options_initialize();
        shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_0);
        long result = shaderc_compile_into_spv(compiler, src, kind, name, "main", options);
        if (shaderc_result_get_compilation_status(result) != 0)
            throw new IllegalStateException(name + ": " + shaderc_result_get_error_message(result));
        ByteBuffer bytes = shaderc_result_get_bytes(result);
        ByteBuffer copy = MemoryUtil.memAlloc(bytes.remaining());
        copy.put(bytes).flip();
        return copy;
    }

    static void check(boolean ok, String what) {
        if (!ok) throw new IllegalStateException(what + " failed: " + SDL_GetError());
    }

    public static void main(String[] args) {
        final int iterations = Integer.parseInt(args[0]);
        final boolean waitOnSecond = args.length > 1 && args[1].equals("wait");
        check(SDL_Init(SDL_INIT_VIDEO), "SDL_Init");
        long dev = SDL_CreateGPUDevice(SDL_GPU_SHADERFORMAT_SPIRV, false, "vulkan");
        check(dev != 0, "SDL_CreateGPUDevice");
        System.out.println("revision=" + SDL_GetRevision() + " iterations=" + iterations + " waitOnSecond=" + waitOnSecond);

        String vs = "#version 450\nvoid main() { vec2 p = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2); gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0); }\n";
        // Deliberately slow: keeps the first submission in flight while the second one is submitted.
        String fs = "#version 450\nlayout(location=0) out vec4 o;\nvoid main() { float a = gl_FragCoord.x * 0.001;\n"
            + " for (int i = 0; i < 4000; i++) a = sin(a * 1.0001 + gl_FragCoord.y * 0.0001);\n o = vec4(a, 0.0, 0.0, 1.0); }\n";

        try (MemoryStack stack = MemoryStack.stackPush()) {
            long vsh = SDL_CreateGPUShader(dev, SDL_GPUShaderCreateInfo.calloc(stack).code(spirv(vs, shaderc_vertex_shader, "v"))
                .entrypoint(stack.UTF8("main")).format(SDL_GPU_SHADERFORMAT_SPIRV).stage(SDL_GPU_SHADERSTAGE_VERTEX));
            long fsh = SDL_CreateGPUShader(dev, SDL_GPUShaderCreateInfo.calloc(stack).code(spirv(fs, shaderc_fragment_shader, "f"))
                .entrypoint(stack.UTF8("main")).format(SDL_GPU_SHADERFORMAT_SPIRV).stage(SDL_GPU_SHADERSTAGE_FRAGMENT));
            check(vsh != 0 && fsh != 0, "shaders");
            SDL_GPUColorTargetDescription.Buffer targets = SDL_GPUColorTargetDescription.calloc(1, stack);
            targets.get(0).format(SDL_GPU_TEXTUREFORMAT_R8G8B8A8_UNORM);
            SDL_GPUGraphicsPipelineCreateInfo pci = SDL_GPUGraphicsPipelineCreateInfo.calloc(stack)
                .vertex_shader(vsh).fragment_shader(fsh).primitive_type(SDL_GPU_PRIMITIVETYPE_TRIANGLELIST);
            pci.target_info().color_target_descriptions(targets);
            long pipeline = SDL_CreateGPUGraphicsPipeline(dev, pci);
            check(pipeline != 0, "pipeline");
            long target = SDL_CreateGPUTexture(dev, SDL_GPUTextureCreateInfo.calloc(stack).type(SDL_GPU_TEXTURETYPE_2D)
                .format(SDL_GPU_TEXTUREFORMAT_R8G8B8A8_UNORM).usage(SDL_GPU_TEXTUREUSAGE_COLOR_TARGET)
                .width(1024).height(1024).layer_count_or_depth(1).num_levels(1));
            check(target != 0, "target");

            long start = System.nanoTime();
            int stillRunningAtRelease = 0;
            for (int i = 0; i < iterations; i++) {
                try (MemoryStack frame = MemoryStack.stackPush()) {
                    long cbA = SDL_AcquireGPUCommandBuffer(dev);
                    SDL_GPUColorTargetInfo.Buffer color = SDL_GPUColorTargetInfo.calloc(1, frame);
                    color.get(0).texture(target).load_op(SDL_GPU_LOADOP_CLEAR).store_op(SDL_GPU_STOREOP_STORE);
                    long pass = SDL_BeginGPURenderPass(cbA, color, null);
                    SDL_BindGPUGraphicsPipeline(pass, pipeline);
                    SDL_DrawGPUPrimitives(pass, 3, 1, 0, 0);
                    SDL_EndGPURenderPass(pass);
                    long fenceA = SDL_SubmitGPUCommandBufferAndAcquireFence(cbA);
                    check(fenceA != 0, "submit A");
                    if (!SDL_QueryGPUFence(dev, fenceA)) stillRunningAtRelease++;
                    SDL_ReleaseGPUFence(dev, fenceA); // early release, as Angelica does

                    long cbB = SDL_AcquireGPUCommandBuffer(dev);
                    long fenceB = SDL_SubmitGPUCommandBufferAndAcquireFence(cbB); // takes a fence from the pool
                    check(fenceB != 0, "submit B");
                    if (waitOnSecond) {
                        PointerBuffer fences = frame.pointers(fenceB);
                        check(SDL_WaitForGPUFences(dev, true, fences), "wait B");
                    }
                    SDL_ReleaseGPUFence(dev, fenceB);
                }
            }
            check(SDL_WaitForGPUIdle(dev), "wait idle");
            System.out.printf("DONE iterations=%d releasedWhileRunning=%d elapsed=%.1fs%n",
                iterations, stillRunningAtRelease, (System.nanoTime() - start) / 1e9);
        }
    }
}
