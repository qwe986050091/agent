package com.example.agent.controller;

import com.example.agent.agent.AgentFactory;
import com.example.agent.skill.Skill;
import com.example.agent.skill.SkillManager;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Agent REST API
 *
 * 接口一览：
 *   GET  /api/agent/health            健康检查
 *   GET  /api/agent/chat              对话（GET）
 *   POST /api/agent/chat              对话（POST）
 *   GET  /api/agent/skills            列出平台所有可用 Skill
 *   GET  /api/agent/skills/mounted    列出某用户已挂载的 Skill
 *   POST /api/agent/skills/mount      用户挂载 Skill
 *   POST /api/agent/skills/unmount    用户卸载 Skill
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentFactory agentFactory;
    private final SkillManager skillManager;

    public AgentController(AgentFactory agentFactory, SkillManager skillManager) {
        this.agentFactory = agentFactory;
        this.skillManager = skillManager;
    }

    // ==================== 对话接口 ====================

    @GetMapping("/chat")
    public ResponseEntity<?> chatGet(@RequestParam(defaultValue = "default") String userId,
                                     @RequestParam String message) {
        ResponseEntity<?> bad = requireMessage(message);
        if (bad != null) return bad;
        String answer = agentFactory.chat(userId, message);
        return ResponseEntity.ok(new ChatResponse(userId, message, answer));
    }

    @PostMapping("/chat")
    public ResponseEntity<?> chatPost(@RequestBody ChatRequest request) {
        if (request == null) {
            return ResponseEntity.badRequest().body(new ErrorResponse("BAD_REQUEST", "请求体为空"));
        }
        String uid = request.getUserId() == null ? "default" : request.getUserId();
        String msg = request.getMessage();
        ResponseEntity<?> bad = requireMessage(msg);
        if (bad != null) return bad;
        String answer = agentFactory.chat(uid, msg);
        return ResponseEntity.ok(new ChatResponse(uid, msg, answer));
    }

    @GetMapping("/health")
    public String health() {
        return "Simple Agent is running! Powered by LangChain4j + Spring Boot (Dynamic Skill Mode).";
    }

    // ==================== Skill 管理接口 ====================

    /** 列出平台所有可用 Skill */
    @GetMapping("/skills")
    public ResponseEntity<?> listAllSkills() {
        Collection<Skill> skills = skillManager.listAll();
        List<SkillInfo> list = skills.stream()
                .map(s -> new SkillInfo(s.name(), s.shortDescription(), s.detailedDescription()))
                .collect(Collectors.toList());
        return ResponseEntity.ok(list);
    }

    /** 列出某用户已挂载的 Skill */
    @GetMapping("/skills/mounted")
    public ResponseEntity<?> listMountedSkills(@RequestParam(defaultValue = "default") String userId) {
        Set<String> mounted = skillManager.getMountedSkillNames(userId);
        return ResponseEntity.ok(new MountedSkillsResponse(userId, mounted));
    }

    /** 用户挂载 Skill */
    @PostMapping("/skills/mount")
    public ResponseEntity<?> mountSkill(@RequestBody MountRequest request) {
        if (request == null || request.getUserId() == null || request.getSkillName() == null) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("BAD_REQUEST", "userId 和 skillName 必填"));
        }
        boolean ok = skillManager.mountSkill(request.getUserId(), request.getSkillName());
        if (ok) {
            return ResponseEntity.ok(new ErrorResponse("OK",
                    "用户 " + request.getUserId() + " 挂载 Skill " + request.getSkillName() + " 成功"));
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("SKILL_NOT_FOUND", "Skill 不存在: " + request.getSkillName()));
    }

    /** 用户卸载 Skill */
    @PostMapping("/skills/unmount")
    public ResponseEntity<?> unmountSkill(@RequestBody MountRequest request) {
        if (request == null || request.getUserId() == null || request.getSkillName() == null) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("BAD_REQUEST", "userId 和 skillName 必填"));
        }
        boolean ok = skillManager.unmountSkill(request.getUserId(), request.getSkillName());
        if (ok) {
            return ResponseEntity.ok(new ErrorResponse("OK",
                    "用户 " + request.getUserId() + " 卸载 Skill " + request.getSkillName() + " 成功"));
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("NOT_MOUNTED", "用户未挂载该 Skill: " + request.getSkillName()));
    }

    /**
     * 用户上传 Skill（JSON 配置）—— 动态新增能力的核心入口
     *
     * 请求体示例：
     * {
     *   "name": "weather_time_report",
     *   "shortDescription": "生成天气时间报告",
     *   "detailedDescription": "查询天气和时间，生成一份报告",
     *   "systemPrompt": "当用户要求生成天气时间报告时，必须：1.调用 getWeather 2.调用 getCurrentTime 3.生成文档",
     *   "toolNames": ["calculatorTools", "dateTimeTools"]
     * }
     */
    @PostMapping("/skills/upload")
    public ResponseEntity<?> uploadSkill(@RequestBody String json) {
        if (json == null || json.trim().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("BAD_REQUEST", "请求体 JSON 不能为空"));
        }
        try {
            Skill skill = skillManager.registerFromJson(json);
            return ResponseEntity.ok(new ErrorResponse("OK",
                    "Skill 上传成功: " + skill.name() +
                            "，包含 " + skill.tools().size() + " 个 Tool"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("INVALID_SKILL", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("UPLOAD_FAILED", "Skill 上传失败: " + e.getMessage()));
        }
    }

    // ==================== 入参校验 ====================

    private ResponseEntity<?> requireMessage(String message) {
        if (message == null || message.trim().isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse("EMPTY_MESSAGE", "参数 message 不能为空或纯空白字符"));
        }
        return null;
    }

    // ==================== DTO ====================

    public static class ChatRequest {
        private String userId = "default";
        private String message;
        public ChatRequest() {}
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }

    public static class ChatResponse {
        private String userId;
        private String question;
        private String answer;
        public ChatResponse() {}
        public ChatResponse(String userId, String question, String answer) {
            this.userId = userId; this.question = question; this.answer = answer;
        }
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public String getQuestion() { return question; }
        public void setQuestion(String question) { this.question = question; }
        public String getAnswer() { return answer; }
        public void setAnswer(String answer) { this.answer = answer; }
    }

    public static class SkillInfo {
        private String name;
        private String shortDescription;
        private String detailedDescription;
        public SkillInfo() {}
        public SkillInfo(String name, String shortDescription, String detailedDescription) {
            this.name = name;
            this.shortDescription = shortDescription;
            this.detailedDescription = detailedDescription;
        }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getShortDescription() { return shortDescription; }
        public void setShortDescription(String d) { this.shortDescription = d; }
        public String getDetailedDescription() { return detailedDescription; }
        public void setDetailedDescription(String d) { this.detailedDescription = d; }
    }

    public static class MountRequest {
        private String userId;
        private String skillName;
        public MountRequest() {}
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public String getSkillName() { return skillName; }
        public void setSkillName(String skillName) { this.skillName = skillName; }
    }

    public static class MountedSkillsResponse {
        private String userId;
        private Set<String> mountedSkills;
        public MountedSkillsResponse() {}
        public MountedSkillsResponse(String userId, Set<String> mountedSkills) {
            this.userId = userId;
            this.mountedSkills = mountedSkills;
        }
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public Set<String> getMountedSkills() { return mountedSkills; }
        public void setMountedSkills(Set<String> mountedSkills) { this.mountedSkills = mountedSkills; }
    }

    public static class ErrorResponse {
        private String code;
        private String message;
        public ErrorResponse() {}
        public ErrorResponse(String code, String message) {
            this.code = code; this.message = message;
        }
        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }
}
