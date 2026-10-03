import java.nio.*;
import org.lwjgl.PointerBuffer;
import org.lwjgl.sdl.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.sdl.SDLError.SDL_GetError;
import static org.lwjgl.sdl.SDLGPU.*;
import static org.lwjgl.sdl.SDLInit.*;
import static org.lwjgl.util.shaderc.Shaderc.*;

/**
 * Binds N one-pixel textures to N fragment sampler slots through SDL GPU (Vulkan), where texture i
 * holds red = i + 1. The shader checks every slot for its own value and reports matches plus a
 * bitmask of wrong slots. Usage: SamplerSlotTest <numSamplers> <debugMode true|false>
 */
public class SamplerSlotTest {
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
        final int n = Integer.parseInt(args[0]);
        final boolean debug = Boolean.parseBoolean(args[1]);
        // Optional third argument "badusage": texture 0 lacks SAMPLER usage, a deliberate validation error.
        final boolean badUsage = args.length > 2 && args[2].equals("badusage");
        check(SDL_Init(SDL_INIT_VIDEO), "SDL_Init");
        long dev = SDL_CreateGPUDevice(SDL_GPU_SHADERFORMAT_SPIRV, debug, "vulkan");
        check(dev != 0, "SDL_CreateGPUDevice");
        System.out.println("driver=" + SDL_GetGPUDeviceDriver(dev) + " debug=" + debug + " samplers=" + n);

        StringBuilder fs = new StringBuilder("#version 450\n");
        for (int i = 0; i < n; i++) fs.append("layout(set=2, binding=").append(i).append(") uniform sampler2D t").append(i).append(";\n");
        fs.append("layout(location=0) out vec4 o;\nvoid main() {\n float ok = 0.0, lo = 0.0, hi = 0.0;\n");
        for (int i = 0; i < n; i++) {
            String bad = i < 16 ? "lo += " + (1 << i) + ".0;" : "hi += " + (1 << (i - 16)) + ".0;";
            fs.append(" if (int(round(texture(t").append(i).append(", vec2(0.5)).r * 255.0)) == ").append(i + 1)
              .append(") ok += 1.0; else ").append(bad).append('\n');
        }
        fs.append(" o = vec4(ok, lo, hi, 1.0);\n}\n");
        String vs = "#version 450\nvoid main() { vec2 p = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2); gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0); }\n";

        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer vsCode = spirv(vs, shaderc_vertex_shader, "test.vert");
            ByteBuffer fsCode = spirv(fs.toString(), shaderc_fragment_shader, "test.frag");
            long vsh = SDL_CreateGPUShader(dev, SDL_GPUShaderCreateInfo.calloc(stack).code(vsCode).entrypoint(stack.UTF8("main"))
                .format(SDL_GPU_SHADERFORMAT_SPIRV).stage(SDL_GPU_SHADERSTAGE_VERTEX));
            check(vsh != 0, "vertex shader");
            long fsh = SDL_CreateGPUShader(dev, SDL_GPUShaderCreateInfo.calloc(stack).code(fsCode).entrypoint(stack.UTF8("main"))
                .format(SDL_GPU_SHADERFORMAT_SPIRV).stage(SDL_GPU_SHADERSTAGE_FRAGMENT).num_samplers(n));
            if (fsh == 0) {
                System.out.println("RESULT REJECTED fragment shader with " + n + " samplers: " + SDL_GetError());
                return;
            }

            SDL_GPUColorTargetDescription.Buffer targets = SDL_GPUColorTargetDescription.calloc(1, stack);
            targets.get(0).format(SDL_GPU_TEXTUREFORMAT_R32G32B32A32_FLOAT);
            SDL_GPUGraphicsPipelineCreateInfo pci = SDL_GPUGraphicsPipelineCreateInfo.calloc(stack)
                .vertex_shader(vsh).fragment_shader(fsh).primitive_type(SDL_GPU_PRIMITIVETYPE_TRIANGLELIST);
            pci.target_info().color_target_descriptions(targets);
            long pipeline = SDL_CreateGPUGraphicsPipeline(dev, pci);
            check(pipeline != 0, "pipeline");

            long[] tex = new long[n];
            for (int i = 0; i < n; i++) {
                tex[i] = SDL_CreateGPUTexture(dev, SDL_GPUTextureCreateInfo.calloc(stack).type(SDL_GPU_TEXTURETYPE_2D)
                    .format(SDL_GPU_TEXTUREFORMAT_R8G8B8A8_UNORM).usage(badUsage && i == 0 ? SDL_GPU_TEXTUREUSAGE_COLOR_TARGET : SDL_GPU_TEXTUREUSAGE_SAMPLER)
                    .width(1).height(1).layer_count_or_depth(1).num_levels(1));
                check(tex[i] != 0, "texture " + i);
            }
            long target = SDL_CreateGPUTexture(dev, SDL_GPUTextureCreateInfo.calloc(stack).type(SDL_GPU_TEXTURETYPE_2D)
                .format(SDL_GPU_TEXTUREFORMAT_R32G32B32A32_FLOAT).usage(SDL_GPU_TEXTUREUSAGE_COLOR_TARGET)
                .width(1).height(1).layer_count_or_depth(1).num_levels(1));
            check(target != 0, "target");
            long sampler = SDL_CreateGPUSampler(dev, SDL_GPUSamplerCreateInfo.calloc(stack)
                .min_filter(SDL_GPU_FILTER_NEAREST).mag_filter(SDL_GPU_FILTER_NEAREST)
                .mipmap_mode(SDL_GPU_SAMPLERMIPMAPMODE_NEAREST)
                .address_mode_u(SDL_GPU_SAMPLERADDRESSMODE_CLAMP_TO_EDGE)
                .address_mode_v(SDL_GPU_SAMPLERADDRESSMODE_CLAMP_TO_EDGE)
                .address_mode_w(SDL_GPU_SAMPLERADDRESSMODE_CLAMP_TO_EDGE));
            check(sampler != 0, "sampler");

            long up = SDL_CreateGPUTransferBuffer(dev, SDL_GPUTransferBufferCreateInfo.calloc(stack)
                .usage(SDL_GPU_TRANSFERBUFFERUSAGE_UPLOAD).size(n * 4));
            long down = SDL_CreateGPUTransferBuffer(dev, SDL_GPUTransferBufferCreateInfo.calloc(stack)
                .usage(SDL_GPU_TRANSFERBUFFERUSAGE_DOWNLOAD).size(16));
            check(up != 0 && down != 0, "transfer buffers");
            ByteBuffer map = SDL_MapGPUTransferBuffer(dev, up, false, n * 4L);
            for (int i = 0; i < n; i++) map.put((byte) (i + 1)).put((byte) 0).put((byte) 0).put((byte) 255);
            SDL_UnmapGPUTransferBuffer(dev, up);

            long cb = SDL_AcquireGPUCommandBuffer(dev);
            long copy = SDL_BeginGPUCopyPass(cb);
            for (int i = 0; i < n; i++) {
                SDL_UploadToGPUTexture(copy, SDL_GPUTextureTransferInfo.calloc(stack).transfer_buffer(up).offset(i * 4),
                    SDL_GPUTextureRegion.calloc(stack).texture(tex[i]).w(1).h(1).d(1), false);
            }
            SDL_EndGPUCopyPass(copy);

            SDL_GPUColorTargetInfo.Buffer color = SDL_GPUColorTargetInfo.calloc(1, stack);
            color.get(0).texture(target).load_op(SDL_GPU_LOADOP_CLEAR).store_op(SDL_GPU_STOREOP_STORE);
            long pass = SDL_BeginGPURenderPass(cb, color, null);
            SDL_BindGPUGraphicsPipeline(pass, pipeline);
            SDL_GPUTextureSamplerBinding.Buffer bindings = SDL_GPUTextureSamplerBinding.calloc(n, stack);
            for (int i = 0; i < n; i++) bindings.get(i).texture(tex[i]).sampler(sampler);
            SDL_BindGPUFragmentSamplers(pass, 0, bindings);
            SDL_DrawGPUPrimitives(pass, 3, 1, 0, 0);
            SDL_EndGPURenderPass(pass);

            copy = SDL_BeginGPUCopyPass(cb);
            SDL_DownloadFromGPUTexture(copy, SDL_GPUTextureRegion.calloc(stack).texture(target).w(1).h(1).d(1),
                SDL_GPUTextureTransferInfo.calloc(stack).transfer_buffer(down));
            SDL_EndGPUCopyPass(copy);
            long fence = SDL_SubmitGPUCommandBufferAndAcquireFence(cb);
            check(fence != 0, "submit");
            PointerBuffer fences = stack.pointers(fence);
            check(SDL_WaitForGPUFences(dev, true, fences), "wait");
            SDL_ReleaseGPUFence(dev, fence);

            FloatBuffer out = SDL_MapGPUTransferBuffer(dev, down, false, 16).order(ByteOrder.nativeOrder()).asFloatBuffer();
            int ok = Math.round(out.get(0));
            long badMask = (long) Math.round(out.get(1)) | ((long) Math.round(out.get(2)) << 16);
            SDL_UnmapGPUTransferBuffer(dev, down);
            System.out.println("RESULT " + (ok == n && badMask == 0 ? "PASS" : "FAIL") + " correct=" + ok + "/" + n
                + " wrongSlotsMask=0x" + Long.toHexString(badMask));
        }
    }
}
