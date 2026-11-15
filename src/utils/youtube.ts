import { FeedResponse, ParsedYouTubeInput, VideoItem, VideoSource } from '../types/youtube';

export function channelIdToUploadsPlaylist(channelId: string): string {
  if (channelId.startsWith('UC') && channelId.length > 2) {
    return `UU${channelId.slice(2)}`;
  }
  return channelId;
}

export function extractVideoIdFromUrl(url: string): string | null {
  try {
    const parsed = new URL(url);
    const v = parsed.searchParams.get('v');
    if (v && /^[\w-]{11}$/.test(v)) return v;
    if (parsed.hostname.includes('youtu.be')) {
      const seg = parsed.pathname.replace(/^\//, '').split('/')[0];
      if (seg && /^[\w-]{11}$/.test(seg)) return seg;
    }
    const pathMatch = parsed.pathname.match(/^\/(?:shorts|live|embed)\/([\w-]{11})/);
    if (pathMatch?.[1]) return pathMatch[1];
  } catch {
    const match = url.match(/(?:v=|youtu\.be\/|\/shorts\/|\/live\/)([\w-]{11})/);
    if (match?.[1]) return match[1];
  }
  return null;
}

export function parseYouTubeInput(rawInput: string): {
  parsed?: ParsedYouTubeInput;
  error?: string;
} {
  const input = rawInput.trim();
  if (!input) {
    return { error: 'Paste a YouTube playlist URL, channel URL, @handle, or ID.' };
  }

  // Raw Channel ID (UC...)
  if (/^UC[\w-]{20,}$/.test(input)) {
    return {
      parsed: {
        type: 'channel',
        channelId: input,
        playlistId: channelIdToUploadsPlaylist(input),
        canonicalUrl: `https://www.youtube.com/channel/${input}`,
      },
    };
  }

  // Raw Playlist ID (PL..., UU..., OLAK..., FL..., RD...)
  if (/^(PL|UU|OLAK|FL|LL|RD)[\w-]{10,}$/.test(input)) {
    return {
      parsed: {
        type: 'playlist',
        playlistId: input,
        canonicalUrl: `https://www.youtube.com/playlist?list=${input}`,
      },
    };
  }

  // Raw @handle
  if (/^@[\w.-]{2,}$/.test(input)) {
    return {
      parsed: {
        type: 'channel',
        handleUrl: `https://www.youtube.com/${input}`,
        canonicalUrl: `https://www.youtube.com/${input}`,
      },
    };
  }

  // Full URL parsing
  let normalized = input;
  if (!/^https?:\/\//i.test(normalized)) {
    normalized = `https://${normalized}`;
  }

  try {
    const url = new URL(normalized);
    const host = url.hostname.toLowerCase();
    if (!host.includes('youtube.com') && !host.includes('youtu.be')) {
      return { error: 'Please enter a valid YouTube channel or playlist URL.' };
    }

    const listParam = url.searchParams.get('list')?.trim();
    if (listParam && /^[A-Za-z0-9_-]{10,}$/.test(listParam)) {
      return {
        parsed: {
          type: 'playlist',
          playlistId: listParam,
          canonicalUrl: `https://www.youtube.com/playlist?list=${listParam}`,
        },
      };
    }

    const channelMatch = url.pathname.match(/\/channel\/(UC[\w-]+)/);
    if (channelMatch?.[1]) {
      const channelId = channelMatch[1];
      return {
        parsed: {
          type: 'channel',
          channelId,
          playlistId: channelIdToUploadsPlaylist(channelId),
          canonicalUrl: `https://www.youtube.com/channel/${channelId}`,
        },
      };
    }

    const handleMatch = url.pathname.match(/^\/(@[\w.-]+|c\/[\w.-]+|user\/[\w.-]+)/);
    if (handleMatch?.[1]) {
      const handleUrl = `https://www.youtube.com/${handleMatch[1]}`;
      return {
        parsed: {
          type: 'channel',
          handleUrl,
          canonicalUrl: handleUrl,
        },
      };
    }

    if (url.searchParams.get('v') || host.includes('youtu.be')) {
      return {
        error:
          'That looks like a single video link without a playlist. Add ?list=PL... or paste the channel or playlist URL.',
      };
    }

    return { error: 'Could not recognize a YouTube channel or playlist in that URL.' };
  } catch {
    return { error: 'Invalid URL format. Paste a YouTube channel or playlist URL.' };
  }
}

export async function fetchSourceFeed(
  source: Pick<VideoSource, 'type' | 'channelId' | 'playlistId' | 'url'>
): Promise<FeedResponse> {
  const effectivePlaylistId =
    source.type === 'playlist'
      ? source.playlistId
      : source.playlistId || (source.channelId ? channelIdToUploadsPlaylist(source.channelId) : undefined);

  // Tier 1: Server / Cloudflare Pages Function (/api/feed)
  try {
    const params = new URLSearchParams();
    if (source.type === 'playlist' && source.playlistId) {
      params.set('playlistId', source.playlistId);
    } else if (source.channelId) {
      params.set('channelId', source.channelId);
    } else if (source.url) {
      params.set('url', source.url);
    }

    let res = await fetch(`./api/feed?${params.toString()}`);
    if (!res.ok) {
      res = await fetch(`/api/feed?${params.toString()}`);
    }
    const contentType = res.headers.get('content-type') || '';
    if (res.ok && contentType.includes('application/json')) {
      const data = await res.json();
      if (Array.isArray(data.videos) && data.videos.length > 0) {
        return {
          sourceTitle: data.sourceTitle || 'YouTube Feed',
          channelId: data.channelId || source.channelId,
          playlistId: data.playlistId || effectivePlaylistId,
          videos: data.videos,
          resolvedVia: 'api',
        };
      }
      if (data.channelId || data.playlistId) {
        return {
          sourceTitle: data.sourceTitle || 'YouTube Feed',
          channelId: data.channelId || source.channelId,
          playlistId: data.playlistId || effectivePlaylistId,
          videos: data.videos || [],
          resolvedVia: 'api',
        };
      }
    }
  } catch {
    // Fall through cleanly to static client-side strategies
  }

  // Tier 2: Static GitHub Pages fallback via rss2json for public Atom XML
  const rssTarget =
    source.type === 'playlist' && source.playlistId
      ? `https://www.youtube.com/feeds/videos.xml?playlist_id=${encodeURIComponent(source.playlistId)}`
      : source.channelId
        ? `https://www.youtube.com/feeds/videos.xml?channel_id=${encodeURIComponent(source.channelId)}`
        : null;

  if (rssTarget) {
    try {
      const rssRes = await fetch(
        `https://api.rss2json.com/v1/api.json?rss_url=${encodeURIComponent(rssTarget)}`
      );
      if (rssRes.ok) {
        const rssData = await rssRes.json();
        if (rssData.status === 'ok' && Array.isArray(rssData.items)) {
          const videos: VideoItem[] = rssData.items
            .map((item: { link?: string; title?: string; author?: string; pubDate?: string; thumbnail?: string; description?: string }) => {
              const videoId = item.link ? extractVideoIdFromUrl(item.link) : null;
              if (!videoId) return null;
              return {
                videoId,
                title: item.title || 'Untitled Video',
                author: item.author || rssData.feed?.title || 'YouTube',
                published: item.pubDate,
                thumbnail: item.thumbnail || `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg`,
                description: item.description,
              } as VideoItem;
            })
            .filter(Boolean) as VideoItem[];

          if (videos.length > 0) {
            return {
              sourceTitle: rssData.feed?.title || 'YouTube Feed',
              channelId: source.channelId,
              playlistId: effectivePlaylistId,
              videos,
              resolvedVia: 'rss2json',
            };
          }
        }
      }
    } catch {
      // Fall through to IFrame API native playlist loading
    }
  }

  // Tier 3: Native YouTube IFrame API handles playlist/UU uploads playback even without RSS
  return {
    sourceTitle: '',
    channelId: source.channelId,
    playlistId: effectivePlaylistId,
    videos: [],
    resolvedVia: 'iframe-only',
  };
}

export async function fetchSingleVideoMeta(videoId: string): Promise<Partial<VideoItem> | null> {
  try {
    const res = await fetch(
      `https://noembed.com/embed?url=${encodeURIComponent(`https://www.youtube.com/watch?v=${videoId}`)}`
    );
    if (!res.ok) return null;
    const data = await res.json();
    if (data.error || !data.title) return null;
    return {
      videoId,
      title: data.title,
      author: data.author_name || 'YouTube',
      thumbnail: data.thumbnail_url || `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg`,
    };
  } catch {
    return null;
  }
}

export function formatTimecode(totalSeconds: number): string {
  if (!Number.isFinite(totalSeconds) || totalSeconds < 0) return '0:00';
  const secs = Math.floor(totalSeconds);
  const hours = Math.floor(secs / 3600);
  const mins = Math.floor((secs % 3600) / 60);
  const rem = secs % 60;
  if (hours > 0) {
    return `${hours}:${String(mins).padStart(2, '0')}:${String(rem).padStart(2, '0')}`;
  }
  return `${mins}:${String(rem).padStart(2, '0')}`;
}

export function formatRelativeDate(isoDate?: string): string {
  if (!isoDate) return '';
  const date = new Date(isoDate);
  if (Number.isNaN(date.getTime())) return '';
  return date.toLocaleDateString('en-US', {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
  });
}

export function formatViews(views?: number): string {
  if (typeof views !== 'number' || !Number.isFinite(views)) return '';
  if (views >= 1_000_000) return `${(views / 1_000_000).toFixed(1)}M views`;
  if (views >= 1_000) return `${Math.round(views / 1_000)}K views`;
  return `${views} views`;
}

export function serializeSourcesJson(sources: VideoSource[]): string {
  const clean = sources.map((s) => ({
    id: s.id,
    type: s.type,
    title: s.title,
    category: s.category,
    ...(s.channelId ? { channelId: s.channelId } : {}),
    ...(s.playlistId ? { playlistId: s.playlistId } : {}),
    url: s.url,
  }));
  return JSON.stringify({ sources: clean }, null, 2);
}
