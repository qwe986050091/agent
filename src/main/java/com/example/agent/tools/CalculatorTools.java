package com.example.agent.tools;

import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

/**
 * 计算器工具类
 * 提供基础的加减乘除运算，避免 LLM 自己计算出错（LLM 在复杂数学上容易幻觉）
 */
@Component
public class CalculatorTools {

    /**
     * 两个数相加
     * 当你需要计算两个数字之和时调用此工具
     *
     * @param a 第一个加数
     * @param b 第二个加数
     * @return 两数之和
     */
    @Tool("计算两个数的和（加法），适用于 a + b")
    public double add(double a, double b) {
        return a + b;
    }

    /**
     * 两个数相减
     *
     * @param a 被减数
     * @param b 减数
     * @return 两数之差
     */
    @Tool("计算两个数的差（减法），适用于 a - b")
    public double subtract(double a, double b) {
        return a - b;
    }

    /**
     * 两个数相乘
     *
     * @param a 第一个乘数
     * @param b 第二个乘数
     * @return 两数之积
     */
    @Tool("计算两个数的积（乘法），适用于 a × b")
    public double multiply(double a, double b) {
        return a * b;
    }

    /**
     * 两个数相除
     *
     * @param a 被除数
     * @param b 除数（不能为0）
     * @return 两数之商
     */
    @Tool("计算两个数的商（除法），适用于 a ÷ b，注意 b 不能为 0")
    public double divide(double a, double b) {
        if (b == 0) {
            throw new IllegalArgumentException("除数不能为 0");
        }
        return a / b;
    }

    /**
     * 计算一个数的幂
     *
     * @param base 底数
     * @param exponent 指数
     * @return base^exponent 的结果
     */
    @Tool("计算幂运算（乘方），即 base 的 exponent 次方")
    public double power(double base, double exponent) {
        return Math.pow(base, exponent);
    }
}
