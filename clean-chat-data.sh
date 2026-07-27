#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

echo "══════════════════════════════════════════"
echo "  Cleaning Chat & Cost Monitoring Data"
echo "══════════════════════════════════════════"

# Truncate tables: conversations, messages, agent_runs, agent_steps, retrieval_logs, llm_call_logs
# (CASCADE auto-cleans dependent child tables like message_feedback)

SQL_COMMAND="TRUNCATE TABLE conversations, messages, agent_runs, agent_steps, retrieval_logs, llm_call_logs CASCADE;"

if docker compose ps --services --filter "status=running" | grep -q "postgres"; then
  echo "Executing truncate via Docker container 'postgres'..."
  docker compose exec -T postgres psql -U postgres -d postgres -c "${SQL_COMMAND}"
elif command -v psql &> /dev/null; then
  echo "Docker container not running. Attempting direct psql connection (localhost:5433)..."
  PGPASSWORD=postgres psql -h localhost -p 5433 -U postgres -d postgres -c "${SQL_COMMAND}"
else
  echo "❌ Error: Postgres docker container is not running and 'psql' CLI was not found."
  exit 1
fi

echo ""
echo "✅ Conversations, messages, agent runs, agent steps, retrieval logs, and LLM call logs cleared successfully."
