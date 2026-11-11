-- 智慧校园演示库（MySQL 8 与 H2 MODE=MySQL 均可执行）。
--
-- 结构刻意保持小而清晰：两张维度表（班级、课程）+ 三张带归属的业务表（学生、成绩、考勤）。
-- 成绩与考勤冗余 class_id，让"按班级过滤"成为表内谓词而不是额外 JOIN —— 这是数据权限层
-- 能够对各表独立注入过滤条件的前提（见 openspec/changes/campus-data-permission/design.md）。
-- 实时内省（SchemaIntrospector）是唯一事实来源，本文件只负责种子化。

DROP TABLE IF EXISTS attendance;
DROP TABLE IF EXISTS score;
DROP TABLE IF EXISTS student;
DROP TABLE IF EXISTS course;
DROP TABLE IF EXISTS class;

CREATE TABLE class (
    id           BIGINT      NOT NULL,
    name         VARCHAR(50) NOT NULL,
    grade        VARCHAR(20) NOT NULL,
    head_teacher VARCHAR(50),
    PRIMARY KEY (id)
);

CREATE TABLE course (
    id      BIGINT       NOT NULL,
    code    VARCHAR(20)  NOT NULL,
    name    VARCHAR(100) NOT NULL,
    credits DECIMAL(3,1) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (code)
);

CREATE TABLE student (
    id          BIGINT      NOT NULL,
    student_no  VARCHAR(20) NOT NULL,
    name        VARCHAR(50) NOT NULL,
    gender      VARCHAR(10),
    class_id    BIGINT      NOT NULL,
    enrolled_at DATE        NOT NULL,
    phone       VARCHAR(20),
    PRIMARY KEY (id),
    UNIQUE (student_no),
    CONSTRAINT fk_student_class FOREIGN KEY (class_id) REFERENCES class (id)
);

CREATE TABLE score (
    id         BIGINT       NOT NULL,
    student_id BIGINT       NOT NULL,
    course_id  BIGINT       NOT NULL,
    term       VARCHAR(20)  NOT NULL,
    score      DECIMAL(5,2) NOT NULL,
    class_id   BIGINT       NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_score_student FOREIGN KEY (student_id) REFERENCES student (id),
    CONSTRAINT fk_score_course FOREIGN KEY (course_id) REFERENCES course (id)
);

CREATE TABLE attendance (
    id         BIGINT      NOT NULL,
    student_id BIGINT      NOT NULL,
    course_id  BIGINT      NOT NULL,
    class_date DATE        NOT NULL,
    status     VARCHAR(10) NOT NULL,
    class_id   BIGINT      NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_attendance_student FOREIGN KEY (student_id) REFERENCES student (id),
    CONSTRAINT fk_attendance_course FOREIGN KEY (course_id) REFERENCES course (id)
);

CREATE INDEX idx_student_class ON student (class_id);
CREATE INDEX idx_score_student ON score (student_id);
CREATE INDEX idx_score_class ON score (class_id);
CREATE INDEX idx_attendance_student ON attendance (student_id);
CREATE INDEX idx_attendance_class ON attendance (class_id);
