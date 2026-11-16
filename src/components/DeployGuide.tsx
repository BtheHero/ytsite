import { useState } from 'react';
import { Check, Copy, Download, ExternalLink } from 'lucide-react';
import { VideoSource } from '../types/youtube';
import { serializeSourcesJson } from '../utils/youtube';

interface DeployGuideProps {
  sources: VideoSource[];
  onDownloadSourcesJson: () => void;
}

export function DeployGuide({ sources, onDownloadSourcesJson }: DeployGuideProps) {
  const [copiedJson, setCopiedJson] = useState(false);

  const jsonPreview = serializeSourcesJson(sources);

  const handleCopyJson = async () => {
    try {
      await navigator.clipboard.writeText(jsonPreview);
      setCopiedJson(true);
      window.setTimeout(() => setCopiedJson(false), 1800);
    } catch {
      // Clipboard blocked
    }
  };

  return (
    <section id="deploy" className="pt-10 border-t border-white/10">
      <div className="mb-6">
        <h2 className="font-display text-2xl font-bold tracking-tight text-zinc-100">
          Host on Cloudflare Pages or GitHub Pages
        </h2>
        <p className="text-sm text-zinc-400 mt-1 max-w-2xl">
          This repository includes ready-to-use deployment files for both static GitHub Pages and Cloudflare Pages with edge feed resolution.
        </p>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6 mb-6">
        {/* Cloudflare Pages Card */}
        <div className="glass-panel rounded-2xl p-6 flex flex-col justify-between">
          <div>
            <div className="flex items-center justify-between gap-2 text-xs text-zinc-400 mb-2">
              <span className="text-amber-300 font-medium">Recommended</span>
              <span>Static + Edge Function</span>
            </div>
            <h3 className="font-display text-lg font-semibold text-zinc-100 mb-3">
              Cloudflare Pages
            </h3>
            <ol className="space-y-2.5 text-sm text-zinc-300 list-decimal list-inside leading-relaxed">
              <li>Push this repository to GitHub.</li>
              <li>
                In Cloudflare Dashboard, go to <span className="text-zinc-100 font-medium">Workers &amp; Pages → Create → Pages → Connect to Git</span>.
              </li>
              <li>
                Set build command to <code className="font-mono text-xs bg-white/5 border border-white/10 px-1.5 py-0.5 rounded text-amber-300">npm run build</code> and output directory to <code className="font-mono text-xs bg-white/5 border border-white/10 px-1.5 py-0.5 rounded text-amber-300">dist</code>.
              </li>
              <li>
                Cloudflare automatically deploys <code className="font-mono text-xs text-zinc-200">functions/api/feed.ts</code> at <code className="font-mono text-xs text-zinc-200">/api/feed</code> so <code className="font-mono text-xs text-zinc-200">@handle</code> URLs and YouTube RSS feeds cache at the edge.
              </li>
            </ol>
          </div>

          <div className="mt-5 pt-4 border-t border-white/10 flex items-center justify-between text-xs text-zinc-400">
            <span className="font-mono">wrangler.toml · public/_redirects</span>
            <a
              href="https://developers.cloudflare.com/pages/framework-guides/deploy-a-vite3-project/"
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-1 text-zinc-200 hover:text-amber-300 transition-colors"
            >
              Cloudflare Docs <ExternalLink className="w-3 h-3" />
            </a>
          </div>
        </div>

        {/* GitHub Pages Card */}
        <div className="glass-panel rounded-2xl p-6 flex flex-col justify-between">
          <div>
            <div className="flex items-center justify-between gap-2 text-xs text-zinc-400 mb-2">
              <span className="font-medium text-zinc-300">Pure Static</span>
              <span>GitHub Actions</span>
            </div>
            <h3 className="font-display text-lg font-semibold text-zinc-100 mb-3">
              GitHub Pages
            </h3>
            <ol className="space-y-2.5 text-sm text-zinc-300 list-decimal list-inside leading-relaxed">
              <li>Push this repository to a GitHub repo on the <code className="font-mono text-xs text-zinc-200">main</code> branch.</li>
              <li>
                Open <span className="text-zinc-100 font-medium">Settings → Pages → Build and deployment</span> and set Source to <span className="text-zinc-100 font-medium">GitHub Actions</span>.
              </li>
              <li>
                The included <code className="font-mono text-xs text-zinc-200">.github/workflows/deploy-pages.yml</code> builds <code className="font-mono text-xs text-zinc-200">dist/</code> with relative paths (<code className="font-mono text-xs text-zinc-200">base: &apos;./&apos;</code>) and publishes automatically.
              </li>
              <li>
                Playlist and channel <code className="font-mono text-xs text-zinc-200">UC…</code> / <code className="font-mono text-xs text-zinc-200">PL…</code> sources resolve directly in the browser via CORS feed fallback and the YouTube IFrame API.
              </li>
            </ol>
          </div>

          <div className="mt-5 pt-4 border-t border-white/10 flex items-center justify-between text-xs text-zinc-400">
            <span className="font-mono">.github/workflows/deploy-pages.yml</span>
            <a
              href="https://docs.github.com/en/pages/getting-started-with-github-pages/configuring-a-publishing-source-for-your-github-pages-site"
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-1 text-zinc-200 hover:text-amber-300 transition-colors"
            >
              GitHub Docs <ExternalLink className="w-3 h-3" />
            </a>
          </div>
        </div>
      </div>

      {/* Live sources.json Export */}
      <div className="glass-panel rounded-2xl p-6">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 mb-4">
          <div>
            <h3 className="font-display text-base font-semibold text-zinc-100">
              Publish Your Curated Library (<span className="font-mono text-sm">public/sources.json</span>)
            </h3>
            <p className="text-xs text-zinc-400 mt-0.5">
              Download or copy your current channels &amp; playlists and replace <code className="font-mono text-zinc-300">public/sources.json</code> before pushing so visitors see your exact lineup.
            </p>
          </div>

          <div className="flex items-center gap-2 shrink-0">
            <button
              type="button"
              onClick={handleCopyJson}
              className="inline-flex items-center gap-1.5 px-3 py-2 text-xs font-medium text-zinc-200 bg-white/5 border border-white/12 hover:bg-white/10 rounded-xl transition-colors whitespace-nowrap shrink-0 cursor-pointer"
            >
              {copiedJson ? (
                <>
                  <Check className="w-3.5 h-3.5 text-amber-300" />
                  Copied
                </>
              ) : (
                <>
                  <Copy className="w-3.5 h-3.5" />
                  Copy JSON
                </>
              )}
            </button>

            <button
              type="button"
              onClick={onDownloadSourcesJson}
              className="inline-flex items-center gap-1.5 px-3.5 py-2 text-xs font-semibold text-zinc-950 bg-gradient-to-br from-amber-300 to-amber-500 hover:from-amber-200 hover:to-amber-400 rounded-xl shadow-md shadow-amber-500/20 transition-all whitespace-nowrap shrink-0 cursor-pointer"
            >
              <Download className="w-3.5 h-3.5" />
              Download sources.json
            </button>
          </div>
        </div>

        <pre className="glass-input rounded-xl p-4 text-xs font-mono text-zinc-300 overflow-x-auto max-h-64 cinema-scroll">
          {jsonPreview}
        </pre>
      </div>
    </section>
  );
}
