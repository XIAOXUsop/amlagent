CREATE TABLE legal_index_state (
    id                 VARCHAR(32) NOT NULL PRIMARY KEY,
    active_version     VARCHAR(64),
    building_version   VARCHAR(64),
    build_owner        VARCHAR(64),
    build_lease_until  DATETIME(6),
    segment_count      INT NOT NULL DEFAULT 0,
    updated_at         DATETIME(6) NOT NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

INSERT INTO legal_index_state (id, segment_count, updated_at) VALUES ('legal', 0, NOW(6));
