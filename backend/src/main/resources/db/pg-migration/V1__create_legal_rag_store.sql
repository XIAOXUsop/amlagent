-- PGVector 法规向量库基础结构。所有生产 DDL 只能通过 Flyway 演进。
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS legal_docs (
    embedding_id UUID PRIMARY KEY,
    embedding vector(384) NOT NULL,
    text TEXT NULL,
    metadata JSON NULL
);
