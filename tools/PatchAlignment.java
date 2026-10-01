import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.io.*;
import org.objectweb.asm.*;

public class PatchAlignment {
    static int patches;
    static byte[] patch(byte[] input) {
        ClassReader reader = new ClassReader(input);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        int[] changed = {0};
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exceptions) {
                MethodVisitor parent = super.visitMethod(access, name, desc, sig, exceptions);
                if (!name.equals("uploadAndDrawExtended")) return parent;
                return new MethodVisitor(Opcodes.ASM9, parent) {
                    public void visitMethodInsn(int op, String owner, String method, String descriptor, boolean itf) {
                        if (owner.equals("com/gtnewhorizons/angelica/glsm/streaming/PersistentStreamingBuffer")
                            && method.equals("upload") && descriptor.equals("(Ljava/nio/ByteBuffer;I)I")) {
                            super.visitInsn(Opcodes.ICONST_4);
                            descriptor = "(Ljava/nio/ByteBuffer;II)I";
                            changed[0]++;
                        }
                        super.visitMethodInsn(op, owner, method, descriptor, itf);
                    }
                };
            }
        }, 0);
        if (changed[0] != 1) throw new IllegalStateException("Expected one upload call, found " + changed[0]);
        patches++;
        return writer.toByteArray();
    }
    public static void main(String[] args) throws Exception {
        try (JarFile source = new JarFile(args[0]); JarOutputStream out = new JarOutputStream(Files.newOutputStream(Path.of(args[1])))) {
            for (JarEntry entry : Collections.list(source.entries())) {
                byte[] data;
                try (InputStream stream = source.getInputStream(entry)) { data = stream.readAllBytes(); }
                if (entry.getName().endsWith("com/gtnewhorizons/angelica/glsm/streaming/TessellatorStreamingDrawer.class")) {
                    data = patch(data);
                    System.out.println("Patched " + entry.getName());
                }
                JarEntry copy = new JarEntry(entry.getName());
                copy.setTime(entry.getTime());
                out.putNextEntry(copy);
                out.write(data);
                out.closeEntry();
            }
        }
        if (patches != 2) throw new IllegalStateException("Expected base and Java 17 variants, found " + patches);
    }
}
