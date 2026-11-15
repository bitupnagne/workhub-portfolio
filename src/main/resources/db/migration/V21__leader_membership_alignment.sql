-- 소속(users.team_id)과 조직장(teams.leader_user_id) 정합화.
-- 원칙: 조직장의 소속 = 자신이 이끄는 조직. 본부장 소속=해당 본부(DIVISION),
--       대표 소속=대표이사(ROOT). 팀장/팀원은 기존대로 팀 소속(이미 일치).
-- DIVISION/ROOT 조직장인 사용자의 team_id를, 그가 이끄는 최상위 조직 노드로 보정.
UPDATE users u
SET u.team_id = (
    SELECT t.id
    FROM teams t
    WHERE t.leader_user_id = u.id
      AND t.node_type IN ('DIVISION', 'ROOT')
      AND t.active = TRUE
    ORDER BY FIELD(t.node_type, 'ROOT', 'DIVISION'), t.id
    LIMIT 1
)
WHERE EXISTS (
    SELECT 1
    FROM teams t
    WHERE t.leader_user_id = u.id
      AND t.node_type IN ('DIVISION', 'ROOT')
      AND t.active = TRUE
);
