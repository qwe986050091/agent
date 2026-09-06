#!/bin/bash
# P0-2 鉴权验证（服务已部署完成，仅做验证）
TOKEN_VAL=$(grep -o 'AGENT_INTERNAL_TOKEN=[^ ]*' /etc/systemd/system/agent.service | cut -d= -f2)

echo "===== 1. 直连 agent 8080 鉴权验证 ====="
echo -n "无 token POST /api/agent/chat（期望 401）: "
curl -s -o /tmp/r1 -w '%{http_code}\n' -X POST http://127.0.0.1:8080/api/agent/chat -H 'Content-Type: application/json' -d '{"userId":"x","message":"hi"}'
cat /tmp/r1; echo
echo -n "错误 token POST /api/agent/chat（期望 401）: "
curl -s -o /tmp/r2 -w '%{http_code}\n' -X POST http://127.0.0.1:8080/api/agent/chat -H 'Content-Type: application/json' -H 'X-Agent-Token: wrong-token' -d '{"userId":"x","message":"hi"}'
cat /tmp/r2; echo
echo -n "无 token GET /api/agent/tools（期望 401）: "
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/api/agent/tools
echo -n "无 token POST RAG 索引（期望 401）: "
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://127.0.0.1:8080/api/agent/rag/index-conversation -H 'Content-Type: application/json' -d '{"userId":"x","role":"user","content":"y"}'
echo -n "无 token GET /api/agent/health（期望 200，探活放行）: "
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/api/agent/health
echo -n "正确 token POST /api/agent/chat（期望 200）: "
curl -s -o /tmp/r3 -w '%{http_code}\n' --max-time 90 -X POST http://127.0.0.1:8080/api/agent/chat -H 'Content-Type: application/json' -H "X-Agent-Token: $TOKEN_VAL" -d '{"userId":"authtest","message":"用一句话说你好"}'
cat /tmp/r3; echo

echo; echo "===== 2. 经 backend 端到端（backend 自动注入 token） ====="
B=http://127.0.0.1:8081
U="at$(date +%s)"
REG=$(curl -s -X POST $B/api/auth/register -H 'Content-Type: application/json' -d "{\"username\":\"$U\",\"password\":\"test123456\"}")
T=$(echo "$REG" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
if [ -z "$T" ]; then echo "注册失败: $REG"; else
  echo -n "backend 同步对话（期望 200）: "
  curl -s -o /tmp/r4 -w '%{http_code}\n' --max-time 90 -X POST $B/api/chat -H "Authorization: Bearer $T" -H 'Content-Type: application/json' -d '{"message":"用一句话回答：服务间鉴权链路通了吗？"}'
  cat /tmp/r4; echo
  echo -n "backend SSE 流式（期望 token 流）: "
  curl -s -N --max-time 60 -X POST $B/api/chat/stream -H "Authorization: Bearer $T" -H 'Content-Type: application/json' -d '{"message":"你好"}' | head -c 100
  echo; echo
  echo -n "挂载技能（期望成功）: "
  curl -s -X POST $B/api/skills/mount -H "Authorization: Bearer $T" -H 'Content-Type: application/json' -d '{"skillName":"calculator"}'
  echo
  echo -n "带技能对话 123*45（期望 5535）: "
  curl -s --max-time 90 -X POST $B/api/chat -H "Authorization: Bearer $T" -H 'Content-Type: application/json' -d '{"message":"123乘以45等于多少？"}'
  echo
  mysql -uroot -p"${MYSQL_PW:?请先 export MYSQL_PW=数据库密码}" agent_chat -e "delete from app_user where username='$U'; delete from chat_message where user_id='$U'; delete from user_skill where user_id='$U'; delete from agent_memory where user_id='$U';" 2>/dev/null
  echo "测试数据已清理"
fi

echo; echo "===== 3. agent 侧未授权访问告警日志 ====="
journalctl -u agent --since '3 minutes ago' --no-pager | grep 'InternalAuth' | tail -5
rm -f /tmp/r1 /tmp/r2 /tmp/r3 /tmp/r4
