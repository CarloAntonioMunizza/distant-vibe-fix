import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

public class PatchTracing {
  static int count;
  static byte[] patch(byte[] bytes) throws Exception {
    ClassNode node = new ClassNode();
    new ClassReader(bytes).accept(node, 0);
    int hits = 0;
    for (MethodNode method : node.methods) {
      if (!method.name.equals("transformInternal")) continue;
      for (AbstractInsnNode insn : method.instructions.toArray()) {
        if (insn instanceof MethodInsnNode call && call.name.equals("maybeExtractRwImageStores")) {
          if (!(call.getPrevious().getPrevious() instanceof VarInsnNode load) || load.getOpcode()!=Opcodes.ALOAD)
            throw new AssertionError("Unexpected result local");
          InsnList added = new InsnList();
          added.add(new VarInsnNode(Opcodes.ALOAD, load.var));
          added.add(new VarInsnNode(Opcodes.ALOAD, 3));
          added.add(new MethodInsnNode(Opcodes.INVOKESTATIC, node.name, "invalidateExtractedArtifacts", "(Ljava/util/EnumMap;Ljava/util/EnumMap;)V", false));
          method.instructions.insert(call, added);
          hits++;
        }
      }
    }
    if (hits!=1) throw new AssertionError("Expected one extraction call: "+hits);
    MethodNode helper = new MethodNode(Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC, "invalidateExtractedArtifacts", "(Ljava/util/EnumMap;Ljava/util/EnumMap;)V", null, null);
    String type = "net/coderbot/iris/pipeline/transform/PatchShaderType";
    Label end = new Label();
    helper.visitCode();
    helper.visitVarInsn(Opcodes.ALOAD, 1);
    helper.visitJumpInsn(Opcodes.IFNULL, end);
    helper.visitVarInsn(Opcodes.ALOAD, 0);
    helper.visitFieldInsn(Opcodes.GETSTATIC, type, "COMPUTE", "L"+type+";");
    helper.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/EnumMap", "containsKey", "(Ljava/lang/Object;)Z", false);
    helper.visitJumpInsn(Opcodes.IFEQ, end);
    for (String stage : List.of("VERTEX", "FRAGMENT")) {
      helper.visitVarInsn(Opcodes.ALOAD, 1);
      helper.visitFieldInsn(Opcodes.GETSTATIC, type, stage, "L"+type+";");
      helper.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/EnumMap", "remove", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
      helper.visitInsn(Opcodes.POP);
    }
    helper.visitLabel(end);
    helper.visitFrame(Opcodes.F_SAME,0,null,0,null);
    helper.visitInsn(Opcodes.RETURN);
    helper.visitMaxs(2,2);
    helper.visitEnd();
    node.methods.add(helper);
    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    node.accept(writer);
    byte[] result=writer.toByteArray();
    ClassNode verified=new ClassNode();
    new ClassReader(result).accept(verified,0);
    for(MethodNode m:verified.methods) new Analyzer<>(new BasicVerifier()).analyze(verified.name,m);
    count++;
    return result;
  }
  public static void main(String[] args) throws Exception {
    try(JarFile jar=new JarFile(args[0]); JarOutputStream out=new JarOutputStream(Files.newOutputStream(Path.of(args[1])))) {
      for(JarEntry entry:Collections.list(jar.entries())) {
        byte[] bytes=jar.getInputStream(entry).readAllBytes();
        if(entry.getName().endsWith("net/coderbot/iris/pipeline/transform/ShaderTransformer.class")) {
          bytes=patch(bytes);
          System.out.println("Patched and verified "+entry.getName());
        }
        JarEntry copy=new JarEntry(entry.getName()); copy.setTime(entry.getTime());
        out.putNextEntry(copy);out.write(bytes);out.closeEntry();
      }
    }
    if(count!=2)throw new AssertionError("Unexpected class count "+count);
  }
}
