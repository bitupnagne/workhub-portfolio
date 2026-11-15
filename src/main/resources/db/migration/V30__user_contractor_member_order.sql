-- 도급 여부 + 팀/본부 내 직원 정렬 순서
ALTER TABLE users
  ADD COLUMN contractor TINYINT(1) NOT NULL DEFAULT 0 AFTER system_account,
  ADD COLUMN member_order INT NOT NULL DEFAULT 0 AFTER contractor;

-- 가상 계약직 계정이 등록되어 있으면 도급으로 표시한다.
UPDATE users SET contractor = 1
WHERE username IN ('demo.contractor01','demo.contractor02','demo.contractor03','demo.contractor04',
                   'demo.contractor05','demo.contractor06','demo.contractor07','demo.contractor08','demo.contractor09');
