-- Portfolio scope: the appraisal/KPI module is not part of this edition.
-- Earlier migrations stay immutable; the retired module is removed here at the head.
-- This migration is for an isolated portfolio database only.
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

-- Employee grades stay: they drive employee records, hourly wages and leave allowance.
ALTER TABLE emp_grades DROP COLUMN IF EXISTS eval_group;
ALTER TABLE teams DROP COLUMN IF EXISTS kpi_target;

-- Personnel transfers stay; only the appraisal-era column name is neutralised.
ALTER TABLE personnel_transfers CHANGE COLUMN eval_year transfer_year INT NOT NULL;

DELETE FROM user_roles WHERE role_code = 'EVAL_ADMIN';
DELETE FROM roles WHERE code = 'EVAL_ADMIN';

DELETE FROM admin_menu_grants WHERE menu_key IN ('EVAL_KPI', 'EVAL_TEMPLATES', 'EVAL_CYCLES');
DELETE FROM admin_menu_grant_logs WHERE menu_key IN ('EVAL_KPI', 'EVAL_TEMPLATES', 'EVAL_CYCLES');

-- The personnel-transfer menu moved from the retired group to the organisation group.
UPDATE IGNORE app_settings SET setting_key = REPLACE(setting_key, '.eval.transfer', '.org.transfer')
WHERE setting_key IN ('menu.ext.show.eval.transfer', 'menu.int.show.eval.transfer');
DELETE FROM app_settings
WHERE setting_key IN ('menu.ext.show.eval', 'menu.int.show.eval',
                      'menu.ext.show.hr.performance', 'menu.int.show.hr.performance')
   OR setting_key LIKE 'menu.ext.show.eval.%'
   OR setting_key LIKE 'menu.int.show.eval.%';

DELETE FROM notifications
WHERE type LIKE 'EVAL%' OR type IN ('KPI', 'PERFORMANCE_REVIEW')
   OR link_url REGEXP '^/(eval|admin/eval|hr/performance)(/|[?#]|$)';
