import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { AddSourceModal } from './components/AddSourceModal';
import { DeployGuide } from './components/DeployGuide';
import { QueuePanel } from './components/QueuePanel';
import { SourcesSection } from './components/SourcesSection';
import { TopBar } from './components/TopBar';
import { YouTubeStage } from './components/YouTubeStage';
import { ParsedYouTubeInput, VideoItem, VideoSource } from './types/youtube';
import {
  channelIdToUploadsPlaylist,
  fetchSingleVideoMeta,
  fetchSourceFeed,
  serializeSourcesJson,
} from './utils/youtube';

const STORAGE_KEY = 'framestack.sources.v1';
const ACTIVE_SOURCE_KEY = 'framestack.activeSource.v1';

const FALLBACK_SOURCES: VideoSource[] = [
  {
    id: 'src-3b1b',
    type: 'channel',
    title: '3Blue1Brown',
    category: 'Mathematics',
    channelId: 'UCYO_jab_esuFRV4b17AJtAw',
    playlistId: 'UUYO_jab_esuFRV4b17AJtAw',
    url: 'https://www.youtube.com/channel/UCYO_jab_esuFRV4b17AJtAw',
  },
  {
    id: 'src-kurzgesagt',
    type: 'channel',
    title: 'Kurzgesagt – In a Nutshell',
    category: 'Science',
    channelId: 'UCsXVk37bltHxD1rDPwtNM8Q',
    playlistId: 'UUsXVk37bltHxD1rDPwtNM8Q',
    url: 'https://www.youtube.com/channel/UCsXVk37bltHxD1rDPwtNM8Q',
  },
  {
    id: 'src-tinydesk',
    type: 'playlist',
    title: 'Tiny Desk Concerts',
    category: 'Music',
    playlistId: 'PL1B627337ED6F55F0',
    url: 'https://www.youtube.com/playlist?list=PL1B627337ED6F55F0',
  },
];

export default function App() {
  const [sources, setSources] = useState<VideoSource[]>([]);
  const [activeSourceId, setActiveSourceId] = useState<string | null>(null);
  const [activeVideoId, setActiveVideoId] = useState<string | null>(null);
  const [loadingSourceIds, setLoadingSourceIds] = useState<Record<string, boolean>>({});
  const [shuffle, setShuffle] = useState(false);
  const [loopQueue, setLoopQueue] = useState(true);
  const [theaterMode, setTheaterMode] = useState(false);
  const [addModalOpen, setAddModalOpen] = useState(false);
  const [activeNav, setActiveNav] = useState<'player' | 'sources' | 'deploy'>('player');
  const [notice, setNotice] = useState<{ type: 'info' | 'error'; text: string } | null>(null);

  const hydratedRef = useRef(false);

  const showNotice = useCallback((type: 'info' | 'error', text: string) => {
    setNotice({ type, text });
    window.setTimeout(() => {
      setNotice((prev) => (prev?.text === text ? null : prev));
    }, 3500);
  }, []);

  const refreshFeedForSource = useCallback(
    async (source: VideoSource, selectFirstIfEmpty = false) => {
      setLoadingSourceIds((prev) => ({ ...prev, [source.id]: true }));
      try {
        const result = await fetchSourceFeed(source);
        setSources((prev) =>
          prev.map((item) => {
            if (item.id !== source.id) return item;
            const nextVideos = result.videos.length > 0 ? result.videos : item.videos || [];
            return {
              ...item,
              title:
                item.title === 'Loading feed...' && result.sourceTitle
                  ? result.sourceTitle
                  : item.title || result.sourceTitle,
              channelId: result.channelId || item.channelId,
              playlistId: result.playlistId || item.playlistId,
              videos: nextVideos,
              lastFetched: Date.now(),
              error:
                nextVideos.length === 0 && !result.playlistId
                  ? 'Could not retrieve videos from this feed.'
                  : undefined,
            };
          })
        );

        if (selectFirstIfEmpty && result.videos.length > 0) {
          setActiveVideoId((current) => current || result.videos[0].videoId);
        }
      } catch (err) {
        const message = err instanceof Error ? err.message : 'Feed fetch failed';
        setSources((prev) =>
          prev.map((item) => (item.id === source.id ? { ...item, error: message } : item))
        );
      } finally {
        setLoadingSourceIds((prev) => ({ ...prev, [source.id]: false }));
      }
    },
    []
  );

  // Initial load from localStorage or ./sources.json
  useEffect(() => {
    if (hydratedRef.current) return;
    hydratedRef.current = true;

    async function bootstrap() {
      let initialSources: VideoSource[] = [];
      try {
        const stored = localStorage.getItem(STORAGE_KEY);
        if (stored) {
          const parsed = JSON.parse(stored);
          if (Array.isArray(parsed) && parsed.length > 0) {
            initialSources = parsed;
          }
        }
      } catch {
        // Ignore corrupted localStorage
      }

      if (initialSources.length === 0) {
        try {
          const res = await fetch('./sources.json');
          if (res.ok) {
            const data = await res.json();
            if (Array.isArray(data.sources) && data.sources.length > 0) {
              initialSources = data.sources;
            }
          }
        } catch {
          // Fall back to embedded defaults
        }
      }

      if (initialSources.length === 0) {
        initialSources = FALLBACK_SOURCES;
      }

      const savedActiveId = localStorage.getItem(ACTIVE_SOURCE_KEY);
      const firstActive =
        initialSources.find((s) => s.id === savedActiveId) || initialSources[0] || null;

      setSources(initialSources);
      if (firstActive) {
        setActiveSourceId(firstActive.id);
        if (firstActive.videos && firstActive.videos.length > 0) {
          setActiveVideoId(firstActive.videos[0].videoId);
        }
        await refreshFeedForSource(firstActive, true);
      }

      // Background-refresh remaining sources for card previews
      const remaining = initialSources.filter((s) => s.id !== firstActive?.id);
      for (const src of remaining) {
        if (!src.videos || src.videos.length === 0) {
          void refreshFeedForSource(src, false);
        }
      }
    }

    void bootstrap();
  }, [refreshFeedForSource]);

  // Persist curated sources cleanly (without bloating localStorage with massive descriptions)
  useEffect(() => {
    if (sources.length === 0) return;
    try {
      const compact = sources.map((s) => ({
        ...s,
        videos: (s.videos || []).slice(0, 25),
      }));
      localStorage.setItem(STORAGE_KEY, JSON.stringify(compact));
    } catch {
      // Ignore quota exceeded
    }
  }, [sources]);

  useEffect(() => {
    if (activeSourceId) {
      localStorage.setItem(ACTIVE_SOURCE_KEY, activeSourceId);
    }
  }, [activeSourceId]);

  const activeSource = useMemo(
    () => sources.find((s) => s.id === activeSourceId) || null,
    [sources, activeSourceId]
  );

  const activeVideos = useMemo(() => activeSource?.videos || [], [activeSource]);

  const activeVideo = useMemo(() => {
    if (activeVideos.length === 0) return null;
    return activeVideos.find((v) => v.videoId === activeVideoId) || activeVideos[0];
  }, [activeVideos, activeVideoId]);

  const activeIndex = useMemo(() => {
    if (!activeVideo) return -1;
    return activeVideos.findIndex((v) => v.videoId === activeVideo.videoId);
  }, [activeVideos, activeVideo]);

  const handleSelectSource = (sourceId: string) => {
    const target = sources.find((s) => s.id === sourceId);
    if (!target) return;
    setActiveSourceId(target.id);
    if (target.videos && target.videos.length > 0) {
      setActiveVideoId(target.videos[0].videoId);
    } else {
      setActiveVideoId(null);
      void refreshFeedForSource(target, true);
    }
    document.getElementById('player')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    setActiveNav('player');
  };

  const handleNextVideo = useCallback(() => {
    if (activeVideos.length === 0) return;
    if (shuffle && activeVideos.length > 1) {
      const candidates = activeVideos.filter((v) => v.videoId !== activeVideo?.videoId);
      const pick = candidates[Math.floor(Math.random() * candidates.length)];
      if (pick) setActiveVideoId(pick.videoId);
      return;
    }

    if (activeIndex < activeVideos.length - 1) {
      setActiveVideoId(activeVideos[activeIndex + 1].videoId);
    } else if (loopQueue) {
      setActiveVideoId(activeVideos[0].videoId);
    }
  }, [activeVideos, activeIndex, activeVideo, shuffle, loopQueue]);

  const handlePreviousVideo = useCallback(() => {
    if (activeVideos.length === 0) return;
    if (activeIndex > 0) {
      setActiveVideoId(activeVideos[activeIndex - 1].videoId);
    } else if (loopQueue) {
      setActiveVideoId(activeVideos[activeVideos.length - 1].videoId);
    }
  }, [activeVideos, activeIndex, loopQueue]);

  const handleShufflePick = () => {
    if (activeVideos.length < 2) return;
    const candidates = activeVideos.filter((v) => v.videoId !== activeVideo?.videoId);
    const pick = candidates[Math.floor(Math.random() * candidates.length)];
    if (pick) setActiveVideoId(pick.videoId);
  };

  const handleAddSource = async ({
    parsed,
    customTitle,
    category,
  }: {
    parsed: ParsedYouTubeInput;
    customTitle: string;
    category: string;
  }): Promise<{ ok: boolean; error?: string }> => {
    const feed = await fetchSourceFeed({
      type: parsed.type,
      channelId: parsed.channelId,
      playlistId: parsed.playlistId,
      url: parsed.handleUrl || parsed.canonicalUrl,
    });

    const resolvedChannelId = feed.channelId || parsed.channelId;
    const resolvedPlaylistId =
      feed.playlistId ||
      parsed.playlistId ||
      (resolvedChannelId ? channelIdToUploadsPlaylist(resolvedChannelId) : undefined);

    if (!resolvedPlaylistId && feed.videos.length === 0) {
      return {
        ok: false,
        error:
          'Could not resolve this channel or playlist. If hosting purely statically on GitHub Pages, try pasting the channel UC... URL or playlist PL... URL.',
      };
    }

    const newSource: VideoSource = {
      id: `src-${Date.now().toString(36)}`,
      type: parsed.type,
      title: customTitle || feed.sourceTitle || (parsed.type === 'channel' ? 'YouTube Channel' : 'YouTube Playlist'),
      category,
      channelId: resolvedChannelId,
      playlistId: resolvedPlaylistId,
      url: parsed.canonicalUrl,
      videos: feed.videos,
      lastFetched: Date.now(),
    };

    setSources((prev) => [newSource, ...prev]);
    setActiveSourceId(newSource.id);
    setActiveVideoId(feed.videos[0]?.videoId || null);
    showNotice('info', `Added "${newSource.title}" to your library.`);
    return { ok: true };
  };

  const handleRemoveSource = (sourceId: string) => {
    setSources((prev) => {
      const next = prev.filter((s) => s.id !== sourceId);
      if (activeSourceId === sourceId) {
        const replacement = next[0] || null;
        setActiveSourceId(replacement?.id || null);
        setActiveVideoId(replacement?.videos?.[0]?.videoId || null);
      }
      return next;
    });
    showNotice('info', 'Removed source from library.');
  };

  // Discover playlist IDs from YouTube IFrame API when running in Tier 3 pure-static fallback
  const handleDiscoverPlaylistIds = useCallback(
    async (ids: string[]) => {
      if (!activeSourceId) return;
      const current = sources.find((s) => s.id === activeSourceId);
      if (!current) return;

      const existingIds = new Set((current.videos || []).map((v) => v.videoId));
      const missingIds = ids.filter((id) => !existingIds.has(id)).slice(0, 20);
      if (missingIds.length === 0) return;

      const placeholderItems: VideoItem[] = missingIds.map((videoId) => ({
        videoId,
        title: `Video ${videoId}`,
        author: current.title,
        thumbnail: `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg`,
      }));

      setSources((prev) =>
        prev.map((s) =>
          s.id === activeSourceId
            ? { ...s, videos: [...(s.videos || []), ...placeholderItems] }
            : s
        )
      );

      // Hydrate real video titles via CORS noembed
      const hydrated = await Promise.all(
        missingIds.map(async (id) => {
          const meta = await fetchSingleVideoMeta(id);
          return meta ? { videoId: id, ...meta } : null;
        })
      );

      setSources((prev) =>
        prev.map((s) => {
          if (s.id !== activeSourceId) return s;
          const map = new Map<string, Partial<VideoItem>>();
          for (const item of hydrated) {
            if (item?.videoId) map.set(item.videoId, item);
          }
          return {
            ...s,
            videos: (s.videos || []).map((v) => {
              const patch = map.get(v.videoId);
              return patch ? { ...v, ...patch } : v;
            }),
          };
        })
      );
    },
    [activeSourceId, sources]
  );

  const handleExportJson = () => {
    const content = serializeSourcesJson(sources);
    const blob = new Blob([content], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = 'sources.json';
    link.click();
    URL.revokeObjectURL(url);
    showNotice('info', 'Downloaded sources.json.');
  };

  const handleImportJson = async (file: File) => {
    try {
      const text = await file.text();
      const parsed = JSON.parse(text);
      const list: unknown[] = Array.isArray(parsed) ? parsed : parsed?.sources;
      if (!Array.isArray(list) || list.length === 0) {
        showNotice('error', 'Invalid sources.json: expected a non-empty "sources" array.');
        return;
      }

      const validSources: VideoSource[] = list
        .filter((item): item is VideoSource => {
          if (!item || typeof item !== 'object') return false;
          const s = item as Partial<VideoSource>;
          return Boolean(s.title && (s.channelId || s.playlistId || s.url));
        })
        .map((s, idx) => ({
          id: s.id || `imported-${Date.now()}-${idx}`,
          type: s.type === 'playlist' ? 'playlist' : 'channel',
          title: s.title,
          category: s.category || 'Imported',
          channelId: s.channelId,
          playlistId:
            s.playlistId ||
            (s.channelId ? channelIdToUploadsPlaylist(s.channelId) : undefined),
          url: s.url || '',
        }));

      if (validSources.length === 0) {
        showNotice('error', 'No valid YouTube sources found in the imported file.');
        return;
      }

      setSources(validSources);
      setActiveSourceId(validSources[0].id);
      setActiveVideoId(null);
      void refreshFeedForSource(validSources[0], true);
      showNotice('info', `Imported ${validSources.length} sources.`);
    } catch {
      showNotice('error', 'Could not parse JSON file.');
    }
  };

  const handleResetDefaults = async () => {
    localStorage.removeItem(STORAGE_KEY);
    localStorage.removeItem(ACTIVE_SOURCE_KEY);
    let defaults = FALLBACK_SOURCES;
    try {
      const res = await fetch('./sources.json');
      if (res.ok) {
        const data = await res.json();
        if (Array.isArray(data.sources) && data.sources.length > 0) {
          defaults = data.sources;
        }
      }
    } catch {
      // Use embedded fallback
    }
    setSources(defaults);
    if (defaults[0]) {
      setActiveSourceId(defaults[0].id);
      setActiveVideoId(null);
      void refreshFeedForSource(defaults[0], true);
    }
    showNotice('info', 'Restored default sources from sources.json.');
  };

  const handleNavigate = (section: 'player' | 'sources' | 'deploy' | 'queue') => {
    if (section === 'player') setActiveNav('player');
    if (section === 'sources') setActiveNav('sources');
    if (section === 'deploy') setActiveNav('deploy');
    document.getElementById(section)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  };

  const hasNext =
    activeVideos.length > 0 && (loopQueue || shuffle || activeIndex < activeVideos.length - 1);
  const hasPrevious = activeVideos.length > 0 && (loopQueue || activeIndex > 0);

  return (
    <div className="min-h-screen bg-[#09090B] text-zinc-100 flex flex-col">
      <TopBar
        activeSection={activeNav}
        onNavigate={handleNavigate}
        onOpenAddModal={() => setAddModalOpen(true)}
      />

      {notice && (
        <div className="max-w-[1440px] w-full mx-auto px-4 sm:px-6 lg:px-8 pt-4">
          <div
            role="status"
            className={`px-4 py-2.5 rounded-lg text-xs font-medium border ${
              notice.type === 'error'
                ? 'bg-red-500/10 border-red-500/30 text-red-300'
                : 'bg-amber-500/10 border-amber-500/30 text-amber-200'
            }`}
          >
            {notice.text}
          </div>
        </div>
      )}

      <main className="flex-1 max-w-[1440px] w-full mx-auto px-4 sm:px-6 lg:px-8 py-6 sm:py-8 space-y-12">
        {/* Primary Theater Workspace */}
        <section id="player" aria-label="Video Player Theater">
          <div
            className={`grid grid-cols-1 gap-6 items-start ${
              theaterMode ? 'lg:grid-cols-1' : 'lg:grid-cols-12'
            }`}
          >
            <div className={theaterMode ? 'lg:col-span-1' : 'lg:col-span-8'}>
              <YouTubeStage
                source={activeSource}
                activeVideo={activeVideo}
                hasNext={hasNext}
                hasPrevious={hasPrevious}
                shuffle={shuffle}
                loopQueue={loopQueue}
                theaterMode={theaterMode}
                onNext={handleNextVideo}
                onPrevious={handlePreviousVideo}
                onToggleShuffle={() => setShuffle((s) => !s)}
                onToggleLoop={() => setLoopQueue((l) => !l)}
                onToggleTheater={() => setTheaterMode((t) => !t)}
                onDiscoverPlaylistIds={handleDiscoverPlaylistIds}
              />
            </div>

            <div className={theaterMode ? 'lg:col-span-1' : 'lg:col-span-4'}>
              <QueuePanel
                source={activeSource}
                videos={activeVideos}
                activeVideoId={activeVideo?.videoId || null}
                loading={Boolean(activeSourceId && loadingSourceIds[activeSourceId])}
                error={activeSource?.error}
                onSelectVideo={(video) => setActiveVideoId(video.videoId)}
                onRefreshSource={() => {
                  if (activeSource) void refreshFeedForSource(activeSource, false);
                }}
                onShufflePick={handleShufflePick}
              />
            </div>
          </div>
        </section>

        {/* Channels & Playlists Library */}
        <SourcesSection
          sources={sources}
          activeSourceId={activeSourceId}
          loadingSourceIds={loadingSourceIds}
          onSelectSource={handleSelectSource}
          onRefreshSource={(id) => {
            const target = sources.find((s) => s.id === id);
            if (target) void refreshFeedForSource(target, false);
          }}
          onRemoveSource={handleRemoveSource}
          onOpenAddModal={() => setAddModalOpen(true)}
          onExportJson={handleExportJson}
          onImportJson={handleImportJson}
        />

        {/* GitHub Pages & Cloudflare Pages Deployment Workspace */}
        <DeployGuide sources={sources} onDownloadSourcesJson={handleExportJson} />
      </main>

      <footer className="border-t border-zinc-800/80 mt-12">
        <div className="max-w-[1440px] mx-auto px-4 sm:px-6 lg:px-8 py-6 flex flex-col sm:flex-row items-start sm:items-center justify-between gap-4 text-xs text-zinc-500">
          <p>
            Shortcuts: Space play/pause · J/L seek 10s · N/P next/previous · M mute · F fullscreen
          </p>
          <div className="flex items-center gap-4">
            <button
              type="button"
              onClick={handleResetDefaults}
              className="text-zinc-400 hover:text-zinc-200 transition-colors cursor-pointer"
            >
              Reset Default Sources
            </button>
            <span aria-hidden="true">·</span>
            <span>Static &amp; Edge Ready</span>
          </div>
        </div>
      </footer>

      <AddSourceModal
        open={addModalOpen}
        onClose={() => setAddModalOpen(false)}
        onAdd={handleAddSource}
      />
    </div>
  );
}
