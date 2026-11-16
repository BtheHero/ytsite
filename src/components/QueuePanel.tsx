import { useMemo, useState } from 'react';
import { PanelRightClose, RefreshCw, Search, Shuffle, X } from 'lucide-react';
import { VideoItem, VideoSource } from '../types/youtube';
import { formatRelativeDate } from '../utils/youtube';
import { ThumbnailImage } from './ThumbnailImage';

interface QueuePanelProps {
  source: VideoSource | null;
  videos: VideoItem[];
  activeVideoId: string | null;
  loading: boolean;
  error?: string;
  onSelectVideo: (video: VideoItem) => void;
  onRefreshSource: () => void;
  onShufflePick: () => void;
  onHideSideMenu: () => void;
}

export function QueuePanel({
  source,
  videos,
  activeVideoId,
  loading,
  error,
  onSelectVideo,
  onRefreshSource,
  onShufflePick,
  onHideSideMenu,
}: QueuePanelProps) {
  const [query, setQuery] = useState('');

  const filteredVideos = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return videos;
    return videos.filter(
      (v) =>
        v.title.toLowerCase().includes(q) ||
        v.author.toLowerCase().includes(q)
    );
  }, [videos, query]);

  return (
    <aside
      id="queue"
      aria-label="Up Next Queue"
      className="glass-panel rounded-2xl flex flex-col h-full max-h-[680px]"
    >
      {/* Header */}
      <div className="p-4 border-b border-white/10 shrink-0">
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <h2 className="font-display text-base font-semibold text-zinc-100 truncate">
              {source ? source.title : 'Playback Queue'}
            </h2>
            <p className="text-xs text-zinc-400 mt-0.5 tabular-nums">
              {source ? (
                <>
                  <span>{source.type === 'channel' ? 'Channel' : 'Playlist'}</span>
                  <span aria-hidden="true"> · </span>
                  <span>{videos.length} videos</span>
                </>
              ) : (
                'No source selected'
              )}
            </p>
          </div>

          <div className="flex items-center gap-1 shrink-0">
            <button
              type="button"
              onClick={onShufflePick}
              disabled={videos.length < 2}
              title="Play random video from queue"
              aria-label="Play random video from queue"
              className="p-2 rounded-lg text-zinc-400 hover:text-zinc-100 hover:bg-white/10 disabled:opacity-35 transition-colors cursor-pointer"
            >
              <Shuffle className="w-4 h-4" />
            </button>
            <button
              type="button"
              onClick={onRefreshSource}
              disabled={!source || loading}
              title="Refresh source feed"
              aria-label="Refresh source feed"
              className="p-2 rounded-lg text-zinc-400 hover:text-zinc-100 hover:bg-white/10 disabled:opacity-35 transition-colors cursor-pointer"
            >
              <RefreshCw className={`w-4 h-4 ${loading ? 'animate-spin' : ''}`} />
            </button>
            <button
              type="button"
              onClick={onHideSideMenu}
              title="Hide side menu"
              aria-label="Hide side menu"
              className="p-2 rounded-lg text-zinc-400 hover:text-amber-300 hover:bg-white/10 transition-colors cursor-pointer"
            >
              <PanelRightClose className="w-4 h-4" />
            </button>
          </div>
        </div>

        {/* Search Queue */}
        <div className="relative mt-3">
          <Search className="w-3.5 h-3.5 text-zinc-400 absolute left-3 top-1/2 -translate-y-1/2 pointer-events-none" />
          <input
            type="text"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Filter queue..."
            aria-label="Filter queue videos"
            className="w-full glass-input focus:border-amber-400/70 focus:outline-none rounded-xl pl-8 pr-8 py-2 text-xs text-zinc-100 placeholder:text-zinc-500 transition-colors"
          />
          {query && (
            <button
              type="button"
              onClick={() => setQuery('')}
              aria-label="Clear queue filter"
              className="absolute right-2 top-1/2 -translate-y-1/2 p-1 text-zinc-400 hover:text-zinc-200 cursor-pointer"
            >
              <X className="w-3.5 h-3.5" />
            </button>
          )}
        </div>
      </div>

      {/* Queue Body */}
      <div className="flex-1 overflow-y-auto cinema-scroll p-2.5 space-y-1.5">
        {loading && videos.length === 0 ? (
          <div className="space-y-2 p-2">
            {Array.from({ length: 6 }).map((_, i) => (
              <div key={i} className="flex items-center gap-3 p-2 rounded-xl bg-white/5 animate-pulse">
                <div className="w-24 aspect-video rounded-lg bg-white/10 shrink-0" />
                <div className="flex-1 space-y-2">
                  <div className="h-3 bg-white/10 rounded w-5/6" />
                  <div className="h-2.5 bg-white/10 rounded w-1/2" />
                </div>
              </div>
            ))}
          </div>
        ) : error && videos.length === 0 ? (
          <div className="p-6 text-center">
            <p className="text-sm text-zinc-200 mb-2">Could not load feed items</p>
            <p className="text-xs text-zinc-400 mb-4">{error}</p>
            <button
              type="button"
              onClick={onRefreshSource}
              className="px-3.5 py-2 text-xs font-semibold bg-white/10 hover:bg-white/15 border border-white/15 text-zinc-100 rounded-xl transition-colors cursor-pointer"
            >
              Try Again
            </button>
          </div>
        ) : filteredVideos.length === 0 ? (
          <div className="p-6 text-center">
            <p className="text-sm text-zinc-400">
              {query ? 'No videos match your filter.' : 'No videos found in this feed yet.'}
            </p>
          </div>
        ) : (
          filteredVideos.map((video, idx) => {
            const isActive = video.videoId === activeVideoId;
            return (
              <button
                key={video.videoId}
                type="button"
                onClick={() => onSelectVideo(video)}
                className={`w-full text-left flex items-center gap-3 p-2 rounded-xl transition-all cursor-pointer group focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-400 ${
                  isActive
                    ? 'bg-amber-400/15 border border-amber-400/45 shadow-sm shadow-amber-500/10'
                    : 'border border-transparent hover:bg-white/[0.06] hover:border-white/10'
                }`}
              >
                <span
                  className={`font-mono text-[11px] tabular-nums w-5 text-right shrink-0 ${
                    isActive ? 'text-amber-300 font-semibold' : 'text-zinc-500'
                  }`}
                >
                  {String(idx + 1).padStart(2, '0')}
                </span>

                <div className="w-24 aspect-video rounded-lg overflow-hidden shrink-0 bg-zinc-900 border border-white/10">
                  <ThumbnailImage
                    src={video.thumbnail}
                    alt={video.title}
                    className="w-full h-full"
                    fallbackLabel={video.title}
                  />
                </div>

                <div className="min-w-0 flex-1">
                  <p
                    className={`text-xs font-medium leading-snug line-clamp-2 ${
                      isActive ? 'text-amber-100' : 'text-zinc-200 group-hover:text-white'
                    }`}
                  >
                    {video.title}
                  </p>
                  <p className="text-[11px] text-zinc-400 mt-1 truncate">
                    {video.author}
                    {video.published ? ` · ${formatRelativeDate(video.published)}` : ''}
                  </p>
                </div>
              </button>
            );
          })
        )}
      </div>
    </aside>
  );
}
