CREATE TABLE IF NOT EXISTS app_user (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, openid VARCHAR(128) NOT NULL UNIQUE,
 name VARCHAR(40) NOT NULL, avatar VARCHAR(512) NOT NULL DEFAULT '',
 admin BOOLEAN NOT NULL DEFAULT FALSE, enabled BOOLEAN NOT NULL DEFAULT TRUE,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
CREATE TABLE IF NOT EXISTS club (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(60) NOT NULL,
 description VARCHAR(1000) NOT NULL DEFAULT '', color VARCHAR(20) NOT NULL DEFAULT 'green',
 enabled BOOLEAN NOT NULL DEFAULT TRUE, created_by BIGINT NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY (created_by) REFERENCES app_user(id)
);
CREATE TABLE IF NOT EXISTS club_member (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, club_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
 role VARCHAR(20) NOT NULL DEFAULT 'MEMBER', status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
 joined_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE (club_id, user_id), FOREIGN KEY (club_id) REFERENCES club(id), FOREIGN KEY (user_id) REFERENCES app_user(id)
);
CREATE TABLE IF NOT EXISTS activity (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, club_id BIGINT NOT NULL, created_by BIGINT NOT NULL,
 title VARCHAR(80) NOT NULL, description VARCHAR(2000) NOT NULL DEFAULT '',
 category VARCHAR(20) NOT NULL, location VARCHAR(100) NOT NULL, poster VARCHAR(512) NOT NULL DEFAULT '',
 start_time TIMESTAMP(6) NOT NULL, end_time TIMESTAMP(6) NOT NULL, signup_deadline TIMESTAMP(6) NOT NULL,
 capacity INT NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
 reviewed_by BIGINT, review_note VARCHAR(200) NOT NULL DEFAULT '', reviewed_at TIMESTAMP(6),
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY (club_id) REFERENCES club(id), FOREIGN KEY (created_by) REFERENCES app_user(id),
 FOREIGN KEY (reviewed_by) REFERENCES app_user(id), CHECK(capacity > 0), CHECK(end_time > start_time),
 INDEX idx_activity_feed(status,start_time,id)
);
CREATE TABLE IF NOT EXISTS registration (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, activity_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
 status VARCHAR(20) NOT NULL, joined_at TIMESTAMP(6) NOT NULL,
 UNIQUE(activity_id,user_id), FOREIGN KEY(activity_id) REFERENCES activity(id), FOREIGN KEY(user_id) REFERENCES app_user(id),
 INDEX idx_registration_queue(activity_id,status,joined_at,id)
);
CREATE TABLE IF NOT EXISTS equipment (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(60) NOT NULL, category VARCHAR(20) NOT NULL,
 description VARCHAR(1000) NOT NULL DEFAULT '', image VARCHAR(512) NOT NULL DEFAULT '',
 total_quantity INT NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), CHECK(total_quantity >= 0)
);
CREATE TABLE IF NOT EXISTS loan (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, activity_id BIGINT NOT NULL, applicant_id BIGINT NOT NULL,
 equipment_id BIGINT NOT NULL, quantity INT NOT NULL, planned_start TIMESTAMP(6) NOT NULL,
 planned_end TIMESTAMP(6) NOT NULL, reason VARCHAR(300) NOT NULL DEFAULT '', status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
 request_key VARCHAR(64) NOT NULL UNIQUE, reviewed_by BIGINT, review_note VARCHAR(200) NOT NULL DEFAULT '',
 reviewed_at TIMESTAMP(6), checked_out_at TIMESTAMP(6), returned_at TIMESTAMP(6),
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(activity_id) REFERENCES activity(id), FOREIGN KEY(applicant_id) REFERENCES app_user(id),
 FOREIGN KEY(equipment_id) REFERENCES equipment(id), FOREIGN KEY(reviewed_by) REFERENCES app_user(id),
 CHECK(quantity > 0), CHECK(planned_end > planned_start),
 INDEX idx_loan_interval(equipment_id,status,planned_start,planned_end)
);
CREATE TABLE IF NOT EXISTS notification (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, title VARCHAR(80) NOT NULL,
 body VARCHAR(500) NOT NULL, delivered BOOLEAN NOT NULL DEFAULT FALSE, read_at TIMESTAMP(6),
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), FOREIGN KEY(user_id) REFERENCES app_user(id),
 INDEX idx_notification_user(user_id,delivered,id)
);
CREATE TABLE IF NOT EXISTS message_task (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, notification_id BIGINT NOT NULL UNIQUE,
 source_kind VARCHAR(20) NOT NULL DEFAULT 'NOTICE', source_id BIGINT,
 status VARCHAR(20) NOT NULL DEFAULT 'PENDING', available_at TIMESTAMP(6) NOT NULL,
 attempts INT NOT NULL DEFAULT 0, last_error VARCHAR(200) NOT NULL DEFAULT '',
 completed_at TIMESTAMP(6), created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(notification_id) REFERENCES notification(id),
 INDEX idx_task_due(status,available_at,id)
);
