#!/bin/bash
# P0-2 部署：替换 jar + 两个 systemd unit 注入 AGENT_INTERNAL_TOKEN
set -e

echo "===== 1. 备份 + 替换 jar ====="
cd /opt/agent
cp agent.jar agent.jar.bak-20260906-authtoken
cp agent-backend.jar agent-backend.jar.bak-20260906-authtoken
mv /tmp/agent.jar.new agent.jar
mv /tmp/agent-backend.jar.new agent-backend.jar
chown agent:agent agent.jar agent-backend.jar
ls -l agent.jar agent-backend.jar

echo; echo "===== 2. 生成内部 token 并写入两个 systemd unit ====="
TOKEN=$(openssl rand -hex 24)
for svc in agent agent-backend; do
  f=/etc/systemd/system/$svc.service
  sed -i '/^Environment=AGENT_INTERNAL_TOKEN=/d' $f
  sed -i "/^ExecStart=/i Environment=AGENT_INTERNAL_TOKEN=$TOKEN" $f
done
systemctl daemon-reload
echo "--- agent.service Environment 行 ---"
grep -E '^Environment=' /etc/systemd/system/agent.service
echo "--- agent-backend.service Environment 行 ---"
grep -E '^Environment=' /etc/systemd/system/agent-backend.service

echo; echo "===== 3. 重启 agent ====="
systemctl restart agent
sleep 15
systemctl is-active agent
curl -s -o /dev/null -w 'agent-health(no-token-needed): %{http_code}\n' http://127.0.0.1:8080/api/agent/health

echo; echo "===== 4. 重启 backend ====="
systemctl restart agent-backend
sleep 15
systemctl is-active agent-backend
curl -s -o /dev/null -w 'backend-health: %{http_code}\n' http://127.0.0.1:8081/api/health

echo; echo "===== 5. 鉴权验证：直连 agent 8080 ====="
echo -n "无 token POST /chat（期望 401）: "
curl -s -o /tmp/r1 -w '%{http_code}\n' -X POST http://127.0.0.1:8080/api/agent/chat -H 'Content-Type: application/json' -d '{"userId":"x","message":"hi"}'
cat /tmp/r1; echo
echo -n "错误 token POST /chat（期望 401）: "
curl -s -o /tmp/r2 -w '%{http_code}\n' -X POST http://127.0.0.1:8080/api/agent/chat -H 'Content-Type: application/json' -H 'X-Agent-Token: wrong-token' -d '{"userId":"x","message":"hi"}'
cat /tmp/r2; echo
echo -n "无 token GET /tools（期望 401）: "
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/api/agent/tools
echo -n "无 token GET /health（期望 200，探活放行）: "
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/api/agent/health
echo -n "正确 token POST /chat（期望 200）: "
curl -s -o /tmp/r3 -w '%{http_code}\n' --max-time 90 -X POST http://127.0.0.1:8080/api/agent/chat -H 'Content-Type: application/json' -H "X-Agent-Token: $TOKEN" -d '{"userId":"authtest","message":"用一句话说你好"}'
cat /tmp/r3; echo

echo; echo "===== 6. 经 backend 端到端对话（backend 自动带 token，期望 200） ====="
B=http://127.0.0.1:8081
U="at$(date +%s)"
REG=$(curl -s -X POST $B/api/auth/register -H 'Content-Type: application/json' -d "{\"username\":\"$U\",\"password\":\"test123456\"}")
T=$(echo "$REG" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
echo -n "backend 同步对话: "
curl -s -o /tmp/r4 -w '%{http_code}\n' --max-time 90 -X POST $B/api/chat -H "Authorization: Bearer $T" -H 'Content-Type: application/json' -d '{"message":"用一句话回答：鉴权链路通了吗？"}'
cat /tmp/r4; echo
echo -n "backend SSE 流式（前几个事件）: "
curl -s -N --max-time 60 -X POST $B/api/chat/stream -H "Authorization: Bearer $T" -H 'Content-Type: application/json' -d '{"message":"你好"}' | head -c 80
echo

echo; echo "===== 7. 日志确认无鉴权相关异常 ====="
journalctl -u agent-backend --since '2 minutes ago' --no-pager | grep -iE 'error|exception|started' | tail -5
journalctl -u agent --since '2 minutes ago' --no-pager | grep -iE 'error|exception|started|InternalAuth' | tail -5

echo; echo "===== 8. 清理 ====="
mysql -uroot -p"${MYSQL_PW:?请先 export MYSQL_PW=数据库密码}" agent_chat -e "delete from app_user where username='$U'; delete from chat_message where user_id='$U'; delete from agent_memory where user_id='$U';" 2>/dev/null
rm -f /tmp/r1 /tmp/r2 /tmp/r3 /tmp/r4
echo "完成"
