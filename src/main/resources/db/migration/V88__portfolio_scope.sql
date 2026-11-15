-- Portfolio scope: keep applied migration history immutable and remove the retired module at the head.
-- This migration is for the isolated portfolio database. Never point the portfolio app at an original service database.
-- Drop child tables before their parents so foreign-key checks remain enabled.
DROP TABLE IF EXISTS eval_kpi_evidence;
DROP TABLE IF EXISTS eval_kpi_achievements;
DROP TABLE IF EXISTS eval_kpi_scores;
DROP TABLE IF EXISTS eval_answers;
DROP TABLE IF EXISTS eval_coop_scores;
DROP TABLE IF EXISTS eval_results;
DROP TABLE IF EXISTS eval_assignments;
DROP TABLE IF EXISTS eval_committee_members;
DROP TABLE IF EXISTS eval_template_items;
DROP TABLE IF EXISTS eval_templates;
DROP TABLE IF EXISTS eval_team_kpi_items;
DROP TABLE IF EXISTS eval_team_kpis;
DROP TABLE IF EXISTS eval_kpi_round;
DROP TABLE IF EXISTS eval_cycle_exclusions;
DROP TABLE IF EXISTS training_completions;
DROP TABLE IF EXISTS eval_cycles;
DROP TABLE IF EXISTS performance_reviews;
DROP TABLE IF EXISTS personnel_transfers;

-- Employee grades remain shared by employee records and annual-leave allowance calculation.
ALTER TABLE emp_grades DROP COLUMN IF EXISTS eval_group;
ALTER TABLE teams DROP COLUMN IF EXISTS kpi_target;

DELETE FROM user_roles WHERE role_code = 'EVAL_ADMIN';
DELETE FROM roles WHERE code = 'EVAL_ADMIN';
DELETE FROM app_settings
WHERE setting_key IN ('menu.ext.show.eval', 'menu.int.show.eval',
                      'menu.ext.show.hr.performance', 'menu.int.show.hr.performance')
   OR setting_key LIKE 'menu.ext.show.eval.%'
   OR setting_key LIKE 'menu.int.show.eval.%';
DELETE FROM notifications
WHERE type LIKE 'EVAL%' OR type IN ('KPI', 'PERFORMANCE_REVIEW')
   OR link_url REGEXP '^/(eval|admin/eval|admin/grades|admin/personnel/transfer|hr/performance)(/|[?#]|$)';
