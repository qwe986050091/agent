#!/bin/bash
# P0-4 韧性验证 + 故障演练（在新服务器 82.156.243.32 上执行）
B=http://127.0.0.1:8081
U="cb$(date +%s)"
P=test123456

echo "===== 1. 注册 + 正常同步对话 ====="
REG=$(curl -s -X POST $B/api/auth/register -H 'Content-Type: application/json' \
  -d "{\"username\":\"$U\",\"password\":\"$P\",\"nickname\":\"熔断演练\"}")
TOKEN=$(echo "$REG" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
if [ -z "$TOKEN" ]; then echo "!! 注册失败: $REG"; exit 1; fi
echo "用户 $U 注册成功"
S=$(date +%s)
curl -s --max-time 60 -X POST $B/api/chat -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"message":"你好，用一句话回答：1+1等于几"}'
echo; echo "(耗时 $(($(date +%s)-S))s)"

echo; echo "===== 2. SSE 流式对话（应逐 token） ====="
curl -s -N --max-time 60 -X POST $B/api/chat/stream -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"message":"用一句话介绍你自己"}' | head -c 120
echo; echo

echo "===== 3. 故障演练：停止 agent ====="
systemctl stop agent
sleep 2
curl -s -o /dev/null --max-time 5 http://127.0.0.1:8080/api/agent/health && echo "agent 仍在?!" || echo "agent 已停止（连接失败，符合预期）"

echo; echo "===== 4. 连续 6 次对话（前几次走重试≈秒级失败，第 6 次应熔断立即失败） ====="
for i in 1 2 3 4 5 6; do
  S=$(date +%s%N)
  CODE=$(curl -s -o /tmp/resp.json -w '%{http_code}' --max-time 60 -X POST $B/api/chat \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"message":"你好"}')
  E=$(date +%s%N)
  MS=$(( (E-S)/1000000 ))
  echo "第${i}次: HTTP $CODE, ${MS}ms, 响应: $(head -c 130 /tmp/resp.json)"
done

echo; echo "===== 5. SSE 在熔断打开时（应立即失败，不等待） ====="
S=$(date +%s%N)
CODE=$(curl -s -o /tmp/sse-resp.txt -w '%{http_code}' -N --max-time 15 -X POST $B/api/chat/stream \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"message":"你好"}')
E=$(date +%s%N)
echo "SSE 故障态: HTTP $CODE, $(( (E-S)/1000000 ))ms, 响应: $(head -c 150 /tmp/sse-resp.txt)"

echo; echo "===== 6. backend 熔断/重试日志 ====="
journalctl -u agent-backend --since '3 minutes ago' --no-pager | grep -iE 'Resilience' | tail -12

echo; echo "===== 7. 恢复 agent，等待熔断半开（30s）后自动放行 ====="
systemctl start agent
sleep 15
curl -s -o /dev/null -w 'agent 恢复健康检查: %{http_code}\n' http://127.0.0.1:8080/api/agent/health
echo "等待 32s 熔断器进入半开探测..."
sleep 32
S=$(date +%s%N)
CODE=$(curl -s -o /tmp/resp2.json -w '%{http_code}' --max-time 90 -X POST $B/api/chat \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"message":"服务恢复了吗？用一句话回答"}')
E=$(date +%s%N)
echo "恢复后对话: HTTP $CODE, $(( (E-S)/1000000 ))ms, 响应: $(head -c 200 /tmp/resp2.json)"

echo; echo "===== 8. 熔断恢复日志 ====="
journalctl -u agent-backend --since '2 minutes ago' --no-pager | grep -iE 'Resilience' | tail -6

echo; echo "===== 9. 清理测试数据 ====="
mysql -uroot -p"${MYSQL_PW:?请先 export MYSQL_PW=数据库密码}" agent_chat -e \
  "delete from app_user where username='$U'; delete from chat_message where user_id='$U'; delete from agent_memory where user_id='$U';" 2>/dev/null
rm -f /tmp/resp.json /tmp/resp2.json /tmp/sse-resp.txt
echo "测试用户 $U 已清理"
