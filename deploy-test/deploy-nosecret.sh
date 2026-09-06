#!/bin/bash
# P0-1 部署：替换 jar（unit 已注入 OPENAI_API_KEY / MYSQL_PASSWORD，不受 yml 默认值移除影响）
set -e
cd /opt/agent
cp agent.jar agent.jar.bak-20260906-nosecret
cp agent-backend.jar agent-backend.jar.bak-20260906-nosecret
mv /tmp/agent.jar.new agent.jar
mv /tmp/agent-backend.jar.new agent-backend.jar
chown agent:agent agent.jar agent-backend.jar

echo "===== 重启 agent ====="
systemctl restart agent
sleep 18
echo "agent: $(systemctl is-active agent)"
curl -s -o /dev/null -w 'agent-health: %{http_code}\n' http://127.0.0.1:8080/api/agent/health

echo "===== 重启 backend ====="
systemctl restart agent-backend
sleep 22
echo "backend: $(systemctl is-active agent-backend)"
curl -s -o /dev/null -w 'backend-health: %{http_code}\n' http://127.0.0.1:8081/api/health

echo "===== 启动日志检查（无 key/密码相关报错） ====="
journalctl -u agent --since '1 minute ago' --no-pager | grep -iE 'Started SimpleAgent|run failed|OPENAI_API_KEY' | tail -3
journalctl -u agent-backend --since '1 minute ago' --no-pager | grep -iE 'Started AgentBackend|run failed|Access denied|HikariPool.*Start' | tail -3

echo "===== 端到端对话 ====="
B=http://127.0.0.1:8081
U="ns$(date +%s)"
REG=$(curl -s -X POST $B/api/auth/register -H 'Content-Type: application/json' -d "{\"username\":\"$U\",\"password\":\"test123456\"}")
T=$(echo "$REG" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
echo -n "同步对话: "
curl -s --max-time 90 -X POST $B/api/chat -H "Authorization: Bearer $T" -H 'Content-Type: application/json' -d '{"message":"用一句话回答：密钥改为环境变量注入后服务正常吗？"}'
echo
DBPW=$(grep -o 'MYSQL_PASSWORD=[^ ]*' /etc/systemd/system/agent-backend.service | cut -d= -f2)
DBU=$(grep -o 'MYSQL_USER=[^ ]*' /etc/systemd/system/agent-backend.service | cut -d= -f2)
mysql -u"$DBU" -p"$DBPW" agent_chat -e "delete from app_user where username='$U'; delete from chat_message where user_id='$U'; delete from agent_memory where user_id='$U';" 2>/dev/null
echo "清理完成"
