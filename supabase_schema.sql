-- Supabase SQL Schema for NewsReader App
-- Run this in your Supabase SQL Editor to create the required table

-- Create the news_reports table
CREATE TABLE IF NOT EXISTS news_reports (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    topic TEXT NOT NULL,
    report_text TEXT NOT NULL,
    articles_json TEXT NOT NULL,
    batch_time TIMESTAMPTZ NOT NULL,
    audio_base64 TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Create indexes for faster queries
CREATE INDEX IF NOT EXISTS idx_news_reports_topic ON news_reports(topic);
CREATE INDEX IF NOT EXISTS idx_news_reports_batch_time ON news_reports(batch_time DESC);
CREATE INDEX IF NOT EXISTS idx_news_reports_topic_batch ON news_reports(topic, batch_time DESC);

-- Enable Row Level Security (RLS)
ALTER TABLE news_reports ENABLE ROW LEVEL SECURITY;

-- Create a policy that allows all operations (for development)
-- In production, you should restrict this based on your auth requirements
CREATE POLICY "Allow all operations" ON news_reports
    FOR ALL
    USING (true)
    WITH CHECK (true);

-- Grant access to the anon role (for unauthenticated access)
GRANT ALL ON news_reports TO anon;
GRANT ALL ON news_reports TO authenticated;

-- ═══════════════════════════════════════════════════════════════════
-- Auto-delete news older than 48 hours using pg_cron
-- Run this ONCE in the Supabase SQL Editor (Dashboard → SQL Editor)
-- ═══════════════════════════════════════════════════════════════════

-- 1. Enable the pg_cron extension (Supabase supports this natively)
CREATE EXTENSION IF NOT EXISTS pg_cron;

-- 2. Grant pg_cron access to the public schema
GRANT USAGE ON SCHEMA cron TO postgres;

-- 3. Schedule a job to delete rows older than 48 hours, every hour
SELECT cron.schedule(
    'cleanup-old-news',           -- job name
    '0 * * * *',                  -- every hour at minute 0
    $$DELETE FROM public.news_reports WHERE created_at < NOW() - INTERVAL '48 hours'$$
);

-- To check scheduled jobs:  SELECT * FROM cron.job;
-- To remove the job:        SELECT cron.unschedule('cleanup-old-news');

