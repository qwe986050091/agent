package com.example.agent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 评测数据集：一组 {@link EvalCase}，从 JSON 数组文件加载。
 *
 * <p>位置支持两种前缀：
 * <ul>
 *   <li>{@code classpath:...} —— 打进 jar 的默认数据集（{@code eval/dataset/agent-eval-v1.json}）；</li>
 *   <li>普通路径 —— 外部数据集文件（便于反馈反哺后手工扩充、以及「版本化数据集」对比）。</li>
 * </ul>
 */
public record EvalDataset(String name, List<EvalCase> cases) {

    public EvalDataset {
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    /** 用例总数 */
    public int size() {
        return cases.size();
    }

    /**
     * 加载数据集并做最小 schema 校验（空集/重复 id/缺关键字段直接失败，
     * 把「数据集本身写错」挡在跑评测之前——这也是 CI 离线档的一部分）。
     */
    public static EvalDataset load(String location, String name, ObjectMapper mapper) throws IOException {
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("评测数据集路径不能为空");
        }
        List<EvalCase> cases = read(location, mapper);
        List<String> problems = validate(cases);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("评测数据集非法: " + String.join("; ", problems));
        }
        return new EvalDataset(name == null || name.isBlank() ? location : name, cases);
    }

    static List<EvalCase> read(String location, ObjectMapper mapper) throws IOException {
        if (location.startsWith("classpath:")) {
            String path = location.substring("classpath:".length());
            try (InputStream in = new ClassPathResource(path).getInputStream()) {
                return mapper.readValue(in, mapper.getTypeFactory()
                        .constructCollectionType(List.class, EvalCase.class));
            }
        }
        try (InputStream in = Files.newInputStream(Path.of(location))) {
            return mapper.readValue(in, mapper.getTypeFactory()
                    .constructCollectionType(List.class, EvalCase.class));
        }
    }

    /** 返回问题清单（空=合法）；不抛异常，便于 schema 单测直接断言 */
    static List<String> validate(List<EvalCase> cases) {
        List<String> problems = new ArrayList<>();
        if (cases == null || cases.isEmpty()) {
            problems.add("数据集为空");
            return problems;
        }
        Set<String> ids = new HashSet<>();
        for (EvalCase c : cases) {
            if (c == null) {
                problems.add("存在 null 用例");
                continue;
            }
            if (c.id() == null || c.id().isBlank()) {
                problems.add("存在缺少 id 的用例");
                continue;
            }
            if (!ids.add(c.id())) {
                problems.add("用例 id 重复: " + c.id());
            }
            if (c.input() == null || c.input().isBlank()) {
                problems.add("用例 " + c.id() + " 缺少 input");
            }
            if (c.category() == null || c.category().isBlank()) {
                problems.add("用例 " + c.id() + " 缺少 category");
            } else if (!List.of(EvalCase.CATEGORY_RAG_QA, EvalCase.CATEGORY_TOOL_CALL,
                    EvalCase.CATEGORY_SAFETY, EvalCase.CATEGORY_PLAIN).contains(c.category())) {
                problems.add("用例 " + c.id() + " category 非法: " + c.category());
            }
            boolean hasAssertion = c.refuses() || c.checksTools() || c.checksCitations() || c.checksPoints();
            if (!hasAssertion) {
                problems.add("用例 " + c.id() + " 没有任何断言目标");
            }
        }
        return problems;
    }
}
