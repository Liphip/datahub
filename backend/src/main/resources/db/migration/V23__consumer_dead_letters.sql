CREATE TABLE IF NOT EXISTS consumer_dead_letters (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), channel VARCHAR(128) NOT NULL,
    payload TEXT NOT NULL, error TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_consumer_dead_letters_created ON consumer_dead_letters(created_at DESC);
