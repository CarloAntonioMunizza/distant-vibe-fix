import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

public class VerifySamplers {
  static final String PKG = "com/gtnewhorizons/angelica/sdlgpu/";
  static final String SLOTS = PKG + "sampler/SamplerSlots";

  static int pushes(MethodNode m, int value) {
    int n = 0;
    for (AbstractInsnNode i : m.instructions) if (i instanceof IntInsnNode p && p.getOpcode() == Opcodes.BIPUSH && p.operand == value) n++;
    return n;
  }
  static boolean slots(AbstractInsnNode i, String helper) {
    return i instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC && call.owner.equals(SLOTS) && call.name.equals(helper);
  }
  static int slotCalls(MethodNode m) {
    int n = 0;
    for (AbstractInsnNode i : m.instructions) if (slots(i, "perStage")) n++;
    return n;
  }
  static AbstractInsnNode next(AbstractInsnNode i) {
    do i = i.getNext(); while (i != null && i.getOpcode() < 0);
    return i;
  }
  static MethodNode method(ClassNode c, String name) {
    return c.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
  }
  static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

  // Loads SamplerSlots next to a stand-in org.lwjgl.sdl.SDLVersion reporting the given revision.
  static int[] limits(byte[] slotsClass, String revision) throws Exception {
    ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    w.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "org/lwjgl/sdl/SDLVersion", null, "java/lang/Object", null);
    MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "SDL_GetRevision", "()Ljava/lang/String;", null, null);
    m.visitCode();
    if (revision == null) m.visitInsn(Opcodes.ACONST_NULL); else m.visitLdcInsn(revision);
    m.visitInsn(Opcodes.ARETURN);
    m.visitMaxs(0, 0);
    w.visitEnd();
    byte[] sdl = w.toByteArray();
    ClassLoader loader = new ClassLoader(VerifySamplers.class.getClassLoader()) {
      @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
        if (name.equals("org.lwjgl.sdl.SDLVersion")) return defineClass(name, sdl, 0, sdl.length);
        if (name.equals(SLOTS.replace('/', '.'))) return defineClass(name, slotsClass, 0, slotsClass.length);
        throw new ClassNotFoundException(name);
      }
    };
    Class<?> c = loader.loadClass(SLOTS.replace('/', '.'));
    int perStage = (int) c.getMethod("perStage").invoke(null);
    check((int) c.getMethod("perStage").invoke(null) == perStage, "cached limit");
    return new int[] { perStage, (int) c.getMethod("textureUnits").invoke(null) };
  }

  public static void main(String[] args) throws Exception {
    int classes = 0;
    try (JarFile jar = new JarFile(args[0])) {
      byte[] slotsClass = jar.getInputStream(Objects.requireNonNull(jar.getEntry(SLOTS + ".class"), "SamplerSlots")).readAllBytes();
      check(Arrays.equals(limits(slotsClass, "SDL-3.4.12-release-3.4.12 (angelica-samplers32)"), new int[] { 32, 32 }), "patched SDL limits");
      check(Arrays.equals(limits(slotsClass, "SDL-3.4.12-release-3.4.12"), new int[] { 16, 17 }), "stock SDL limits");
      check(Arrays.equals(limits(slotsClass, null), new int[] { 16, 17 }), "unknown SDL limits");
      System.out.println("PASS SamplerSlots: patched SDL 32/32, stock SDL 16/17, unknown SDL 16/17");

      for (JarEntry entry : Collections.list(jar.entries())) {
        String base = entry.getName().replaceFirst("^META-INF/versions/\\d+/", "");
        if (!base.startsWith(PKG) || !base.endsWith(".class")) continue;
        String type = base.substring(PKG.length(), base.length() - 6);
        if (!Set.of("frame/ContextState", "sampler/SamplerBinder", "resource/FBOClearTracker", "SDLGPURenderBackend").contains(type)) continue;
        ClassNode c = new ClassNode();
        new ClassReader(jar.getInputStream(entry)).accept(c, 0);
        for (MethodNode m : c.methods) new Analyzer<>(new BasicVerifier()).analyze(c.name, m);
        switch (type) {
          case "frame/ContextState" -> {
            check(c.fields.stream().anyMatch(f -> f.name.equals("MAX_SAMPLERS") && Integer.valueOf(32).equals(f.value)), "MAX_SAMPLERS");
            MethodNode init = method(c, "<init>");
            // Six sampler buffers/arrays grow to join the three existing 32-entry arrays;
            // UBO, SSBO, compute and EBO arrays keep 16 entries.
            check(pushes(init, 32) == 9 && pushes(init, 16) == 7, "ContextState sizes");
          }
          case "sampler/SamplerBinder" -> {
            MethodNode stage = method(c, "bindStageSamplers"), fallback = method(c, "bindFallbackForStage");
            int guards = 0;
            for (AbstractInsnNode i : stage.instructions) {
              if (next(i) != null && next(i).getOpcode() == Opcodes.IF_ICMPLE && slots(i, "perStage")) guards++;
            }
            check(guards == 1 && slotCalls(stage) == 1 && pushes(stage, 16) == 0, "stage limit");
            check(slotCalls(fallback) == 3 && pushes(fallback, 16) == 0, "fallback count");
          }
          case "resource/FBOClearTracker" -> {
            int clamps = 0;
            for (AbstractInsnNode i : method(c, "flushPendingClearsForBoundSamplers").instructions) {
              if (slots(i, "perStage") && next(i) instanceof MethodInsnNode min && min.name.equals("min")) clamps++;
            }
            check(clamps == 2 && pushes(method(c, "flushPendingClearsForBoundSamplers"), 16) == 0, "clear clamps");
          }
          case "SDLGPURenderBackend" -> {
            MethodNode m = c.methods.stream().filter(x -> x.name.equals("getInteger") && x.desc.equals("(I)I")).findFirst().orElseThrow();
            LookupSwitchInsnNode sw = null;
            for (AbstractInsnNode i : m.instructions) if (i instanceof LookupSwitchInsnNode s) sw = s;
            Map<Integer, AbstractInsnNode> targets = new HashMap<>();
            for (int k = 0; k < sw.keys.size(); k++) targets.put(sw.keys.get(k), next(sw.labels.get(k)));
            check(slots(targets.get(0x8872), "textureUnits") && slots(targets.get(0x8B4C), "textureUnits"), "texture unit limits");
            check(targets.get(0x8B4D) instanceof IntInsnNode p && p.operand == 32, "combined texture units");
          }
        }
        classes++;
        System.out.println("Verified operand stacks and SamplerSlots limits: " + entry.getName());
      }
    }
    if (classes != 7) throw new AssertionError("Missing class variant");
  }
}
