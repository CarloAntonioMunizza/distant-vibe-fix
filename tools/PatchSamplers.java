import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

// Lets the SDL GPU backend use 32 samplers per shader stage when SDL3 was built with
// MAX_TEXTURE_SAMPLERS_PER_STAGE 32 and SDL_VENDOR_INFO=angelica-samplers32. With any other SDL the
// added SamplerSlots helper reports SDL's stock 16, since stock SDL overflows its binding arrays past 16.
public class PatchSamplers {
  static final int OLD = 16, OLD_UNITS = 17, CAPACITY = 32;
  static final int GL_MAX_TEXTURE_IMAGE_UNITS = 0x8872, GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS = 0x8B4C;
  static final String PKG = "com/gtnewhorizons/angelica/sdlgpu/";
  static final String SLOTS = PKG + "sampler/SamplerSlots";
  static final String MARKER = "angelica-samplers32";
  static final Set<String> SAMPLER_ARRAYS = Set.of("lastFragSamplerTex", "lastFragSamplerSmp", "lastVertSamplerTex", "lastVertSamplerSmp");
  static final Map<String, Integer> EXPECTED = Map.of(
    PKG + "frame/ContextState", 7, PKG + "sampler/SamplerBinder", 5,
    PKG + "resource/FBOClearTracker", 2, PKG + "SDLGPURenderBackend", 2);
  static int patchedEntries;

  static boolean push(AbstractInsnNode i, int value) {
    return i instanceof IntInsnNode n && n.getOpcode() == Opcodes.BIPUSH && n.operand == value;
  }
  static AbstractInsnNode next(AbstractInsnNode i) {
    do i = i.getNext(); while (i != null && i.getOpcode() < 0);
    return i;
  }
  static void call(MethodNode m, AbstractInsnNode i, String helper) {
    m.instructions.set(i, new MethodInsnNode(Opcodes.INVOKESTATIC, SLOTS, helper, "()I", false));
  }

  // Binding buffers always hold 32 entries; how many are bound is decided at runtime.
  static int contextState(ClassNode c) {
    int hits = 0;
    for (FieldNode f : c.fields) {
      if (f.name.equals("MAX_SAMPLERS") && Integer.valueOf(OLD).equals(f.value)) { f.value = CAPACITY; hits++; }
    }
    for (MethodNode m : c.methods) {
      if (!m.name.equals("<init>")) continue;
      for (AbstractInsnNode i : m.instructions.toArray()) {
        if (!push(i, OLD)) continue;
        AbstractInsnNode n = next(i);
        boolean calloc = n instanceof MethodInsnNode call && call.owner.equals("org/lwjgl/sdl/SDL_GPUTextureSamplerBinding") && call.name.equals("calloc");
        boolean array = n instanceof IntInsnNode a && a.getOpcode() == Opcodes.NEWARRAY && a.operand == Opcodes.T_LONG
          && next(n) instanceof FieldInsnNode put && put.getOpcode() == Opcodes.PUTFIELD && SAMPLER_ARRAYS.contains(put.name);
        if (calloc || array) { ((IntInsnNode) i).operand = CAPACITY; hits++; }
      }
    }
    return hits;
  }

  static int samplerBinder(ClassNode c) {
    int hits = 0;
    String message = "samplers; MAX=" + OLD, replacement = "samplers; exceeds the SDL per-stage sampler limit";
    for (MethodNode m : c.methods) {
      boolean limits = m.name.equals("bindStageSamplers") || m.name.equals("bindFallbackForStage");
      for (AbstractInsnNode i : m.instructions.toArray()) {
        if (limits && push(i, OLD)) { call(m, i, "perStage"); hits++; }
        if (i instanceof LdcInsnNode ldc && ldc.cst instanceof String s && s.contains(message)) {
          ldc.cst = s.replace(message, replacement); hits++;
        }
        if (i instanceof InvokeDynamicInsnNode indy) {
          for (int a = 0; a < indy.bsmArgs.length; a++) {
            if (indy.bsmArgs[a] instanceof String s && s.contains(message)) {
              indy.bsmArgs[a] = s.replace(message, replacement); hits++;
            }
          }
        }
      }
    }
    return hits;
  }

  static int fboClearTracker(ClassNode c) {
    int hits = 0;
    for (MethodNode m : c.methods) {
      if (!m.name.equals("flushPendingClearsForBoundSamplers")) continue;
      for (AbstractInsnNode i : m.instructions.toArray()) {
        if (push(i, OLD) && next(i) instanceof MethodInsnNode min && min.owner.equals("java/lang/Math") && min.name.equals("min")) {
          call(m, i, "perStage"); hits++;
        }
      }
    }
    return hits;
  }

  static int renderBackend(ClassNode c) {
    int hits = 0;
    for (MethodNode m : c.methods) {
      if (!m.name.equals("getInteger") || !m.desc.equals("(I)I")) continue;
      for (AbstractInsnNode i : m.instructions.toArray()) {
        if (!(i instanceof LookupSwitchInsnNode sw)) continue;
        for (int k = 0; k < sw.keys.size(); k++) {
          int key = sw.keys.get(k);
          if (key != GL_MAX_TEXTURE_IMAGE_UNITS && key != GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS) continue;
          AbstractInsnNode target = next(sw.labels.get(k));
          if (!push(target, OLD_UNITS)) throw new AssertionError("Unexpected texture unit limit for " + key);
          call(m, target, "textureUnits"); hits++;
        }
      }
    }
    return hits;
  }

  // public final class SamplerSlots {
  //   static int perStage() { 32 if SDL_GetRevision() contains MARKER, else 16; logged once }
  //   static int textureUnits() { perStage() == 32 ? 32 : 17 }
  // }
  static byte[] samplerSlots() throws AnalyzerException {
    ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
      @Override protected String getCommonSuperClass(String a, String b) { return "java/lang/Object"; }
    };
    w.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, SLOTS, null, "java/lang/Object", null);
    w.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "perStage", "I", null, null).visitEnd();

    MethodVisitor m = w.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null);
    m.visitCode();
    m.visitVarInsn(Opcodes.ALOAD, 0);
    m.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
    m.visitInsn(Opcodes.RETURN);
    m.visitMaxs(0, 0);
    m.visitEnd();

    m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "perStage", "()I", null, null);
    Label store = new Label(), done = new Label();
    m.visitCode();
    m.visitFieldInsn(Opcodes.GETSTATIC, SLOTS, "perStage", "I");
    m.visitVarInsn(Opcodes.ISTORE, 0);
    m.visitVarInsn(Opcodes.ILOAD, 0);
    m.visitJumpInsn(Opcodes.IFNE, done);
    m.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/sdl/SDLVersion", "SDL_GetRevision", "()Ljava/lang/String;", false);
    m.visitVarInsn(Opcodes.ASTORE, 1);
    m.visitIntInsn(Opcodes.BIPUSH, OLD);
    m.visitVarInsn(Opcodes.ISTORE, 0);
    m.visitVarInsn(Opcodes.ALOAD, 1);
    m.visitJumpInsn(Opcodes.IFNULL, store);
    m.visitVarInsn(Opcodes.ALOAD, 1);
    m.visitLdcInsn(MARKER);
    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "contains", "(Ljava/lang/CharSequence;)Z", false);
    m.visitJumpInsn(Opcodes.IFEQ, store);
    m.visitIntInsn(Opcodes.BIPUSH, CAPACITY);
    m.visitVarInsn(Opcodes.ISTORE, 0);
    m.visitLabel(store);
    m.visitVarInsn(Opcodes.ILOAD, 0);
    m.visitFieldInsn(Opcodes.PUTSTATIC, SLOTS, "perStage", "I");
    m.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
    m.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
    m.visitInsn(Opcodes.DUP);
    m.visitLdcInsn("[Angelica-SDLGPU] Sampler slots per shader stage: ");
    m.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
    m.visitVarInsn(Opcodes.ILOAD, 0);
    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(I)Ljava/lang/StringBuilder;", false);
    m.visitLdcInsn(" (SDL revision: ");
    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
    m.visitVarInsn(Opcodes.ALOAD, 1);
    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
    m.visitLdcInsn(")");
    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
    m.visitLabel(done);
    m.visitVarInsn(Opcodes.ILOAD, 0);
    m.visitInsn(Opcodes.IRETURN);
    m.visitMaxs(0, 0);
    m.visitEnd();

    m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "textureUnits", "()I", null, null);
    Label stock = new Label();
    m.visitCode();
    m.visitMethodInsn(Opcodes.INVOKESTATIC, SLOTS, "perStage", "()I", false);
    m.visitIntInsn(Opcodes.BIPUSH, CAPACITY);
    m.visitJumpInsn(Opcodes.IF_ICMPNE, stock);
    m.visitIntInsn(Opcodes.BIPUSH, CAPACITY);
    m.visitInsn(Opcodes.IRETURN);
    m.visitLabel(stock);
    m.visitIntInsn(Opcodes.BIPUSH, OLD_UNITS);
    m.visitInsn(Opcodes.IRETURN);
    m.visitMaxs(0, 0);
    m.visitEnd();
    w.visitEnd();
    return verify(w.toByteArray());
  }

  static byte[] verify(byte[] bytes) throws AnalyzerException {
    ClassNode verified = new ClassNode();
    new ClassReader(bytes).accept(verified, 0);
    for (MethodNode m : verified.methods) new Analyzer<>(new BasicVerifier()).analyze(verified.name, m);
    return bytes;
  }

  static byte[] patch(String name, byte[] bytes) throws Exception {
    ClassNode c = new ClassNode();
    new ClassReader(bytes).accept(c, 0);
    int hits = switch (c.name.substring(PKG.length())) {
      case "frame/ContextState" -> contextState(c);
      case "sampler/SamplerBinder" -> samplerBinder(c);
      case "resource/FBOClearTracker" -> fboClearTracker(c);
      case "SDLGPURenderBackend" -> renderBackend(c);
      default -> throw new AssertionError(c.name);
    };
    if (hits != EXPECTED.get(c.name)) throw new AssertionError("Expected " + EXPECTED.get(c.name) + " sites in " + name + ", found " + hits);
    // Replacements push one int in place of one int, so existing frames and max stack still hold.
    ClassWriter writer = new ClassWriter(0);
    c.accept(writer);
    patchedEntries++;
    return verify(writer.toByteArray());
  }

  public static void main(String[] args) throws Exception {
    try (JarFile jar = new JarFile(args[0]); JarOutputStream out = new JarOutputStream(Files.newOutputStream(Path.of(args[1])))) {
      if (jar.getEntry(SLOTS + ".class") != null) throw new AssertionError("Input is already patched");
      long time = 0;
      for (JarEntry entry : Collections.list(jar.entries())) {
        byte[] bytes = jar.getInputStream(entry).readAllBytes();
        String base = entry.getName().replaceFirst("^META-INF/versions/\\d+/", "");
        if (base.endsWith(".class") && EXPECTED.containsKey(base.substring(0, base.length() - 6))) {
          bytes = patch(entry.getName(), bytes);
          System.out.println("Patched and verified " + entry.getName());
        }
        time = entry.getTime();
        JarEntry copy = new JarEntry(entry.getName()); copy.setTime(entry.getTime());
        out.putNextEntry(copy); out.write(bytes); out.closeEntry();
      }
      JarEntry slots = new JarEntry(SLOTS + ".class"); slots.setTime(time);
      out.putNextEntry(slots); out.write(samplerSlots()); out.closeEntry();
      System.out.println("Added and verified " + slots.getName());
    }
    // Base and Java 17 variants of three classes; FBOClearTracker has no Java 17 variant.
    if (patchedEntries != 7) throw new AssertionError("Unexpected class count " + patchedEntries);
  }
}
