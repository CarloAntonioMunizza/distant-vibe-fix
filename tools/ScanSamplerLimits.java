import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

// Lists sampler-limit sites (BIPUSH 16/17, limit messages, Iris SamplerLimits calls) in the SDL GPU backend
// and Iris sampler code. Diff the output for two Angelica releases before rebasing PatchSamplers: the patcher
// rejects missing sites, but a site added upstream would otherwise go unnoticed.
public class ScanSamplerLimits {
  public static void main(String[] args) throws Exception {
    TreeMap<String, Integer> sites = new TreeMap<>();
    try (JarFile jar = new JarFile(args[0])) {
      for (JarEntry entry : Collections.list(jar.entries())) {
        String name = entry.getName();
        if (!name.endsWith(".class") || name.startsWith("META-INF/")) continue;
        if (!(name.contains("angelica/sdlgpu/") || name.contains("iris/gl/program/") || name.contains("iris/samplers/") || name.contains("SamplerLimits"))) continue;
        ClassNode c = new ClassNode();
        new ClassReader(jar.getInputStream(entry).readAllBytes()).accept(c, 0);
        for (MethodNode m : c.methods) {
          for (AbstractInsnNode i : m.instructions) {
            String hit = null;
            if (i instanceof IntInsnNode p && p.getOpcode() == Opcodes.BIPUSH && (p.operand == 16 || p.operand == 17)) hit = "push" + p.operand;
            if (i instanceof LdcInsnNode l && l.cst instanceof String s && (s.contains("MAX=") || s.contains("sampler limit") || s.contains("texture units"))) hit = "str:" + s.replace('\n', ' ');
            if (i instanceof MethodInsnNode call && (call.name.contains("MaxTextureUnits") || call.owner.contains("SamplerLimits"))) hit = "call:" + call.owner + "." + call.name;
            if (hit != null) sites.merge(c.name + "#" + m.name + " " + hit, 1, Integer::sum);
          }
        }
      }
    }
    sites.forEach((site, count) -> System.out.println(count + " " + site));
  }
}
