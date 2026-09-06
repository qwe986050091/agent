#!/bin/bash
# 无状态化改造端到端验证（在新服务器 82.156.243.32 上执行）
B=http://127.0.0.1:8081
A=http://127.0.0.1:8080
U="dt$(date +%s)"
P=test123456

echo "===== 1. 注册测试用户: $U ====="
REG=$(curl -s -X POST $B/api/auth/register -H 'Content-Type: application/json' \
  -d "{\"username\":\"$U\",\"password\":\"$P\",\"nickname\":\"部署测试\"}")
echo "$REG"
TOKEN=$(echo "$REG" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
if [ -z "$TOKEN" ]; then echo "!! 注册失败，终止"; exit 1; fi

echo; echo "===== 2. 第一轮对话：告知个人信息 ====="
curl -s -X POST $B/api/chat -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"message":"我叫李部署，是一名测试工程师，请记住我的名字和职业。"}'
echo

echo; echo "===== 3. 重启 agent（模拟服务重启/多实例切换） ====="
systemctl restart agent
sleep 15
curl -s -o /dev/null -w 'agent 重启后健康检查: %{http_code}\n' $A/api/agent/health

echo; echo "===== 4. 第二轮对话：agent 已重启，问刚才的信息（应从 backend 下发的历史恢复） ====="
curl -s -X POST $B/api/chat -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"message":"我刚才告诉你我叫什么名字？做什么工作？"}'
echo

echo; echo "===== 5. 挂载计算器技能（写 MySQL，不调 agent）后问数学题 ====="
curl -s -X POST $B/api/skills/mount -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"skillName":"calculator"}'
echo
curl -s -X POST $B/api/chat -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"message":"帮我算一下 123 乘以 45 等于多少？"}'
echo

echo; echo "===== 6. SSE 流式对话验证 ====="
curl -s -N -X POST $B/api/chat/stream -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"message":"用一句话介绍你自己"}' --max-time 60 | head -c 300
echo; echo

echo "===== 7. 数据库落库验证 ====="
mysql -uroot -p agent_chat -e \
  "select user_id, count(*) as msg_count from chat_message where user_id='$U' group by user_id; select user_id, skill_name from user_skill where user_id='$U'; select user_id, left(summary,60) as summary_preview, updated_at from agent_memory where user_id='$U';" \
  2>/dev/null

echo; echo "===== 8. agent 侧 ReAct 日志（应看到计算器工具调用 + 本次装配 Skill） ====="
journalctl -u agent --since '3 minutes ago' --no-pager | grep -E 'AgentFactory|Action|Final Answer' | tail -8

echo; echo "===== 9. 清理测试数据 ====="
mysql -uroot -p agent_chat -e \
  "delete from app_user where username='$U'; delete from chat_message where user_id='$U'; delete from user_skill where user_id='$U'; delete from agent_memory where user_id='$U';" 2>/dev/null
echo "测试用户 $U 数据已清理"
