import { useEffect, useMemo, useState } from 'react';
import { X } from 'lucide-react';
import { ParsedYouTubeInput } from '../types/youtube';
import { parseYouTubeInput } from '../utils/youtube';

interface AddSourceModalProps {
  open: boolean;
  onClose: () => void;
  onAdd: (payload: {
    parsed: ParsedYouTubeInput;
    customTitle: string;
    category: string;
  }) => Promise<{ ok: boolean; error?: string }>;
}

const QUICK_EXAMPLES = [
  {
    label: 'Fireship',
    url: 'https://www.youtube.com/channel/UCsBjURrPoezykLs9EqgamOA',
    category: 'Engineering',
  },
  {
    label: 'Veritasium',
    url: 'https://www.youtube.com/channel/UCHnyfMqiRRG1u-2MsSQLbXA',
    category: 'Science',
  },
  {
    label: 'Computerphile',
    url: 'https://www.youtube.com/channel/UC9-y-6csu5WGm29I7JiwpnA',
    category: 'Computer Science',
  },
];

export function AddSourceModal({ open, onClose, onAdd }: AddSourceModalProps) {
  const [urlInput, setUrlInput] = useState('');
  const [titleInput, setTitleInput] = useState('');
  const [categoryInput, setCategoryInput] = useState('Curated');
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  const liveParse = useMemo(() => {
    if (!urlInput.trim()) return null;
    return parseYouTubeInput(urlInput);
  }, [urlInput]);

  if (!open) return null;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setSubmitError(null);

    const check = parseYouTubeInput(urlInput);
    if (!check.parsed) {
      setSubmitError(check.error || 'Enter a valid YouTube channel or playlist URL.');
      return;
    }

    setSubmitting(true);
    const result = await onAdd({
      parsed: check.parsed,
      customTitle: titleInput.trim(),
      category: categoryInput.trim() || 'Curated',
    });
    setSubmitting(false);

    if (result.ok) {
      setUrlInput('');
      setTitleInput('');
      setCategoryInput('Curated');
      onClose();
    } else {
      setSubmitError(result.error || 'Could not load this YouTube source.');
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/75 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-labelledby="add-source-title"
      onClick={onClose}
    >
      <div
        className="w-full max-w-lg bg-[#111114] border border-zinc-800 rounded-xl p-6 shadow-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-start justify-between gap-4 mb-5">
          <div>
            <h2 id="add-source-title" className="font-display text-xl font-bold text-zinc-100">
              Add YouTube Source
            </h2>
            <p className="text-xs text-zinc-400 mt-1">
              Paste a playlist URL, channel URL, @handle, or ID (PL… / UC…).
            </p>
          </div>

          <button
            type="button"
            onClick={onClose}
            aria-label="Close modal"
            className="p-2 rounded-lg text-zinc-400 hover:text-zinc-100 hover:bg-zinc-800 transition-colors cursor-pointer"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label htmlFor="yt-source-url" className="block text-xs font-medium text-zinc-300 mb-1.5">
              YouTube Channel or Playlist URL
            </label>
            <input
              id="yt-source-url"
              type="text"
              autoFocus
              value={urlInput}
              onChange={(e) => {
                setUrlInput(e.target.value);
                setSubmitError(null);
              }}
              placeholder="https://www.youtube.com/playlist?list=PL... or @handle"
              className="w-full bg-[#09090B] border border-zinc-800 focus:border-amber-500/80 focus:outline-none rounded-lg px-3.5 py-2.5 text-sm text-zinc-100 placeholder:text-zinc-600 transition-colors"
            />

            {liveParse?.parsed && (
              <p className="mt-1.5 text-xs text-emerald-400">
                Detected {liveParse.parsed.type === 'playlist' ? 'Playlist' : 'Channel'}
                {' · '}
                <span className="font-mono">
                  {liveParse.parsed.playlistId || liveParse.parsed.channelId || liveParse.parsed.handleUrl}
                </span>
              </p>
            )}
            {liveParse?.error && urlInput.trim().length > 4 && (
              <p className="mt-1.5 text-xs text-amber-400">{liveParse.error}</p>
            )}
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            <div>
              <label htmlFor="yt-source-title" className="block text-xs font-medium text-zinc-300 mb-1.5">
                Display Title (optional)
              </label>
              <input
                id="yt-source-title"
                type="text"
                value={titleInput}
                onChange={(e) => setTitleInput(e.target.value)}
                placeholder="Auto-detected from feed"
                className="w-full bg-[#09090B] border border-zinc-800 focus:border-amber-500/80 focus:outline-none rounded-lg px-3.5 py-2 text-sm text-zinc-100 placeholder:text-zinc-600 transition-colors"
              />
            </div>

            <div>
              <label htmlFor="yt-source-category" className="block text-xs font-medium text-zinc-300 mb-1.5">
                Category
              </label>
              <input
                id="yt-source-category"
                type="text"
                value={categoryInput}
                onChange={(e) => setCategoryInput(e.target.value)}
                placeholder="Science, Music, Design..."
                className="w-full bg-[#09090B] border border-zinc-800 focus:border-amber-500/80 focus:outline-none rounded-lg px-3.5 py-2 text-sm text-zinc-100 placeholder:text-zinc-600 transition-colors"
              />
            </div>
          </div>

          {/* Quick Add Presets */}
          <div>
            <p className="text-xs text-zinc-500 mb-2">Quick fill examples:</p>
            <div className="flex flex-wrap gap-2">
              {QUICK_EXAMPLES.map((ex) => (
                <button
                  key={ex.label}
                  type="button"
                  onClick={() => {
                    setUrlInput(ex.url);
                    setTitleInput(ex.label);
                    setCategoryInput(ex.category);
                    setSubmitError(null);
                  }}
                  className="px-2.5 py-1 text-xs font-medium bg-zinc-900 border border-zinc-800 hover:border-zinc-700 text-zinc-300 rounded-md transition-colors cursor-pointer"
                >
                  {ex.label}
                </button>
              ))}
            </div>
          </div>

          {submitError && (
            <div className="p-3 rounded-lg bg-red-500/10 border border-red-500/30 text-xs text-red-300">
              {submitError}
            </div>
          )}

          <div className="flex items-center justify-end gap-3 pt-2">
            <button
              type="button"
              onClick={onClose}
              className="px-4 py-2 text-xs font-medium text-zinc-300 hover:text-zinc-100 rounded-lg transition-colors cursor-pointer"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={submitting || !urlInput.trim()}
              className="px-4 py-2 text-xs font-semibold bg-amber-500 hover:bg-amber-400 disabled:opacity-45 text-zinc-950 rounded-lg transition-colors cursor-pointer"
            >
              {submitting ? 'Loading Feed...' : 'Add Source'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
