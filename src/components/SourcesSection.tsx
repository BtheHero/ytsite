import { useMemo, useRef, useState } from 'react';
import { Download, Play, Plus, RefreshCw, Trash2, Upload } from 'lucide-react';
import { SourceType, VideoSource } from '../types/youtube';
import { ThumbnailImage } from './ThumbnailImage';

interface SourcesSectionProps {
  sources: VideoSource[];
  activeSourceId: string | null;
  loadingSourceIds: Record<string, boolean>;
  onSelectSource: (sourceId: string) => void;
  onRefreshSource: (sourceId: string) => void;
  onRemoveSource: (sourceId: string) => void;
  onOpenAddModal: () => void;
  onExportJson: () => void;
  onImportJson: (file: File) => void;
}

type FilterTab = 'all' | SourceType;

export function SourcesSection({
  sources,
  activeSourceId,
  loadingSourceIds,
  onSelectSource,
  onRefreshSource,
  onRemoveSource,
  onOpenAddModal,
  onExportJson,
  onImportJson,
}: SourcesSectionProps) {
  const [filter, setFilter] = useState<FilterTab>('all');
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  const filteredSources = useMemo(() => {
    return sources.filter((source) => filter === 'all' || source.type === filter);
  }, [sources, filter]);

  const channelCount = sources.filter((s) => s.type === 'channel').length;
  const playlistCount = sources.filter((s) => s.type === 'playlist').length;

  return (
    <section id="sources" className="pt-10 border-t border-white/10">
      <div className="flex flex-col md:flex-row md:items-end justify-between gap-4 mb-6">
        <div>
          <h2 className="font-display text-2xl font-bold tracking-tight text-zinc-100">
            Channels &amp; Playlists
          </h2>
          <p className="text-sm text-zinc-400 mt-1 tabular-nums">
            {sources.length} curated sources · {channelCount} channels · {playlistCount} playlists
          </p>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <input
            ref={fileInputRef}
            type="file"
            accept="application/json,.json"
            className="hidden"
            onChange={(e) => {
              const file = e.target.files?.[0];
              if (file) {
                onImportJson(file);
                e.target.value = '';
              }
            }}
          />

          <button
            type="button"
            onClick={() => fileInputRef.current?.click()}
            className="inline-flex items-center gap-1.5 px-3 py-2 text-xs font-medium text-zinc-200 bg-white/5 border border-white/12 hover:bg-white/10 hover:text-white rounded-xl transition-colors whitespace-nowrap shrink-0 cursor-pointer"
          >
            <Upload className="w-3.5 h-3.5" />
            Import JSON
          </button>

          <button
            type="button"
            onClick={onExportJson}
            className="inline-flex items-center gap-1.5 px-3 py-2 text-xs font-medium text-zinc-200 bg-white/5 border border-white/12 hover:bg-white/10 hover:text-white rounded-xl transition-colors whitespace-nowrap shrink-0 cursor-pointer"
          >
            <Download className="w-3.5 h-3.5" />
            Export sources.json
          </button>

          <button
            type="button"
            onClick={onOpenAddModal}
            className="inline-flex items-center gap-1.5 px-3.5 py-2 text-xs font-semibold text-zinc-950 bg-gradient-to-br from-amber-300 to-amber-500 hover:from-amber-200 hover:to-amber-400 rounded-xl shadow-md shadow-amber-500/20 transition-all whitespace-nowrap shrink-0 cursor-pointer"
          >
            <Plus className="w-3.5 h-3.5" />
            Add Source
          </button>
        </div>
      </div>

      {/* Filter Tabs */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 mb-6">
        <div
          role="tablist"
          aria-label="Filter sources by type"
          className="inline-flex items-center gap-1 p-1 glass-panel rounded-xl self-start"
        >
          {(
            [
              { id: 'all', label: 'All Sources' },
              { id: 'channel', label: 'Channels' },
              { id: 'playlist', label: 'Playlists' },
            ] as const
          ).map((tab) => (
            <button
              key={tab.id}
              type="button"
              role="tab"
              aria-selected={filter === tab.id}
              onClick={() => setFilter(tab.id)}
              className={`px-3 py-1.5 text-xs font-medium rounded-lg transition-colors whitespace-nowrap shrink-0 cursor-pointer ${
                filter === tab.id
                  ? 'bg-white/15 text-white border border-white/15'
                  : 'text-zinc-400 hover:text-zinc-100'
              }`}
            >
              {tab.label}
            </button>
          ))}
        </div>
      </div>

      {/* Sources Grid */}
      {filteredSources.length === 0 ? (
        <div className="glass-panel rounded-2xl p-10 text-center">
          <p className="font-display text-lg font-semibold text-zinc-200 mb-1">
            No matching sources
          </p>
          <p className="text-sm text-zinc-400 mb-5">
            {sources.length === 0
              ? 'Add your first YouTube channel or playlist to start building your library.'
              : 'Try switching the source filter tab.'}
          </p>
          <button
            type="button"
            onClick={onOpenAddModal}
            className="px-4 py-2 text-xs font-semibold bg-gradient-to-br from-amber-300 to-amber-500 hover:from-amber-200 hover:to-amber-400 text-zinc-950 rounded-xl transition-all cursor-pointer"
          >
            Add YouTube Source
          </button>
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          {filteredSources.map((source) => {
            const isActive = source.id === activeSourceId;
            const isLoading = Boolean(loadingSourceIds[source.id]);
            const previews = (source.videos || []).slice(0, 3);
            const videoCount = source.videos?.length ?? 0;

            return (
              <article
                key={source.id}
                className={`glass-panel rounded-2xl p-5 flex flex-col justify-between transition-all ${
                  isActive
                    ? '!border-amber-400/60 shadow-lg shadow-amber-500/10'
                    : 'hover:!border-white/25'
                }`}
              >
                <div>
                  <div className="flex items-center justify-between gap-2 text-xs text-zinc-400 mb-1.5">
                    <span>
                      {source.type === 'channel' ? 'Channel' : 'Playlist'}
                      <span aria-hidden="true"> · </span>
                      {source.category}
                    </span>
                    <span className="tabular-nums text-zinc-400">
                      {videoCount > 0 ? `${videoCount} videos` : isLoading ? 'Loading...' : 'Ready'}
                    </span>
                  </div>

                  <h3 className="font-display text-lg font-semibold text-zinc-100 truncate">
                    {source.title}
                  </h3>

                  {/* 3-Frame Preview Strip */}
                  <div className="grid grid-cols-3 gap-2 mt-4">
                    {previews.length > 0
                      ? previews.map((vid) => (
                          <button
                            key={vid.videoId}
                            type="button"
                            onClick={() => onSelectSource(source.id)}
                            aria-label={`Play ${source.title}`}
                            className="aspect-video rounded-lg overflow-hidden bg-zinc-900 border border-white/10 cursor-pointer group"
                          >
                            <ThumbnailImage
                              src={vid.thumbnail}
                              alt={vid.title}
                              className="w-full h-full group-hover:scale-105 transition-transform duration-200"
                              fallbackLabel={vid.title}
                            />
                          </button>
                        ))
                      : Array.from({ length: 3 }).map((_, idx) => (
                          <div
                            key={idx}
                            className="aspect-video rounded-lg bg-white/5 border border-white/10"
                          />
                        ))}
                  </div>
                </div>

                <div className="flex items-center justify-between gap-2 mt-4 pt-3 border-t border-white/10">
                  <button
                    type="button"
                    onClick={() => onSelectSource(source.id)}
                    className={`inline-flex items-center gap-1.5 px-3.5 py-2 text-xs font-semibold rounded-xl transition-all whitespace-nowrap shrink-0 cursor-pointer ${
                      isActive
                        ? 'bg-gradient-to-br from-amber-300 to-amber-500 text-zinc-950 shadow-md shadow-amber-500/20'
                        : 'bg-white/10 hover:bg-white/15 border border-white/10 text-zinc-100'
                    }`}
                  >
                    <Play className="w-3.5 h-3.5 fill-current" />
                    {isActive ? 'Playing' : 'Load Source'}
                  </button>

                  <div className="flex items-center gap-1">
                    <button
                      type="button"
                      onClick={() => onRefreshSource(source.id)}
                      disabled={isLoading}
                      title="Refresh feed"
                      aria-label={`Refresh ${source.title}`}
                      className="p-2 rounded-xl text-zinc-400 hover:text-zinc-100 hover:bg-white/10 transition-colors cursor-pointer"
                    >
                      <RefreshCw className={`w-3.5 h-3.5 ${isLoading ? 'animate-spin' : ''}`} />
                    </button>
                    <button
                      type="button"
                      onClick={() => onRemoveSource(source.id)}
                      title="Remove source"
                      aria-label={`Remove ${source.title}`}
                      className="p-2 rounded-xl text-zinc-400 hover:text-red-400 hover:bg-white/10 transition-colors cursor-pointer"
                    >
                      <Trash2 className="w-3.5 h-3.5" />
                    </button>
                  </div>
                </div>
              </article>
            );
          })}
        </div>
      )}
    </section>
  );
}
