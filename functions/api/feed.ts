interface FeedVideo {
  videoId: string;
  title: string;
  author: string;
  published: string;
  thumbnail: string;
  description: string;
  views?: number;
}

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
    playlistId: resolvedPlaylistId || (resolvedChannelId?.startsWith('UC') ? `UU${resolvedChannelId.slice(2)}` : undefined),
    videos,
  };
}

async function resolveChannelIdFromHandle(urlStr: string): Promise<string | null> {
  const parsed = new URL(urlStr);
  const allowedHosts = ['youtube.com', 'www.youtube.com', 'm.youtube.com'];
  if (!allowedHosts.includes(parsed.hostname.toLowerCase())) {
    return null;
  }

  const res = await fetch(parsed.toString(), {
    headers: {
      'User-Agent': 'Mozilla/5.0 (compatible; FrameStackBot/1.0)',
      'Accept-Language': 'en-US,en;q=0.9',
    },
  });
  if (!res.ok) return null;
  const html = await res.text();

  const patterns = [
    /"externalId":"(UC[\w-]+)"/,
    /<meta\s+itemprop="identifier"\s+content="(UC[\w-]+)"/i,
    /"channelId":"(UC[\w-]+)"/,
    /channel_id=(UC[\w-]+)/,
  ];
  for (const pattern of patterns) {
    const match = html.match(pattern);
    if (match?.[1]) return match[1];
  }
  return null;
}

export async function onRequestGet(context: { request: Request }): Promise<Response> {
  const reqUrl = new URL(context.request.url);
  const channelIdParam = reqUrl.searchParams.get('channelId')?.trim();
  const playlistIdParam = reqUrl.searchParams.get('playlistId')?.trim();
  const handleUrlParam = reqUrl.searchParams.get('url')?.trim();

  const headers = {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'public, s-maxage=300, max-age=120',
    'Access-Control-Allow-Origin': '*',
  };

  try {
    let channelId = channelIdParam && /^UC[\w-]+$/.test(channelIdParam) ? channelIdParam : undefined;
    let playlistId = playlistIdParam && /^[A-Za-z0-9_-]+$/.test(playlistIdParam) ? playlistIdParam : undefined;

    if (!channelId && !playlistId && handleUrlParam) {
      const resolved = await resolveChannelIdFromHandle(handleUrlParam);
      if (resolved) {
        channelId = resolved;
      } else {
        return new Response(JSON.stringify({ error: 'Could not resolve channel ID from handle URL.' }), {
          status: 404,
          headers,
        });
      }
    }

    if (!channelId && !playlistId) {
      return new Response(JSON.stringify({ error: 'Provide channelId, playlistId, or url query parameter.' }), {
        status: 400,
        headers,
      });
    }

    const feedUrl = playlistId
      ? `https://www.youtube.com/feeds/videos.xml?playlist_id=${encodeURIComponent(playlistId)}`
      : `https://www.youtube.com/feeds/videos.xml?channel_id=${encodeURIComponent(channelId!)}`;

    const feedRes = await fetch(feedUrl, {
      headers: {
        'User-Agent': 'Mozilla/5.0 (compatible; FrameStackBot/1.0)',
      },
    });

    if (!feedRes.ok) {
      return new Response(
        JSON.stringify({
          error: `YouTube feed returned HTTP ${feedRes.status}`,
          channelId,
          playlistId,
        }),
        { status: feedRes.status, headers }
      );
    }

    const xml = await feedRes.text();
    const parsed = parseYouTubeAtom(xml, channelId, playlistId);

    return new Response(JSON.stringify(parsed), { status: 200, headers });
  } catch (err) {
    const message = err instanceof Error ? err.message : 'Unexpected feed error';
    return new Response(JSON.stringify({ error: message }), { status: 500, headers });
  }
}
