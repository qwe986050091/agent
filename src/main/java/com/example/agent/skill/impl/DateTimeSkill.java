package com.example.agent.skill.impl;

import com.example.agent.skill.Skill;
import com.example.agent.tools.DateTimeTools;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 时间日期技能包：把 DateTimeTools 里的所有 @Tool 方法打包成一个 Skill
 */
@Component
public class DateTimeSkill implements Skill {

    private final DateTimeTools dateTimeTools;

    public DateTimeSkill(DateTimeTools dateTimeTools) {
        this.dateTimeTools = dateTimeTools;
    }

    @Override
    public String name() {
        return "datetime";
    }

    @Override
    public String shortDescription() {
        return "时间日期技能：获取当前日期、时间、星期等";
    }

    @Override
    public String detailedDescription() {
        return """
                【时间日期技能已激活】
                你现在可以使用时间日期工具获取准确的当前时间。
                - 支持的工具：getCurrentDate(日期+星期)、getCurrentTime(时间)、getCurrentDateTime(完整日期时间)
                - 对于日期/时间类问题，必须调用工具，不要编造
                """;
    }

    @Override
    public List<Object> tools() {
        return List.of(dateTimeTools);
    }
}
