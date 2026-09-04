package com.example.agent.tools;

import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 时间日期工具类
 */
@Component
public class DateTimeTools {

    private static final Logger log = LoggerFactory.getLogger(DateTimeTools.class);

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
        String result = "当前日期：" + now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + "，" + weekday;
        log.info("[Tool] getCurrentDate() = {}", result);
        return result;
    }

    @Tool("获取当前时间（格式：HH:mm:ss），用于回答'现在几点'、'什么时间'等时间相关问题")
    public String getCurrentTime() {
        String result = "当前时间：" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        log.info("[Tool] getCurrentTime() = {}", result);
        return result;
    }

    @Tool("获取当前完整的日期和时间")
    public String getCurrentDateTime() {
        String result = "当前时间：" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        log.info("[Tool] getCurrentDateTime() = {}", result);
        return result;
    }
}
