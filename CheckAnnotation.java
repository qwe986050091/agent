import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.MemoryId;
import java.lang.reflect.Method;
import java.lang.annotation.Annotation;

public class CheckAnnotation {
    public interface TestAgent {
        String chat(@MemoryId String userId, @UserMessage String msg);
    }
    public static void main(String[] args) throws Exception {
        Method m = TestAgent.class.getMethod("chat", String.class, String.class);
        System.out.println("--- Method: " + m);
        
        // 参数 0: userId, 参数 1: msg
        Annotation[][] anns = m.getParameterAnnotations();
        for (int i = 0; i < anns.length; i++) {
            System.out.println("Param " + i + " annotations: " + anns[i].length);
            for (Annotation a : anns[i]) {
                System.out.println("  -> " + a.annotationType().getName() + " : " + a);
            }
        }
        
        // 方法级注解
        Annotation[] mAnns = m.getAnnotations();
        System.out.println("Method-level annotations: " + mAnns.length);
        for (Annotation a : mAnns) {
            System.out.println("  -> " + a.annotationType().getName());
        }
        
        // 检查 @UserMessage 有没有 value 默认值
        System.out.println("\n=== @UserMessage annotation attributes ===");
        Method vm = UserMessage.class.getMethod("value");
        System.out.println("value return type: " + vm.getReturnType().getName());
        System.out.println("value default: " + vm.getDefaultValue());
        Method dm = UserMessage.class.getMethod("delimiter");
        System.out.println("delimiter default: " + dm.getDefaultValue());
    }
}
