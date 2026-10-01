import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

public class VerifyAlignment {
    public static void main(String[] args) throws Exception {
        int classes = 0;
        try (JarFile jar = new JarFile(args[0])) {
            for (JarEntry entry : Collections.list(jar.entries())) {
                if (!entry.getName().endsWith("com/gtnewhorizons/angelica/glsm/streaming/TessellatorStreamingDrawer.class")) continue;
                ClassNode node = new ClassNode();
                new ClassReader(jar.getInputStream(entry)).accept(node, 0);
                int calls = 0;
                for (MethodNode method : node.methods) {
                    new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
                    if (!method.name.equals("uploadAndDrawExtended")) continue;
                    for (AbstractInsnNode insn : method.instructions) {
                        if (insn instanceof MethodInsnNode call && call.owner.endsWith("/PersistentStreamingBuffer") && call.name.equals("upload")) {
                            if (!call.desc.equals("(Ljava/nio/ByteBuffer;II)I") || call.getPrevious().getOpcode() != Opcodes.ICONST_4)
                                throw new AssertionError("Missing quad alignment");
                            calls++;
                        }
                    }
                }
                if (calls != 1) throw new AssertionError("Expected one aligned upload");
                classes++;
                System.out.println("Verified operand stacks and aligned upload: " + entry.getName());
            }
        }
        if (classes != 2) throw new AssertionError("Missing class variant");
    }
}
