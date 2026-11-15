-- 회사휴무(COMPANY)와 공휴일(PUBLIC)을 구분하기 위한 카테고리 컬럼.
-- 기존 데이터는 모두 COMPANY로 처리한다.
ALTER TABLE company_holidays
    ADD COLUMN category VARCHAR(16) NOT NULL DEFAULT 'COMPANY' AFTER name;

UPDATE company_holidays SET category = 'COMPANY' WHERE category IS NULL OR category = '';
