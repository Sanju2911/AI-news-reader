const express = require('express');
const https = require('https');
const path = require('path');

const app = express();
const PORT = Number(process.env.PORT || 3000);

// ─── API Keys (from your Android app's ApiConfig.kt) ─────────────────
const GNEWS_API_KEY = process.env.GNEWS_API_KEY || '';
const GEMINI_API_KEY = process.env.GEMINI_API_KEY || '';
const SARVAM_API_KEY = process.env.SARVAM_API_KEY || '';

// ─── Supabase Config ──────────────────────────────────────────────────
const SUPABASE_URL = process.env.SUPABASE_URL || '';
const SUPABASE_KEY = process.env.SUPABASE_KEY || '';

// ─── Middleware ───────────────────────────────────────────────────────
app.use(express.json({ limit: '50mb' }));
app.use(express.static(path.join(__dirname, 'public')));

// ─── HTTPS helper ─────────────────────────────────────────────────────
function apiRequest(url, options = {}) {
    return new Promise((resolve, reject) => {
        const parsed = new URL(url);
        const bodyStr = options.body ? (typeof options.body === 'string' ? options.body : JSON.stringify(options.body)) : null;

        const reqOpts = {
            hostname: parsed.hostname,
            port: 443,
            path: parsed.pathname + parsed.search,
            method: options.method || 'GET',
            headers: { ...options.headers }
        };

        if (bodyStr) {
            if (!reqOpts.headers['Content-Type']) reqOpts.headers['Content-Type'] = 'application/json';
            reqOpts.headers['Content-Length'] = Buffer.byteLength(bodyStr);
        }

        const req = https.request(reqOpts, (res) => {
            const chunks = [];
            res.on('data', chunk => chunks.push(chunk));
            res.on('end', () => {
                const raw = Buffer.concat(chunks).toString();
                try {
                    resolve({ status: res.statusCode, data: JSON.parse(raw) });
                } catch {
                    resolve({ status: res.statusCode, data: raw });
                }
            });
        });

        req.on('error', reject);
        req.setTimeout(60000, () => { req.destroy(); reject(new Error('Request timeout')); });
        if (bodyStr) req.write(bodyStr);
        req.end();
    });
}

// ─── Supabase REST helpers ────────────────────────────────────────────
const supabaseHeaders = {
    'apikey': SUPABASE_KEY,
    'Authorization': `Bearer ${SUPABASE_KEY}`,
    'Content-Type': 'application/json',
    'Prefer': 'return=representation'
};

async function supabaseSelect(table, query = '') {
    const url = `${SUPABASE_URL}/rest/v1/${table}?${query}`;
    const result = await apiRequest(url, { headers: supabaseHeaders });
    if (result.status >= 400) {
        console.error(`Supabase SELECT error (${result.status}):`, JSON.stringify(result.data).slice(0, 300));
        return null;
    }
    return result.data;
}

async function supabaseInsert(table, row) {
    const url = `${SUPABASE_URL}/rest/v1/${table}`;
    const result = await apiRequest(url, {
        method: 'POST',
        headers: supabaseHeaders,
        body: row
    });
    if (result.status >= 400) {
        console.error(`Supabase INSERT error (${result.status}):`, JSON.stringify(result.data).slice(0, 300));
        return null;
    }
    console.log(`  ✅ Supabase: saved to "${table}"`);
    return result.data;
}

async function supabaseUpdate(table, matchQuery, updates) {
    const url = `${SUPABASE_URL}/rest/v1/${table}?${matchQuery}`;
    const result = await apiRequest(url, {
        method: 'PATCH',
        headers: supabaseHeaders,
        body: updates
    });
    if (result.status >= 400) {
        console.error(`Supabase UPDATE error (${result.status}):`, JSON.stringify(result.data).slice(0, 300));
        return null;
    }
    return result.data;
}

// ─── Batch time calculation (matches Android's NewsRefreshWorker) ─────
function getCurrentBatchTime() {
    const now = new Date();
    const hour = now.getUTCHours();
    const batchHour = Math.floor(hour / 4) * 4;
    const batch = new Date(Date.UTC(
        now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate(),
        batchHour, 0, 0, 0
    ));
    return batch.toISOString().replace('.000Z', 'Z');
}

// ─── 1. GNews Proxy ───────────────────────────────────────────────────
const TOPIC_MAP = {
    WORLD: 'world', TECHNOLOGY: 'technology', BUSINESS: 'business',
    SPORTS: 'sports', HEALTH: 'health', SCIENCE: 'science',
    ENTERTAINMENT: 'entertainment', INDIA: 'nation'
};

app.get('/api/news/:topic', async (req, res) => {
    const topic = req.params.topic.toUpperCase();
    const gnewsTopic = TOPIC_MAP[topic] || 'world';

    // Check Supabase for a cached report first
    try {
        const batchTime = getCurrentBatchTime();
        const cached = await supabaseSelect('news_reports',
            `topic=eq.${topic}&order=batch_time.desc&limit=1`
        );

        if (cached && cached.length > 0) {
            const report = cached[0];
            console.log(`  📦 Supabase: found cached report for ${topic} (batch: ${report.batch_time})`);
            return res.json({
                _source: 'supabase',
                _report: report,
                articles: parseArticlesJson(report.articles_json)
            });
        }
    } catch (err) {
        console.error('Supabase lookup error:', err.message);
    }

    // Fallback: fetch fresh from GNews
    const url = `https://gnews.io/api/v4/top-headlines?topic=${gnewsTopic}&lang=en&country=in&max=10&apikey=${GNEWS_API_KEY}`;
    try {
        const result = await apiRequest(url);
        console.log(`  🌐 GNews: fetched ${result.data?.articles?.length || 0} articles for ${topic}`);
        res.json({ _source: 'gnews', ...result.data });
    } catch (err) {
        console.error('GNews error:', err.message);
        res.status(500).json({ error: err.message });
    }
});

function parseArticlesJson(json) {
    try {
        const parsed = JSON.parse(json);
        return parsed.map(a => ({
            title: a.title || 'Untitled',
            description: a.description || '',
            source: { name: a.source || 'News' },
            url: a.url || '',
            image: a.image || null
        }));
    } catch { return []; }
}

// ─── 2. Gemini Report Proxy ──────────────────────────────────────────
app.post('/api/report', async (req, res) => {
    const { articles, topicName, topic } = req.body;

    if (!articles || !articles.length) {
        return res.status(400).json({ error: 'No articles provided' });
    }

    const topicKey = (topic || topicName || 'WORLD').toUpperCase();

    const articleList = articles.map((a, i) =>
        `Article ${i + 1}:\n  Title: ${a.title}\n  Description: ${a.description || 'N/A'}\n  Content: ${(a.content || '').slice(0, 400)}\n  Source: ${a.source?.name || 'Unknown'}`
    ).join('\n\n');

    const prompt = `You are a professional news anchor presenting a news bulletin on the topic "${topicName}".
Read through ALL of the following ${articles.length} news articles and create a complete news report.

For EACH article, write a clear 2-3 sentence explanation as if you are reading it aloud on air.
Separate each story with the marker "---NEXT---" on its own line.

Rules:
- Use conversational, easy-to-understand English suitable for text-to-speech
- Do NOT use bullet points, numbering, or markdown
- Make smooth transitions between stories (e.g. "Moving on to our next story...", "In other news...", "Meanwhile...")
- Start the report with a brief greeting like "Here are today's top ${topicName} headlines."
- End with a brief sign-off like "That's all for now. Stay informed!"
- Each story segment should be 2-3 sentences only

Here are the articles:

${articleList}

News report (separate each story with ---NEXT---):`;

    const url = `https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=${GEMINI_API_KEY}`;

    try {
        const result = await apiRequest(url, {
            method: 'POST',
            body: { contents: [{ parts: [{ text: prompt }] }] }
        });

        // Debug: log the raw response structure
        if (result.status !== 200) {
            console.error(`  ❌ Gemini HTTP ${result.status}:`, JSON.stringify(result.data).slice(0, 500));
        }

        const fullText = result.data?.candidates?.[0]?.content?.parts?.[0]?.text || '';

        if (!fullText) {
            console.error('  ❌ Gemini: empty response. Full result:', JSON.stringify(result.data).slice(0, 500));
        }

        const segments = fullText.split('---NEXT---').map(s => s.trim()).filter(s => s);

        console.log(`  🤖 Gemini: ${segments.length} segments for ${articles.length} articles`);

        // If Gemini failed (0 segments), use fallback
        let finalSegments = segments;
        let reportText = fullText;
        if (segments.length === 0) {
            finalSegments = articles.map(a => `${a.title}. ${a.description || 'No details available.'}`);
            reportText = finalSegments.join('\n---NEXT---\n');
            console.log(`  ⚠️  Using fallback (article titles) since Gemini returned 0 segments`);
        }

        // ── Save to Supabase ──────────────────────────────────────────
        const batchTime = getCurrentBatchTime();
            const articlesJson = JSON.stringify(
                articles.map(a => ({ title: a.title, source: a.source?.name || 'News' }))
            );

            // Check if report already exists for this batch
            const existing = await supabaseSelect('news_reports',
                `topic=eq.${topicKey}&batch_time=eq.${encodeURIComponent(batchTime)}&limit=1`
            );

            if (!existing || existing.length === 0) {
                await supabaseInsert('news_reports', {
                    topic: topicKey,
                    report_text: reportText,
                    articles_json: articlesJson,
                    batch_time: batchTime
                });
                console.log(`  💾 Saved report to Supabase: ${topicKey} @ ${batchTime}`);
            } else {
                console.log(`  📦 Report already exists in Supabase for ${topicKey} @ ${batchTime}`);
            }

        res.json({ segments: finalSegments, saved: true });
    } catch (err) {
        console.error('Gemini error:', err.message);
        const fallback = articles.map(a => `${a.title}. ${a.description || 'No details available.'}`);

        // Save fallback to Supabase too
        try {
            const batchTime = getCurrentBatchTime();
            const articlesJson = JSON.stringify(
                articles.map(a => ({ title: a.title, source: a.source?.name || 'News' }))
            );
            const existing = await supabaseSelect('news_reports',
                `topic=eq.${topicKey}&batch_time=eq.${encodeURIComponent(batchTime)}&limit=1`
            );
            if (!existing || existing.length === 0) {
                await supabaseInsert('news_reports', {
                    topic: topicKey,
                    report_text: fallback.join('\n---NEXT---\n'),
                    articles_json: articlesJson,
                    batch_time: batchTime
                });
                console.log(`  💾 Saved fallback report to Supabase: ${topicKey}`);
            }
        } catch (e) { console.error('Supabase save fallback error:', e.message); }

        res.json({ segments: fallback, saved: true });
    }
});

// ─── 3. Sarvam TTS Proxy (with Supabase audio caching) ────────────────
app.post('/api/tts', async (req, res) => {
    const { text, language = 'en-IN', speaker = 'ritu' } = req.body;

    if (!text) return res.status(400).json({ error: 'No text provided' });

    const url = 'https://api.sarvam.ai/text-to-speech';

    try {
        const result = await apiRequest(url, {
            method: 'POST',
            headers: { 'api-subscription-key': SARVAM_API_KEY },
            body: {
                text,
                target_language_code: language,
                speaker,
                pace: 1.1,
                speech_sample_rate: 48000,
                enable_preprocessing: true,
                model: 'bulbul:v3'
            }
        });

        if (result.status !== 200) {
            console.error(`  ❌ Sarvam TTS HTTP ${result.status}:`, JSON.stringify(result.data).slice(0, 300));
            return res.status(result.status).json({ error: `TTS failed: ${result.status}` });
        }

        const audioChunks = result.data?.audios || [];
        const combinedAudio = audioChunks.join('');
        console.log(`  🔊 TTS: generated ${Math.round(combinedAudio.length / 1024)}KB audio`);
        res.json({ audio: combinedAudio });
    } catch (err) {
        console.error('Sarvam TTS error:', err.message);
        res.status(500).json({ error: err.message });
    }
});

// ─── 4. Cache TTS audio back to Supabase ──────────────────────────────
app.post('/api/cache-audio', async (req, res) => {
    const { reportId, audioBase64 } = req.body;
    if (!reportId || !audioBase64) {
        return res.status(400).json({ error: 'Missing reportId or audioBase64' });
    }

    try {
        await supabaseUpdate('news_reports', `id=eq.${reportId}`, { audio_base64: audioBase64 });
        console.log(`  💾 Cached TTS audio for report ${reportId.slice(0, 8)}...`);
        res.json({ ok: true });
    } catch (err) {
        console.error('Cache audio error:', err.message);
        res.status(500).json({ error: err.message });
    }
});

// ─── 5. Supabase status — check what's stored ─────────────────────────
app.get('/api/supabase/status', async (req, res) => {
    try {
        const rows = await supabaseSelect('news_reports',
            'select=id,topic,batch_time,created_at&order=created_at.desc&limit=50'
        );
        res.json({
            ok: true,
            count: rows ? rows.length : 0,
            reports: rows || [],
            currentBatch: getCurrentBatchTime()
        });
    } catch (err) {
        res.json({ ok: false, error: err.message });
    }
});

// ─── 6. Manual refresh: fetch → Gemini → save all topics ─────────────
app.post('/api/refresh-all', async (req, res) => {
    const batchTime = getCurrentBatchTime();
    const results = {};

    console.log(`\n  🔄 Manual refresh started (batch: ${batchTime})`);

    for (const [topicKey, gnewsTopic] of Object.entries(TOPIC_MAP)) {
        try {
            // Check if already exists
            const existing = await supabaseSelect('news_reports',
                `topic=eq.${topicKey}&batch_time=eq.${encodeURIComponent(batchTime)}&limit=1`
            );
            if (existing && existing.length > 0) {
                results[topicKey] = 'already cached';
                continue;
            }

            // Fetch from GNews
            const newsUrl = `https://gnews.io/api/v4/top-headlines?topic=${gnewsTopic}&lang=en&country=in&max=10&apikey=${GNEWS_API_KEY}`;
            const newsResult = await apiRequest(newsUrl);
            const articles = newsResult.data?.articles || [];
            if (articles.length === 0) { results[topicKey] = 'no articles'; continue; }

            // Generate Gemini report
            const prompt = buildPrompt(articles, topicKey);
            const geminiUrl = `https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=${GEMINI_API_KEY}`;
            const geminiResult = await apiRequest(geminiUrl, {
                method: 'POST',
                body: { contents: [{ parts: [{ text: prompt }] }] }
            });
            const reportText = geminiResult.data?.candidates?.[0]?.content?.parts?.[0]?.text || '';

            if (!reportText) { results[topicKey] = 'gemini failed'; continue; }

            // Save to Supabase
            const articlesJson = JSON.stringify(
                articles.map(a => ({ title: a.title, source: a.source?.name || 'News' }))
            );
            await supabaseInsert('news_reports', {
                topic: topicKey,
                report_text: reportText,
                articles_json: articlesJson,
                batch_time: batchTime
            });
            results[topicKey] = 'saved';
        } catch (err) {
            results[topicKey] = `error: ${err.message}`;
        }
    }

    console.log('  🔄 Refresh results:', results);
    res.json({ batchTime, results });
});

function buildPrompt(articles, topicName) {
    const articleList = articles.map((a, i) =>
        `Article ${i + 1}:\n  Title: ${a.title}\n  Description: ${a.description || 'N/A'}\n  Content: ${(a.content || '').slice(0, 400)}\n  Source: ${a.source?.name || 'Unknown'}`
    ).join('\n\n');

    return `You are a professional news anchor presenting a news bulletin on the topic "${topicName}".
Read through ALL of the following ${articles.length} news articles and create a complete news report.

For EACH article, write a clear 2-3 sentence explanation as if you are reading it aloud on air.
Separate each story with the marker "---NEXT---" on its own line.

Rules:
- Use conversational, easy-to-understand English suitable for text-to-speech
- Do NOT use bullet points, numbering, or markdown
- Make smooth transitions between stories
- Start the report with a brief greeting like "Here are today's top ${topicName} headlines."
- End with a brief sign-off like "That's all for now. Stay informed!"
- Each story segment should be 2-3 sentences only

Here are the articles:

${articleList}

News report (separate each story with ---NEXT---):`;
}

// ─── Start ────────────────────────────────────────────────────────────
app.listen(PORT, () => {
    console.log('');
    console.log('  🎙️  AI News Reader — Web Version');
    console.log('  ─────────────────────────────────');
    console.log(`  Open: http://localhost:${PORT}`);
    console.log(`  Supabase: ${SUPABASE_URL}`);
    console.log(`  Batch: ${getCurrentBatchTime()}`);
    console.log('');
});
