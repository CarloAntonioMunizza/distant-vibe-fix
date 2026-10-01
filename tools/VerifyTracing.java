import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public class VerifyTracing extends ClassLoader {
  public enum Stage { VERTEX, FRAGMENT, COMPUTE }
  public static void main(String[] args) throws Exception {
    int tested=0;
    try(JarFile jar=new JarFile(args[0])) {
      for(JarEntry entry:Collections.list(jar.entries())) {
        if(!entry.getName().endsWith("net/coderbot/iris/pipeline/transform/ShaderTransformer.class"))continue;
        ClassNode n=new ClassNode();new ClassReader(jar.getInputStream(entry)).accept(n,0);
        MethodNode helper=n.methods.stream().filter(m->m.name.equals("invalidateExtractedArtifacts")).findFirst().orElseThrow();
        helper.access=Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC;
        for(AbstractInsnNode i:helper.instructions)if(i instanceof FieldInsnNode f){f.owner="VerifyTracing$Stage";f.desc="LVerifyTracing$Stage;";}
        ClassWriter w=new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V17,Opcodes.ACC_PUBLIC,"TracingHelper",null,"java/lang/Object",null);
        helper.accept(w);w.visitEnd();byte[] code=w.toByteArray();
        Class<?> c=new VerifyTracing().defineClass("TracingHelper",code,0,code.length);
        var method=c.getMethod("invalidateExtractedArtifacts",EnumMap.class,EnumMap.class);
        EnumMap<Stage,Object> result=new EnumMap<>(Stage.class), artifacts=new EnumMap<>(Stage.class);
        Object v=new Object(),f=new Object(); artifacts.put(Stage.VERTEX,v);artifacts.put(Stage.FRAGMENT,f);
        method.invoke(null,result,artifacts);
        if(artifacts.get(Stage.VERTEX)!=v||artifacts.get(Stage.FRAGMENT)!=f)throw new AssertionError("Normal shader trees changed");
        result.put(Stage.COMPUTE,"extracted pre-pass");method.invoke(null,result,artifacts);
        if(!artifacts.isEmpty())throw new AssertionError("Stale graphics trees retained");
        method.invoke(null,result,null);
        System.out.println("PASS unchanged sources, extracted sources, absent artifacts: "+entry.getName());tested++;
      }
    }
    if(tested!=2)throw new AssertionError();
  }
}
