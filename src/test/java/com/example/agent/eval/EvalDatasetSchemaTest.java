package com.example.agent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 评测数据集 schema 校验（CI 离线档的一部分）：把「数据集本身写错」挡在跑评测之前。
 *
 * <p>断言默认数据集（classpath:eval/dataset/agent-eval-v1.json）：
 * 能解析、条数达标、id 唯一、类别合法、每条都有断言目标。
 */
class EvalDatasetSchemaTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void defaultDatasetIsLoadableAndWellFormed() throws Exception {
        EvalDataset dataset = EvalDataset.load(
                "classpath:eval/dataset/agent-eval-v1.json", "agent-eval-v1", mapper);

        assertThat(dataset.size()).isGreaterThanOrEqualTo(60);
        assertThat(dataset.cases()).extracting(EvalCase::id).doesNotHaveDuplicates();
        assertThat(dataset.cases()).allSatisfy(c -> {
            assertThat(c.input()).isNotBlank();
            assertThat(c.category()).isIn(EvalCase.CATEGORY_RAG_QA, EvalCase.CATEGORY_TOOL_CALL,
                    EvalCase.CATEGORY_SAFETY, EvalCase.CATEGORY_PLAIN);
            assertThat(c.refuses() || c.checksTools() || c.checksCitations() || c.checksPoints())
                    .as("用例 %s 必须有断言目标", c.id()).isTrue();
        });
    }

    @Test
    void datasetCoversAllFourCategories() throws Exception {
        EvalDataset dataset = EvalDataset.load(
                "classpath:eval/dataset/agent-eval-v1.json", "agent-eval-v1", mapper);
        assertThat(dataset.cases()).extracting(EvalCase::category)
                .contains(EvalCase.CATEGORY_RAG_QA, EvalCase.CATEGORY_TOOL_CALL,
                        EvalCase.CATEGORY_SAFETY, EvalCase.CATEGORY_PLAIN);
    }

    @Test
    void validationRejectsDuplicateIdAndMissingAssertion() throws Exception {
        String bad = """
                [
                  {"id":"dup","category":"plain","input":"a","requiredPoints":["x"]},
                  {"id":"dup","category":"plain","input":"b","requiredPoints":["y"]},
                  {"id":"no-assertion","category":"plain","input":"c"}
                ]
                """;
        List<EvalCase> cases = mapper.readValue(bad, mapper.getTypeFactory()
                .constructCollectionType(List.class, EvalCase.class));
        List<String> problems = EvalDataset.validate(cases);
        assertThat(problems).anyMatch(p -> p.contains("id 重复"));
        assertThat(problems).anyMatch(p -> p.contains("no-assertion") && p.contains("断言目标"));
    }

    @Test
    void missingFieldsAreNormalizedToEmptyLists() throws Exception {
        EvalCase c = mapper.readValue(
                "{\"id\":\"x\",\"category\":\"plain\",\"input\":\"hi\",\"requiredPoints\":[\"ok\"]}",
                EvalCase.class);
        assertThat(c.skills()).isEmpty();
        assertThat(c.expectedTools()).isEmpty();
        assertThat(c.refuses()).isFalse();
    }

    @Test
    void unknownLocationFails() {
        assertThatThrownBy(() -> EvalDataset.load("classpath:eval/dataset/does-not-exist.json",
                "x", mapper)).isInstanceOf(Exception.class);
    }
}
