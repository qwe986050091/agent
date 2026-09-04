package com.example.agent.tools;

import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 计算器工具类
 * 提供基础的加减乘除运算，避免 LLM 自己计算出错（LLM 在复杂数学上容易幻觉）
 */
@Component
public class CalculatorTools {

    private static final Logger log = LoggerFactory.getLogger(CalculatorTools.class);

    @Tool("计算两个数的和（加法），适用于 a + b")
    public double add(double a, double b) {
        double result = a + b;
        log.info("[Tool] add({}, {}) = {}", a, b, result);
        return result;
    }

    @Tool("计算两个数的差（减法），适用于 a - b")
    public double subtract(double a, double b) {
        double result = a - b;
        log.info("[Tool] subtract({}, {}) = {}", a, b, result);
        return result;
    }

    @Tool("计算两个数的积（乘法），适用于 a × b")
    public double multiply(double a, double b) {
        double result = a * b;
        log.info("[Tool] multiply({}, {}) = {}", a, b, result);
        return result;
    }

    @Tool("计算两个数的商（除法），适用于 a ÷ b，注意 b 不能为 0")
    public double divide(double a, double b) {
        if (b == 0) {
            log.warn("[Tool] divide({}, {}) 失败：除数为 0", a, b);
            throw new IllegalArgumentException("除数不能为 0");
        }
        double result = a / b;
        log.info("[Tool] divide({}, {}) = {}", a, b, result);
        return result;
    }

    @Tool("计算幂运算（乘方），即 base 的 exponent 次方")
    public double power(double base, double exponent) {
        double result = Math.pow(base, exponent);
        log.info("[Tool] power({}, {}) = {}", base, exponent, result);
        return result;
    }
}
