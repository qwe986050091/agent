package com.example.agent.skill.impl;

import com.example.agent.skill.Skill;
import com.example.agent.tools.CalculatorTools;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 计算技能包：把 CalculatorTools 里的所有 @Tool 方法打包成一个 Skill
 */
@Component
public class CalculatorSkill implements Skill {

    private final CalculatorTools calculatorTools;

    public CalculatorSkill(CalculatorTools calculatorTools) {
        this.calculatorTools = calculatorTools;
    }

    @Override
    public String name() {
        return "calculator";
    }

    @Override
    public String shortDescription() {
        return "数学计算技能：加减乘除、幂运算等";
    }

    @Override
    public String detailedDescription() {
        return """
                【计算技能已激活】
                你现在可以使用数学计算工具。
                - 对于任何数学运算，必须调用工具，不要心算或猜测
                - 支持的工具：add(加)、subtract(减)、multiply(乘)、divide(除)、power(幂)
                - 注意：除数不能为 0
                """;
    }

    @Override
    public List<Object> tools() {
        return List.of(calculatorTools);
    }
}
