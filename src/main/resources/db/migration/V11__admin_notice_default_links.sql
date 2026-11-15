UPDATE admin_notices
SET link_url = CONCAT('/notices/', id)
WHERE link_url IS NULL OR link_url = '';

UPDATE notifications n
JOIN admin_notices an ON an.id = n.notice_id
SET n.link_url = an.link_url
WHERE n.notice_id IS NOT NULL
  AND (n.link_url IS NULL OR n.link_url = '' OR n.link_url = '/notifications');
