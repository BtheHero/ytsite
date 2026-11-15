import { useMemo, useState } from 'react';
import { RefreshCw, Search, Shuffle, X } from 'lucide-react';
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
      className="bg-[#111114] border border-zinc-800/80 rounded-xl flex flex-col h-full max-h-[680px]"
    >
      {/* Header */}
      <div className="p-4 border-b border-zinc-800/80 shrink-0">
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
              className="p-2 rounded-lg text-zinc-400 hover:text-zinc-100 hover:bg-zinc-800/80 disabled:opacity-35 transition-colors cursor-pointer"
            >
              <Shuffle className="w-4 h-4" />
            </button>
            <button
              type="button"
              onClick={onRefreshSource}
              disabled={!source || loading}
              title="Refresh source feed"
              aria-label="Refresh source feed"
              className="p-2 rounded-lg text-zinc-400 hover:text-zinc-100 hover:bg-zinc-800/80 disabled:opacity-35 transition-colors cursor-pointer"
            >
              <RefreshCw className={`w-4 h-4 ${loading ? 'animate-spin' : ''}`} />
            </button>
          </div>
        </div>

        {/* Search Queue */}
        <div className="relative mt-3">
          <Search className="w-3.5 h-3.5 text-zinc-500 absolute left-3 top-1/2 -translate-y-1/2 pointer-events-none" />
          <input
            type="text"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Filter queue..."
            aria-label="Filter queue videos"
            className="w-full bg-[#09090B] border border-zinc-800 focus:border-amber-500/70 focus:outline-none rounded-lg pl-8 pr-8 py-2 text-xs text-zinc-100 placeholder:text-zinc-500 transition-colors"
          />
          {query && (
            <button
              type="button"
              onClick={() => setQuery('')}
              aria-label="Clear queue filter"
              className="absolute right-2 top-1/2 -translate-y-1/2 p-1 text-zinc-500 hover:text-zinc-300 cursor-pointer"
            >
              <X className="w-3.5 h-3.5" />
            </button>
          )}
        </div>
      </div>

      {/* Queue Body */}
      <div className="flex-1 overflow-y-auto cinema-scroll p-2 space-y-1">
        {loading && videos.length === 0 ? (
          <div className="space-y-2 p-2">
            {Array.from({ length: 6 }).map((_, i) => (
              <div key={i} className="flex items-center gap-3 p-2 rounded-lg bg-zinc-900/50 animate-pulse">
                <div className="w-24 aspect-video rounded bg-zinc-800 shrink-0" />
                <div className="flex-1 space-y-2">
                  <div className="h-3 bg-zinc-800 rounded w-5/6" />
                  <div className="h-2.5 bg-zinc-800 rounded w-1/2" />
                </div>
              </div>
            ))}
          </div>
        ) : error && videos.length === 0 ? (
          <div className="p-6 text-center">
            <p className="text-sm text-zinc-300 mb-2">Could not load feed items</p>
            <p className="text-xs text-zinc-500 mb-4">{error}</p>
            <button
              type="button"
              onClick={onRefreshSource}
              className="px-3.5 py-2 text-xs font-semibold bg-zinc-800 hover:bg-zinc-700 text-zinc-100 rounded-lg transition-colors cursor-pointer"
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
                className={`w-full text-left flex items-center gap-3 p-2 rounded-lg transition-colors cursor-pointer group focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-500 ${
                  isActive
                    ? 'bg-amber-500/12 border border-amber-500/30'
                    : 'border border-transparent hover:bg-zinc-800/60'
                }`}
              >
                <span
                  className={`font-mono text-[11px] tabular-nums w-5 text-right shrink-0 ${
                    isActive ? 'text-amber-400 font-semibold' : 'text-zinc-500'
                  }`}
                >
                  {String(idx + 1).padStart(2, '0')}
                </span>

                <div className="w-24 aspect-video rounded-md overflow-hidden shrink-0 bg-zinc-900">
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
                  <p className="text-[11px] text-zinc-500 mt-1 truncate">
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
