import express from 'express';
import { createServer as createHttpServer } from 'http';
import path from 'path';
import { fileURLToPath } from 'url';
import { createServer as createViteServer } from 'vite';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

interface FeedVideo {
  videoId: string;
  title: string;
  author: string;
  published: string;
  thumbnail: string;
  description: string;
  views?: number;
}

const YOUTUBE_FETCH_HEADERS = {
  'User-Agent':
    'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36',
  'Accept-Language': 'en-US,en;q=0.9',
  Cookie: 'CONSENT=PENDING+987; SOCS=CAESEwgDEgk0ODE3Nzk3MjQaAmVuIAEaBgiA_LyaBg',
};

function decodeXmlEntities(input: string): string {
  return input
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&#x27;/g, "'")
    .replace(/&#(\d+);/g, (_, dec) => String.fromCharCode(Number(dec)));
}

function extractTag(block: string, tagName: string): string {
  const regex = new RegExp(`<${tagName}[^>]*>([\\s\\S]*?)<\\/${tagName}>`, 'i');
  const match = block.match(regex);
  return match ? decodeXmlEntities(match[1].trim()) : '';
}

function parseYouTubeAtom(xml: string, fallbackChannelId?: string, fallbackPlaylistId?: string) {
  const firstEntryIndex = xml.indexOf('<entry>');
  const headerXml = firstEntryIndex > -1 ? xml.slice(0, firstEntryIndex) : xml;
  const sourceTitle = extractTag(headerXml, 'title') || 'YouTube Feed';
  const rawXmlChannelId = extractTag(headerXml, 'yt:channelId');
  const normalizedXmlChannelId =
    rawXmlChannelId && !rawXmlChannelId.startsWith('UC') && rawXmlChannelId.length === 22
      ? `UC${rawXmlChannelId}`
      : rawXmlChannelId;
  const resolvedChannelId = fallbackChannelId || normalizedXmlChannelId;
  const resolvedPlaylistId = extractTag(headerXml, 'yt:playlistId') || fallbackPlaylistId;

  const entryMatches = xml.match(/<entry>[\s\S]*?<\/entry>/g) || [];
  const videos: FeedVideo[] = [];

  for (const entry of entryMatches) {
    const videoId = extractTag(entry, 'yt:videoId');
    if (!videoId) continue;

    const title = extractTag(entry, 'title') || 'Untitled Video';
    const authorBlock = entry.match(/<author>[\s\S]*?<\/author>/i)?.[0] || '';
    const author = extractTag(authorBlock, 'name') || sourceTitle;
    const published = extractTag(entry, 'published');
    const description = extractTag(entry, 'media:description');
    const thumbMatch = entry.match(/<media:thumbnail[^>]*url="([^"]+)"/i);
    const thumbnail = thumbMatch?.[1] || `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg`;
    const viewsMatch = entry.match(/<media:statistics[^>]*views="(\d+)"/i);
    const views = viewsMatch ? Number(viewsMatch[1]) : undefined;

    videos.push({
      videoId,
      title,
      author,
      published,
      thumbnail,
      description,
      views,
    });
  }

  return {
    sourceTitle,
    channelId: resolvedChannelId,
    playlistId:
      resolvedPlaylistId ||
      (resolvedChannelId?.startsWith('UC') ? `UU${resolvedChannelId.slice(2)}` : undefined),
    videos,
  };
}

async function resolveChannelIdFromHandle(urlStr: string): Promise<string | null> {
  const parsed = new URL(urlStr);
  const allowedHosts = ['youtube.com', 'www.youtube.com', 'm.youtube.com'];
  if (!allowedHosts.includes(parsed.hostname.toLowerCase())) {
    return null;
  }

  const res = await fetch(parsed.toString(), { headers: YOUTUBE_FETCH_HEADERS });
  if (!res.ok) return null;
  const html = await res.text();

  const patterns = [
    /"externalId":"(UC[\w-]+)"/,
    /<meta\s+itemprop="identifier"\s+content="(UC[\w-]+)"/i,
    /"channelId":"(UC[\w-]+)"/,
    /channel_id=(UC[\w-]+)/,
    /"browseId":"(UC[\w-]+)"/,
  ];
  for (const pattern of patterns) {
    const match = html.match(pattern);
    if (match?.[1]) return match[1];
  }
  return null;
}

async function scrapePlaylistHtmlFallback(playlistId: string) {
  const res = await fetch(
    `https://www.youtube.com/playlist?list=${encodeURIComponent(playlistId)}`,
    { headers: YOUTUBE_FETCH_HEADERS }
  );
  if (!res.ok) return null;
  const html = await res.text();

  const dataMatch = html.match(/var ytInitialData\s*=\s*(\{[\s\S]*?\});<\/script>/);
  if (!dataMatch?.[1]) return null;

  try {
    const data = JSON.parse(dataMatch[1]);
    const sourceTitle =
      data?.metadata?.playlistMetadataRenderer?.title || 'YouTube Playlist';
    const tabs = data?.contents?.twoColumnBrowseResultsRenderer?.tabs || [];
    const videos: FeedVideo[] = [];
    const seen = new Set<string>();

    const walk = (node: unknown) => {
      if (!node || typeof node !== 'object') return;
      if (Array.isArray(node)) {
        for (const item of node) walk(item);
        return;
      }
      const obj = node as Record<string, unknown>;
      const renderer = obj.playlistVideoRenderer as Record<string, unknown> | undefined;
      if (renderer && typeof renderer.videoId === 'string' && !seen.has(renderer.videoId)) {
        seen.add(renderer.videoId);
        const videoId = renderer.videoId;
        const titleRuns = (renderer.title as { runs?: Array<{ text?: string }> })?.runs;
        const simpleTitle = (renderer.title as { simpleText?: string })?.simpleText;
        const title = titleRuns?.map((r) => r.text || '').join('') || simpleTitle || 'Untitled Video';
        const ownerRuns = (renderer.shortBylineText as { runs?: Array<{ text?: string }> })?.runs;
        const author = ownerRuns?.[0]?.text || sourceTitle;
        videos.push({
          videoId,
          title,
          author,
          published: '',
          thumbnail: `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg`,
          description: '',
        });
      }
      for (const val of Object.values(obj)) {
        walk(val);
      }
    };

    walk(tabs);
    if (videos.length === 0) return null;
    return {
      sourceTitle,
      playlistId,
      videos: videos.slice(0, 50),
    };
  } catch {
    return null;
  }
}

async function startServer() {
  const app = express();
  const httpServer = createHttpServer(app);
  const PORT = 3000;

  app.get('/api/feed', async (req, res) => {
    const channelIdParam = typeof req.query.channelId === 'string' ? req.query.channelId.trim() : undefined;
    const playlistIdParam = typeof req.query.playlistId === 'string' ? req.query.playlistId.trim() : undefined;
    const handleUrlParam = typeof req.query.url === 'string' ? req.query.url.trim() : undefined;

    try {
      let channelId = channelIdParam && /^UC[\w-]+$/.test(channelIdParam) ? channelIdParam : undefined;
      let playlistId =
        playlistIdParam && /^[A-Za-z0-9_-]+$/.test(playlistIdParam) ? playlistIdParam : undefined;

      if (!channelId && !playlistId && handleUrlParam) {
        const resolved = await resolveChannelIdFromHandle(handleUrlParam);
        if (resolved) {
          channelId = resolved;
        } else {
          return res.status(404).json({ error: 'Could not resolve channel ID from handle URL.' });
        }
      }

      if (!channelId && !playlistId) {
        return res.status(400).json({ error: 'Provide channelId, playlistId, or url query parameter.' });
      }

      const xmlChannelId =
        channelId || (playlistId?.startsWith('UU') ? `UC${playlistId.slice(2)}` : undefined);

      const feedUrl =
        playlistId && !playlistId.startsWith('UU')
          ? `https://www.youtube.com/feeds/videos.xml?playlist_id=${encodeURIComponent(playlistId)}`
          : `https://www.youtube.com/feeds/videos.xml?channel_id=${encodeURIComponent(xmlChannelId!)}`;

      const feedRes = await fetch(feedUrl, { headers: YOUTUBE_FETCH_HEADERS });

      if (feedRes.ok) {
        const xml = await feedRes.text();
        const parsed = parseYouTubeAtom(xml, xmlChannelId, playlistId);
        if (parsed.videos.length > 0) {
          res.setHeader('Cache-Control', 'public, max-age=120');
          return res.json(parsed);
        }
      }

      if (playlistId) {
        const scraped = await scrapePlaylistHtmlFallback(playlistId);
        if (scraped) {
          res.setHeader('Cache-Control', 'public, max-age=120');
          return res.json(scraped);
        }
      }

      return res.status(feedRes.ok ? 404 : feedRes.status).json({
        error: `YouTube feed returned HTTP ${feedRes.status}`,
        channelId: xmlChannelId,
        playlistId,
      });
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Unexpected feed error';
      return res.status(500).json({ error: message });
    }
  });

  if (process.env.NODE_ENV !== 'production') {
    const vite = await createViteServer({
      server: {
        middlewareMode: true,
        hmr: false,
        ws: { server: httpServer },
      },
      appType: 'spa',
    });
    app.use(vite.middlewares);
  } else {
    const distPath = path.resolve(__dirname, 'dist');
    app.use(express.static(distPath));
    app.get('*', (_req, res) => {
      res.sendFile(path.join(distPath, 'index.html'));
    });
  }

  httpServer.listen(PORT, '0.0.0.0', () => {
    console.log(`FrameStack server listening on http://localhost:${PORT}`);
  });
}

startServer();
