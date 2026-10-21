-- Optional, additive DEMO data only. Run explicitly in an existing Qingye demo database.
-- No DROP/DELETE/UPDATE of existing records. Repeat execution inserts nothing twice.
SET @previous_demo_timezone=@@session.time_zone;
SET time_zone='+08:00';
SET @demo_now=NOW();
SET @demo_admin=(SELECT id FROM app_user WHERE openid='demo:admin' AND admin=TRUE AND enabled=TRUE LIMIT 1);
SET @demo_photo=(SELECT id FROM app_user WHERE openid='demo:photo' AND enabled=TRUE LIMIT 1);
SET @demo_sport=(SELECT id FROM app_user WHERE openid='demo:sport' AND enabled=TRUE LIMIT 1);
START TRANSACTION;
INSERT INTO club(name,description,color,created_by)
SELECT s.name,s.description,s.color,@demo_admin FROM (
 SELECT '风铃音乐社' AS name,'在草坪和晚风里，分享一首喜欢的歌。' AS description,'blue' AS color
 UNION ALL SELECT '漫游手作社','用纸张、植物和双手，记录校园的四季。','orange'
 UNION ALL SELECT '羽动羽毛球社','轻松挥拍，友好对练，新手也能找到搭档。','green'
 UNION ALL SELECT '青苗自然社','一起照顾校园的小小花园。','green'
) s WHERE @demo_admin IS NOT NULL AND @demo_photo IS NOT NULL AND @demo_sport IS NOT NULL
AND NOT EXISTS(SELECT 1 FROM club c WHERE c.name=s.name);
INSERT INTO club_member(club_id,user_id,role,status)
SELECT c.id,CASE WHEN c.name IN ('风铃音乐社','漫游手作社') THEN @demo_photo ELSE @demo_sport END,'MANAGER','ACTIVE'
FROM club c WHERE c.created_by=@demo_admin AND c.name IN ('风铃音乐社','漫游手作社','羽动羽毛球社','青苗自然社')
AND NOT EXISTS(SELECT 1 FROM club_member m WHERE m.club_id=c.id AND m.user_id=CASE WHEN c.name IN ('风铃音乐社','漫游手作社') THEN @demo_photo ELSE @demo_sport END);
INSERT INTO activity(club_id,created_by,title,description,category,location,poster,start_time,end_time,signup_deadline,capacity,status,reviewed_by,reviewed_at)
SELECT c.id,m.user_id,s.title,'新增演示活动，非真实学校排期。',s.category,s.location,s.poster,
TIMESTAMP(DATE(@demo_now)+INTERVAL s.day_offset DAY,s.hour_time),TIMESTAMP(DATE(@demo_now)+INTERVAL s.day_offset DAY,s.hour_time)+INTERVAL 2 HOUR,
TIMESTAMP(DATE(@demo_now)+INTERVAL s.day_offset DAY,s.hour_time)-INTERVAL 1 HOUR,20,'PUBLISHED',@demo_admin,@demo_now
FROM (
 SELECT '风铃音乐社' AS club_name,'草坪不插电音乐会' AS title,'ART' AS category,'东操场草坪' AS location,'/assets/campus/activity-music-v2.jpg' AS poster,1 AS day_offset,'17:30:00' AS hour_time
 UNION ALL SELECT '漫游手作社','植物蓝晒工作坊','ART','创意工坊 102','/assets/campus/activity-craft-v2.jpg',2,'14:00:00'
 UNION ALL SELECT '羽动羽毛球社','羽毛球友好对练','SPORT','体育馆羽毛球区','',0,TIME(@demo_now-INTERVAL 30 MINUTE)
 UNION ALL SELECT '青苗自然社','香草角认养计划','VOLUNTEER','图书馆前花园','',-2,'15:00:00'
) s JOIN club c ON c.name=s.club_name AND c.created_by=@demo_admin
JOIN club_member m ON m.club_id=c.id AND m.role='MANAGER' AND m.status='ACTIVE'
WHERE NOT EXISTS(SELECT 1 FROM activity a WHERE a.club_id=c.id AND a.title=s.title);
INSERT INTO equipment(name,category,description,image,total_quantity)
SELECT s.name,s.category,s.description,s.image,s.quantity FROM (
 SELECT '羽毛球拍' AS name,'运动' AS category,'成套借用，请检查拍线。' AS description,'/assets/campus/equipment-badminton-v2.jpg' AS image,10 AS quantity
 UNION ALL SELECT '无线话筒','音频','含配套接收器。','/assets/campus/equipment-microphone-v2.jpg',4
 UNION ALL SELECT '便携音箱','音频','用于小型社团活动。','/assets/campus/equipment-speaker-v2.jpg',2
 UNION ALL SELECT '相机充电器','摄影','配套充电器，不是相机。','',6
) s WHERE @demo_admin IS NOT NULL AND @demo_photo IS NOT NULL AND @demo_sport IS NOT NULL
AND NOT EXISTS(SELECT 1 FROM equipment e WHERE e.name=s.name);
COMMIT;
SELECT IF(@demo_admin IS NULL OR @demo_photo IS NULL OR @demo_sport IS NULL,'NOT_APPLIED: demo identities missing','DEMO_ADDITIONS_APPLIED_OR_ALREADY_PRESENT') AS demo_result;
SET time_zone=@previous_demo_timezone;
