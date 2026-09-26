package com.bank.aml.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

/**
 * Spring 组件构造器的可解析性检查。
 *
 * <p>
 * 要防的具体故障：一个 {@code @Component} 同时有「给 Spring 用的构造器」和「给测试用的构造器」时， 如果两个都没标
 * {@code @Autowired}，Spring 不会替你挑一个——它会退回无参实例化，然后抛 {@code No default constructor found}，
 * 整个应用起不来。
 * </p>
 *
 * <p>
 * 这个故障有个很坏的性质：**单元测试全绿**。不加载 Spring 上下文的测试根本碰不到它， 而加载上下文的集成测试需要 Docker，跑不起来时也看不出是这条原因。
 * 结果就是一次"代码风格整理"可以悄悄让服务无法启动， 而 CI 上真正红掉的只有那两个需要外部依赖的 job。 这里用纯反射把它提前拦下：不需要数据库，也不需要启动上下文。
 * </p>
 *
 * <p>
 * 反过来也检查了标了 {@code @Autowired} 的构造器必须是 public——标在包级构造器上会同样失效， 而且失败信息一样难懂。
 * </p>
 */
class SpringComponentWiringTest {

    private static final String BASE_PACKAGE = "com.bank.aml";

    @Test
    void everyComponentWithMultipleConstructorsMarksExactlyOneForSpring() {
        Set<String> inspected = new LinkedHashSet<>();
        List<String> problems = new ArrayList<>();

        for (Class<?> type : scanComponents()) {
            inspected.add(type.getName());
            Constructor<?>[] constructors = type.getDeclaredConstructors();
            if (constructors.length < 2) {
                // 只有一个构造器时 Spring 会自动采用，无需标注
                continue;
            }
            List<Constructor<?>> annotated = Arrays.stream(constructors)
                .filter(constructor -> constructor.isAnnotationPresent(Autowired.class))
                .toList();
            if (annotated.size() != 1) {
                problems.add(type.getName() + "：共 " + constructors.length + " 个构造器，标注 @Autowired 的有 " + annotated.size()
                        + " 个");
            }
            else if (!Modifier.isPublic(annotated.get(0).getModifiers())) {
                problems.add(type.getName() + "：@Autowired 标在了非 public 构造器上，Spring 调用不到");
            }
        }

        // 先确认扫描真的扫到了东西——否则这条测试会永远通过，比没有还糟
        assertFalse(inspected.isEmpty(), "没有扫描到任何组件，说明包名或过滤器失效，这条测试已失去意义");
        assertTrue(problems.isEmpty(),
                "以下组件无法被 Spring 稳定实例化（多构造器必须恰好标一个 public 的 @Autowired）：\n  - " + String.join("\n  - ", problems));
    }

    private static List<Class<?>> scanComponents() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));

        List<Class<?>> types = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(BASE_PACKAGE)) {
            String className = definition.getBeanClassName();
            if (className == null) {
                continue;
            }
            try {
                Class<?> type = Class.forName(className);
                if (!type.isInterface() && !Modifier.isAbstract(type.getModifiers())) {
                    types.add(type);
                }
            }
            catch (ClassNotFoundException | LinkageError ignored) {
                // 类加载不了就跳过：这里只关心能被 Spring 实例化的组件
            }
        }
        return types;
    }

}
