package com.example.agent.tools;

import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 时间日期工具类
 * LangChain4j 会扫描 @Tool 注解，自动把方法注册为可被 LLM 调用的工具
 * 方法上的 Javadoc 会作为工具描述传给模型，帮助模型理解什么时候用该工具
 */
@Component
public class DateTimeTools {

    /**
     * 获取当前日期（格式：yyyy-MM-dd）
     * 当用户询问"今天几号"、"日期是多少"、"星期几"、"今天是周几"等与日期相关的问题时调用
     *
     * @return 格式化后的当前日期字符串
     */
    @Tool("获取当前日期（格式：yyyy-MM-dd），用于回答'今天几号'、'星期几'等日期相关问题")
    public String getCurrentDate() {
        LocalDate now = LocalDate.now();
        String weekday = switch (now.getDayOfWeek()) {
            case MONDAY -> "星期一";
            case TUESDAY -> "星期二";
            case WEDNESDAY -> "星期三";
            case THURSDAY -> "星期四";
            case FRIDAY -> "星期五";
            case SATURDAY -> "星期六";
            case SUNDAY -> "星期日";
        };
        return "当前日期：" + now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + "，" + weekday;
    }

    /**
     * 获取当前时间（格式：HH:mm:ss）
     * 当用户询问"现在几点"、"时间是多少"等与时间相关的问题时调用
     *
     * @return 格式化后的当前时间字符串
     */
    @Tool("获取当前时间（格式：HH:mm:ss），用于回答'现在几点'、'什么时间'等时间相关问题")
    public String getCurrentTime() {
        return "当前时间：" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
    }

    /**
     * 获取当前完整的日期时间
     *
     * @return 格式化后的当前日期时间字符串
     */
    @Tool("获取当前完整的日期和时间")
    public String getCurrentDateTime() {
        return "当前时间：" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
