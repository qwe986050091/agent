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
    public String systemPrompt() {
        return """
                【计算技能】
                对于数学计算类问题（加减乘除、幂运算等），必须调用计算器工具，不能心算或猜测。
                可用工具：add(a,b)、subtract(a,b)、multiply(a,b)、divide(a,b)、power(base,exponent)。
                对于复杂表达式，可以分步调用多个工具完成计算。
                """;
    }

    @Override
    public List<Object> tools() {
        return List.of(calculatorTools);
    }
}
