import { useEffect, useRef, useState, useCallback } from 'react';
import {
  Play,
  Pause,
  SkipBack,
  SkipForward,
  Volume2,
  VolumeX,
  Maximize2,
  Minimize2,
  Shuffle,
  Repeat,
  ExternalLink,
  Copy,
  Check,
  ChevronDown,
  ChevronUp,
  RotateCcw,
  RotateCw,
  Subtitles,
  ShieldCheck,
  Aperture,
  Expand,
  Shrink,
} from 'lucide-react';
import { VideoItem, VideoSource } from '../types/youtube';
import { formatRelativeDate, formatTimecode, formatViews } from '../utils/youtube';

interface YTPlayerInstance {
  destroy: () => void;
  loadVideoById: (video: string | { videoId: string; startSeconds?: number }) => void;
  cuePlaylist: (options: { listType: string; list: string }) => void;
  loadPlaylist: (options: { listType: string; list: string }) => void;
  playVideo: () => void;
  pauseVideo: () => void;
  seekTo: (seconds: number, allowSeekAhead: boolean) => void;
  getCurrentTime: () => number;
  getDuration: () => number;
  getPlayerState: () => number;
  getVolume: () => number;
  setVolume: (vol: number) => void;
  mute: () => void;
  unMute: () => void;
  isMuted: () => boolean;
  setPlaybackRate: (rate: number) => void;
  getPlaylist: () => string[] | null;
  getVideoData: () => { video_id?: string; title?: string; author?: string } | undefined;
  loadModule?: (moduleName: string) => void;
  unloadModule?: (moduleName: string) => void;
}

declare global {
  interface Window {
    YT?: {
      Player: new (
        element: HTMLElement | string,
        config: {
          host?: string;
          videoId?: string;
          playerVars?: Record<string, string | number>;
          events?: {
            onReady?: (event: { target: YTPlayerInstance }) => void;
            onStateChange?: (event: { data: number; target: YTPlayerInstance }) => void;
            onError?: (event: { data: number }) => void;
          };
        }
      ) => YTPlayerInstance;
      PlayerState?: {
        ENDED: number;
        PLAYING: number;
        PAUSED: number;
        BUFFERING: number;
        CUED: number;
      };
    };
    onYouTubeIframeAPIReady?: () => void;
  }
}

let ytApiPromise: Promise<void> | null = null;

function loadYouTubeIframeAPI(): Promise<void> {
  if (window.YT?.Player) {
    return Promise.resolve();
  }
  if (ytApiPromise) return ytApiPromise;

  ytApiPromise = new Promise<void>((resolve) => {
    const prevCallback = window.onYouTubeIframeAPIReady;
    window.onYouTubeIframeAPIReady = () => {
      if (prevCallback) prevCallback();
      resolve();
    };

    if (!document.querySelector('script[src="https://www.youtube.com/iframe_api"]')) {
      const script = document.createElement('script');
      script.src = 'https://www.youtube.com/iframe_api';
      script.async = true;
      document.head.appendChild(script);
    }
  });

  return ytApiPromise;
}

interface YouTubeStageProps {
  source: VideoSource | null;
  activeVideo: VideoItem | null;
  hasNext: boolean;
  hasPrevious: boolean;
  shuffle: boolean;
  loopQueue: boolean;
  theaterMode: boolean;
  onNext: () => void;
  onPrevious: () => void;
  onToggleShuffle: () => void;
  onToggleLoop: () => void;
  onToggleTheater: () => void;
  onDiscoverPlaylistIds?: (ids: string[]) => void;
}

type AspectMode = '16:9' | '21:9' | '4:3';

const PLAYBACK_RATES = [0.75, 1, 1.25, 1.5, 2];
const ASPECT_CYCLE: AspectMode[] = ['16:9', '21:9', '4:3'];

export function YouTubeStage({
  source,
  activeVideo,
  hasNext,
  hasPrevious,
  shuffle,
  loopQueue,
  theaterMode,
  onNext,
  onPrevious,
  onToggleShuffle,
  onToggleLoop,
  onToggleTheater,
  onDiscoverPlaylistIds,
}: YouTubeStageProps) {
  const stageWrapperRef = useRef<HTMLDivElement | null>(null);
  const containerRef = useRef<HTMLDivElement | null>(null);
  const playerRef = useRef<YTPlayerInstance | null>(null);
  const activeVideoIdRef = useRef<string | undefined>(activeVideo?.videoId);
  const resumeTimeRef = useRef<number>(0);
  const onNextRef = useRef(onNext);
  const onPreviousRef = useRef(onPrevious);
  const onDiscoverRef = useRef(onDiscoverPlaylistIds);

  activeVideoIdRef.current = activeVideo?.videoId;
  onNextRef.current = onNext;
  onPreviousRef.current = onPrevious;
  onDiscoverRef.current = onDiscoverPlaylistIds;

  const [playerReady, setPlayerReady] = useState(false);
  const [isPlaying, setIsPlaying] = useState(false);
  const [currentTime, setCurrentTime] = useState(0);
  const [duration, setDuration] = useState(0);
  const [volume, setVolume] = useState(85);
  const [isMuted, setIsMuted] = useState(false);
  const [playbackRate, setPlaybackRate] = useState(1);
  const [captionsEnabled, setCaptionsEnabled] = useState(false);
  const [privacyMode, setPrivacyMode] = useState(true);
  const [ambientMode, setAmbientMode] = useState(true);
  const [aspectMode, setAspectMode] = useState<AspectMode>('16:9');
  const [isFullscreen, setIsFullscreen] = useState(false);
  const [copiedLink, setCopiedLink] = useState(false);
  const [showDescription, setShowDescription] = useState(false);
  const [embedError, setEmbedError] = useState<string | null>(null);
  const [ambientImgFailed, setAmbientImgFailed] = useState(false);

  // Sync fullscreen state with browser events
  useEffect(() => {
    const onFsChange = () => {
      setIsFullscreen(document.fullscreenElement === stageWrapperRef.current);
    };
    document.addEventListener('fullscreenchange', onFsChange);
    return () => document.removeEventListener('fullscreenchange', onFsChange);
  }, []);

  // Initialize YT.Player (re-initializes only when privacyMode host switches)
  useEffect(() => {
    let disposed = false;
    setPlayerReady(false);

    loadYouTubeIframeAPI().then(() => {
      if (disposed || !containerRef.current || !window.YT?.Player) return;

      const mountEl = document.createElement('div');
      mountEl.className = 'w-full h-full';
      containerRef.current.innerHTML = '';
      containerRef.current.appendChild(mountEl);

      const initialVideoId = activeVideoIdRef.current;

      const player = new window.YT.Player(mountEl, {
        host: privacyMode ? 'https://www.youtube-nocookie.com' : 'https://www.youtube.com',
        videoId: initialVideoId,
        playerVars: {
          autoplay: 0,
          controls: 0,
          fs: 0,
          disablekb: 1,
          iv_load_policy: 3,
          cc_load_policy: captionsEnabled ? 1 : 0,
          modestbranding: 1,
          rel: 0,
          playsinline: 1,
          enablejsapi: 1,
          origin: window.location.origin,
        },
        events: {
          onReady: (event) => {
            if (disposed) return;
            playerRef.current = event.target;
            event.target.setVolume(volume);
            if (isMuted) event.target.mute();
            setPlayerReady(true);
            setEmbedError(null);
            if (activeVideoIdRef.current) {
              if (resumeTimeRef.current > 0) {
                event.target.loadVideoById({
                  videoId: activeVideoIdRef.current,
                  startSeconds: resumeTimeRef.current,
                });
                resumeTimeRef.current = 0;
              } else {
                event.target.loadVideoById(activeVideoIdRef.current);
              }
            }
          },
          onStateChange: (event) => {
            if (disposed) return;
            const state = event.data;
            if (state === 1) {
              setIsPlaying(true);
              setEmbedError(null);
              const d = event.target.getDuration();
              if (d > 0) setDuration(d);
              const listIds = event.target.getPlaylist?.();
              if (Array.isArray(listIds) && listIds.length > 0 && onDiscoverRef.current) {
                onDiscoverRef.current(listIds);
              }
            } else if (state === 2 || state === 5) {
              setIsPlaying(false);
            } else if (state === 0) {
              setIsPlaying(false);
              onNextRef.current();
            }
          },
          onError: (event) => {
            if (disposed) return;
            if (event.data === 101 || event.data === 150) {
              setEmbedError(
                'The video owner restricted embedded playback. Use "Watch on YouTube" below.'
              );
            } else if (event.data === 100) {
              setEmbedError('This video was removed or marked private.');
            } else {
              setEmbedError('Unable to stream this video in the embedded player.');
            }
          },
        },
      });

      playerRef.current = player;
    });

    return () => {
      disposed = true;
      try {
        playerRef.current?.destroy();
      } catch {
        // Ignore cleanup errors
      }
      playerRef.current = null;
    };
    // Intentionally only rebuild iframe when privacyMode host changes
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [privacyMode]);

  // Load new video when activeVideo changes
  useEffect(() => {
    setEmbedError(null);
    setCurrentTime(0);
    setDuration(0);
    setShowDescription(false);
    setAmbientImgFailed(false);

    const player = playerRef.current;
    if (!player || !playerReady) return;

    if (activeVideo?.videoId) {
      player.loadVideoById(activeVideo.videoId);
    } else if (source?.playlistId) {
      player.loadPlaylist({ listType: 'playlist', list: source.playlistId });
    }
  }, [activeVideo?.videoId, source?.playlistId, playerReady]);

  // Poll playback timecode while playing
  useEffect(() => {
    if (!isPlaying) return;
    const interval = window.setInterval(() => {
      const player = playerRef.current;
      if (!player) return;
      try {
        const t = player.getCurrentTime();
        const d = player.getDuration();
        if (Number.isFinite(t)) setCurrentTime(t);
        if (Number.isFinite(d) && d > 0) setDuration(d);
      } catch {
        // Ignore transient state
      }
    }, 250);
    return () => window.clearInterval(interval);
  }, [isPlaying]);

  const togglePlay = useCallback(() => {
    const player = playerRef.current;
    if (!player) return;
    if (isPlaying) {
      player.pauseVideo();
    } else {
      player.playVideo();
    }
  }, [isPlaying]);

  const seekBy = useCallback(
    (deltaSeconds: number) => {
      const player = playerRef.current;
      if (!player) return;
      const nextTime = Math.max(0, Math.min(duration || 0, player.getCurrentTime() + deltaSeconds));
      player.seekTo(nextTime, true);
      setCurrentTime(nextTime);
    },
    [duration]
  );

  const handleSeekBar = (e: React.ChangeEvent<HTMLInputElement>) => {
    const next = Number(e.target.value);
    setCurrentTime(next);
    playerRef.current?.seekTo(next, true);
  };

  const handleVolumeChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const next = Number(e.target.value);
    setVolume(next);
    const player = playerRef.current;
    if (!player) return;
    if (next === 0) {
      player.mute();
      setIsMuted(true);
    } else {
      if (isMuted) {
        player.unMute();
        setIsMuted(false);
      }
      player.setVolume(next);
    }
  };

  const toggleMute = useCallback(() => {
    const player = playerRef.current;
    if (!player) return;
    if (isMuted) {
      player.unMute();
      setIsMuted(false);
    } else {
      player.mute();
      setIsMuted(true);
    }
  }, [isMuted]);

  const handleRateChange = (rate: number) => {
    setPlaybackRate(rate);
    playerRef.current?.setPlaybackRate(rate);
  };

  const toggleCaptions = () => {
    const next = !captionsEnabled;
    setCaptionsEnabled(next);
    const player = playerRef.current;
    if (!player) return;
    try {
      if (next) {
        player.loadModule?.('captions');
      } else {
        player.unloadModule?.('captions');
      }
    } catch {
      // Module may not be available on videos without captions
    }
  };

  const togglePrivacyMode = () => {
    try {
      const t = playerRef.current?.getCurrentTime();
      if (typeof t === 'number' && Number.isFinite(t) && t > 1) {
        resumeTimeRef.current = Math.floor(t);
      }
    } catch {
      resumeTimeRef.current = 0;
    }
    setPrivacyMode((p) => !p);
  };

  const cycleAspect = () => {
    setAspectMode((prev) => {
      const idx = ASPECT_CYCLE.indexOf(prev);
      return ASPECT_CYCLE[(idx + 1) % ASPECT_CYCLE.length];
    });
  };

  const toggleFullscreen = useCallback(async () => {
    const wrapper = stageWrapperRef.current;
    if (!wrapper) return;
    try {
      if (document.fullscreenElement === wrapper) {
        await document.exitFullscreen();
      } else {
        await wrapper.requestFullscreen();
      }
    } catch {
      // Fullscreen denied by browser/iframe policy
    }
  }, []);

  // Keyboard shortcuts when not focused on inputs
  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement | null;
      if (
        target &&
        (target.tagName === 'INPUT' ||
          target.tagName === 'TEXTAREA' ||
          target.tagName === 'SELECT' ||
          target.isContentEditable)
      ) {
        return;
      }

      if (e.code === 'Space' || e.key.toLowerCase() === 'k') {
        e.preventDefault();
        togglePlay();
      } else if (e.key.toLowerCase() === 'j') {
        e.preventDefault();
        seekBy(-10);
      } else if (e.key.toLowerCase() === 'l') {
        e.preventDefault();
        seekBy(10);
      } else if (e.key.toLowerCase() === 'm') {
        e.preventDefault();
        toggleMute();
      } else if (e.key.toLowerCase() === 'f') {
        e.preventDefault();
        void toggleFullscreen();
      } else if (e.key.toLowerCase() === 'n') {
        e.preventDefault();
        onNextRef.current();
      } else if (e.key.toLowerCase() === 'p') {
        e.preventDefault();
        onPreviousRef.current();
      }
    };

    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [togglePlay, seekBy, toggleMute, toggleFullscreen]);

  const handleCopyVideoUrl = async () => {
    if (!activeVideo) return;
    const ytUrl = `https://www.youtube.com/watch?v=${activeVideo.videoId}`;
    try {
      await navigator.clipboard.writeText(ytUrl);
      setCopiedLink(true);
      window.setTimeout(() => setCopiedLink(false), 1800);
    } catch {
      // Clipboard blocked
    }
  };

  const progressPct = duration > 0 ? Math.min(100, (currentTime / duration) * 100) : 0;

  const aspectClass = isFullscreen
    ? 'flex-1 min-h-0'
    : aspectMode === '21:9'
      ? 'aspect-[21/9]'
      : aspectMode === '4:3'
        ? 'aspect-[4/3]'
        : 'aspect-video';

  return (
    <div className="flex flex-col">
      {/* Stage + Custom Transport Wrapper (supports Custom Fullscreen with controls preserved) */}
      <div
        ref={stageWrapperRef}
        className={`relative flex flex-col ${
          isFullscreen ? 'w-screen h-screen bg-[#09090B] p-4 gap-3' : ''
        }`}
      >
        {/* Ambient Cinema Glow */}
        {ambientMode && activeVideo?.thumbnail && !ambientImgFailed && !isFullscreen && (
          <div
            aria-hidden="true"
            className="pointer-events-none absolute -inset-4 -z-10 overflow-hidden rounded-3xl opacity-35 blur-3xl transition-opacity duration-500"
          >
            <img
              src={activeVideo.thumbnail}
              alt=""
              referrerPolicy="no-referrer"
              onError={() => setAmbientImgFailed(true)}
              className="w-full h-full object-cover scale-110"
            />
          </div>
        )}

        {/* Video Frame */}
        <div
          className={`relative w-full ${aspectClass} bg-black rounded-xl overflow-hidden border border-zinc-800/90 shadow-2xl shadow-black/50`}
        >
          <div ref={containerRef} className="w-full h-full" />

          {!activeVideo && !source?.playlistId && (
            <div className="absolute inset-0 flex flex-col items-center justify-center bg-[#0D0D10] text-center p-6">
              <p className="font-display text-xl font-semibold text-zinc-200 mb-2">
                Select a channel or playlist to start watching
              </p>
              <p className="text-sm text-zinc-400 max-w-md">
                Choose any source from your library below or add a new YouTube channel or playlist URL.
              </p>
            </div>
          )}

          {embedError && (
            <div className="absolute inset-x-0 bottom-0 p-4 bg-gradient-to-t from-black/95 via-black/80 to-transparent flex flex-wrap items-center justify-between gap-3">
              <p className="text-sm text-amber-300">{embedError}</p>
              {activeVideo && (
                <a
                  href={`https://www.youtube.com/watch?v=${activeVideo.videoId}`}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold bg-amber-500 text-zinc-950 rounded-md hover:bg-amber-400 transition-colors"
                >
                  Watch on YouTube
                  <ExternalLink className="w-3.5 h-3.5" />
                </a>
              )}
            </div>
          )}
        </div>

        {/* Custom Theater Transport Bar */}
        <div
          className={`bg-[#111114] border border-zinc-800/80 rounded-xl px-4 py-3 ${
            isFullscreen ? 'shrink-0' : 'mt-3'
          }`}
        >
          {/* Scrubber */}
          <div className="flex items-center gap-3 mb-2.5">
            <span className="font-mono text-xs text-zinc-400 tabular-nums w-12 text-right shrink-0">
              {formatTimecode(currentTime)}
            </span>
            <div className="relative flex-1 flex items-center">
              <input
                type="range"
                aria-label="Seek video timeline"
                min={0}
                max={duration || 100}
                step={0.5}
                value={currentTime}
                onChange={handleSeekBar}
                disabled={!duration}
                className="w-full h-1.5 appearance-none rounded-full bg-zinc-800 accent-amber-500 cursor-pointer disabled:cursor-default"
                style={{
                  background: `linear-gradient(to right, #f59e0b ${progressPct}%, #27272a ${progressPct}%)`,
                }}
              />
            </div>
            <span className="font-mono text-xs text-zinc-400 tabular-nums w-12 shrink-0">
              {formatTimecode(duration)}
            </span>
          </div>

          {/* Transport Controls */}
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div className="flex items-center gap-1 sm:gap-1.5">
              <button
                type="button"
                onClick={onPrevious}
                disabled={!hasPrevious}
                title="Previous video (P)"
                aria-label="Previous video"
                className="p-2 rounded-lg text-zinc-300 hover:text-zinc-100 hover:bg-zinc-800/80 disabled:opacity-35 disabled:pointer-events-none transition-colors cursor-pointer"
              >
                <SkipBack className="w-4 h-4" />
              </button>

              <button
                type="button"
                onClick={() => seekBy(-10)}
                title="Rewind 10 seconds (J)"
                aria-label="Rewind 10 seconds"
                className="p-2 rounded-lg text-zinc-300 hover:text-zinc-100 hover:bg-zinc-800/80 transition-colors cursor-pointer"
              >
                <RotateCcw className="w-4 h-4" />
              </button>

              <button
                type="button"
                onClick={togglePlay}
                title={isPlaying ? 'Pause (Space)' : 'Play (Space)'}
                aria-label={isPlaying ? 'Pause video' : 'Play video'}
                className="inline-flex items-center justify-center w-10 h-10 rounded-lg bg-amber-500 hover:bg-amber-400 text-zinc-950 transition-colors cursor-pointer"
              >
                {isPlaying ? (
                  <Pause className="w-4 h-4 fill-current" />
                ) : (
                  <Play className="w-4 h-4 fill-current ml-0.5" />
                )}
              </button>

              <button
                type="button"
                onClick={() => seekBy(10)}
                title="Forward 10 seconds (L)"
                aria-label="Forward 10 seconds"
                className="p-2 rounded-lg text-zinc-300 hover:text-zinc-100 hover:bg-zinc-800/80 transition-colors cursor-pointer"
              >
                <RotateCw className="w-4 h-4" />
              </button>

              <button
                type="button"
                onClick={onNext}
                disabled={!hasNext}
                title="Next video (N)"
                aria-label="Next video"
                className="p-2 rounded-lg text-zinc-300 hover:text-zinc-100 hover:bg-zinc-800/80 disabled:opacity-35 disabled:pointer-events-none transition-colors cursor-pointer"
              >
                <SkipForward className="w-4 h-4" />
              </button>

              <div className="hidden sm:flex items-center gap-2 ml-2 pl-3 border-l border-zinc-800">
                <button
                  type="button"
                  onClick={toggleMute}
                  title="Mute / Unmute (M)"
                  aria-label={isMuted ? 'Unmute' : 'Mute'}
                  className="p-1.5 rounded-md text-zinc-400 hover:text-zinc-100 transition-colors cursor-pointer"
                >
                  {isMuted || volume === 0 ? (
                    <VolumeX className="w-4 h-4" />
                  ) : (
                    <Volume2 className="w-4 h-4" />
                  )}
                </button>
                <input
                  type="range"
                  aria-label="Volume"
                  min={0}
                  max={100}
                  value={isMuted ? 0 : volume}
                  onChange={handleVolumeChange}
                  className="w-20 h-1 appearance-none rounded-full bg-zinc-800 accent-amber-500 cursor-pointer"
                />
              </div>
            </div>

            <div className="flex flex-wrap items-center gap-1.5">
              <div className="flex items-center bg-zinc-900 border border-zinc-800 rounded-lg p-0.5">
                {PLAYBACK_RATES.map((rate) => (
                  <button
                    key={rate}
                    type="button"
                    onClick={() => handleRateChange(rate)}
                    className={`px-2 py-1 text-xs font-mono tabular-nums rounded-md transition-colors cursor-pointer whitespace-nowrap shrink-0 ${
                      playbackRate === rate
                        ? 'bg-zinc-800 text-amber-400 font-medium'
                        : 'text-zinc-400 hover:text-zinc-200'
                    }`}
                  >
                    {rate}x
                  </button>
                ))}
              </div>

              <button
                type="button"
                onClick={toggleCaptions}
                title={captionsEnabled ? 'Disable captions' : 'Enable captions'}
                aria-pressed={captionsEnabled}
                aria-label="Toggle captions"
                className={`p-2 rounded-lg transition-colors cursor-pointer ${
                  captionsEnabled
                    ? 'bg-amber-500/15 text-amber-400'
                    : 'text-zinc-400 hover:text-zinc-100 hover:bg-zinc-800/80'
                }`}
              >
                <Subtitles className="w-4 h-4" />
              </button>

              <button
                type="button"
                onClick={togglePrivacyMode}
                title={
                  privacyMode
                    ? 'Privacy-enhanced embed active (youtube-nocookie.com)'
                    : 'Standard YouTube embed active (youtube.com)'
                }
                aria-pressed={privacyMode}
                aria-label="Toggle privacy-enhanced embed"
                className={`p-2 rounded-lg transition-colors cursor-pointer ${
                  privacyMode
                    ? 'bg-amber-500/15 text-amber-400'
                    : 'text-zinc-400 hover:text-zinc-100 hover:bg-zinc-800/80'
                }`}
              >
                <ShieldCheck className="w-4 h-4" />
              </button>

              <button
                type="button"
                onClick={() => setAmbientMode((a) => !a)}
                title={ambientMode ? 'Disable ambient glow' : 'Enable ambient glow'}
                aria-pressed={ambientMode}
                aria-label="Toggle ambient cinema glow"
                className={`hidden sm:inline-flex p-2 rounded-lg transition-colors cursor-pointer ${
                  ambientMode
                    ? 'bg-amber-500/15 text-amber-400'
                    : 'text-zinc-400 hover:text-zinc-100 hover:bg-zinc-800/80'
                }`}
              >
                <Aperture className="w-4 h-4" />
              </button>

              {!isFullscreen && (
                <button
                  type="button"
                  onClick={cycleAspect}
                  title="Cycle aspect ratio (16:9, 21:9, 4:3)"
                  aria-label={`Aspect ratio ${aspectMode}`}
                  className="hidden sm:inline-flex px-2 py-1.5 text-xs font-mono tabular-nums rounded-lg bg-zinc-900 border border-zinc-800 text-zinc-300 hover:text-zinc-100 hover:border-zinc-700 transition-colors cursor-pointer whitespace-nowrap shrink-0"
                >
                  {aspectMode}
                </button>
              )}

              <button
                type="button"
                onClick={onToggleShuffle}
                title="Shuffle queue"
                aria-pressed={shuffle}
                aria-label="Toggle shuffle"
                className={`p-2 rounded-lg transition-colors cursor-pointer ${
                  shuffle
                    ? 'bg-amber-500/15 text-amber-400'
                    : 'text-zinc-400 hover:text-zinc-100 hover:bg-zinc-800/80'
                }`}
              >
                <Shuffle className="w-4 h-4" />
              </button>

              <button
                type="button"
                onClick={onToggleLoop}
                title="Loop queue"
                aria-pressed={loopQueue}
                aria-label="Toggle loop queue"
                className={`p-2 rounded-lg transition-colors cursor-pointer ${
                  loopQueue
                    ? 'bg-amber-500/15 text-amber-400'
                    : 'text-zinc-400 hover:text-zinc-100 hover:bg-zinc-800/80'
                }`}
              >
                <Repeat className="w-4 h-4" />
              </button>

              {!isFullscreen && (
                <button
                  type="button"
                  onClick={onToggleTheater}
                  title={theaterMode ? 'Standard layout' : 'Theater wide mode'}
                  aria-pressed={theaterMode}
                  aria-label={theaterMode ? 'Exit theater mode' : 'Enter theater mode'}
                  className="hidden lg:inline-flex p-2 rounded-lg text-zinc-400 hover:text-zinc-100 hover:bg-zinc-800/80 transition-colors cursor-pointer"
                >
                  {theaterMode ? (
                    <Minimize2 className="w-4 h-4" />
                  ) : (
                    <Maximize2 className="w-4 h-4" />
                  )}
                </button>
              )}

              <button
                type="button"
                onClick={() => void toggleFullscreen()}
                title={isFullscreen ? 'Exit fullscreen (F)' : 'Custom fullscreen (F)'}
                aria-pressed={isFullscreen}
                aria-label={isFullscreen ? 'Exit fullscreen' : 'Enter fullscreen'}
                className="p-2 rounded-lg text-zinc-300 hover:text-zinc-100 hover:bg-zinc-800/80 transition-colors cursor-pointer"
              >
                {isFullscreen ? <Shrink className="w-4 h-4" /> : <Expand className="w-4 h-4" />}
              </button>
            </div>
          </div>
        </div>
      </div>

      {/* Active Video Editorial Metadata */}
      {activeVideo && (
        <div className="mt-4 px-1">
          <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-zinc-400 mb-1.5">
            <span className="font-medium text-zinc-200">{activeVideo.author}</span>
            {source && (
              <>
                <span aria-hidden="true">·</span>
                <span>{source.category}</span>
              </>
            )}
            {activeVideo.published && (
              <>
                <span aria-hidden="true">·</span>
                <span>{formatRelativeDate(activeVideo.published)}</span>
              </>
            )}
            {typeof activeVideo.views === 'number' && activeVideo.views > 0 && (
              <>
                <span aria-hidden="true">·</span>
                <span className="tabular-nums">{formatViews(activeVideo.views)}</span>
              </>
            )}
            <span aria-hidden="true">·</span>
            <span>{privacyMode ? 'No-Cookie Embed' : 'Standard Embed'}</span>
          </div>

          <div className="flex flex-col sm:flex-row sm:items-start justify-between gap-4">
            <h1 className="font-display text-xl sm:text-2xl font-semibold text-zinc-100 leading-snug text-balance">
              {activeVideo.title}
            </h1>

            <div className="flex items-center gap-2 shrink-0">
              <button
                type="button"
                onClick={handleCopyVideoUrl}
                className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-zinc-300 bg-zinc-900 border border-zinc-800 hover:border-zinc-700 hover:text-zinc-100 rounded-lg transition-colors whitespace-nowrap shrink-0 cursor-pointer"
              >
                {copiedLink ? (
                  <>
                    <Check className="w-3.5 h-3.5 text-amber-400" />
                    Copied
                  </>
                ) : (
                  <>
                    <Copy className="w-3.5 h-3.5" />
                    Copy Link
                  </>
                )}
              </button>

              <a
                href={`https://www.youtube.com/watch?v=${activeVideo.videoId}`}
                target="_blank"
                rel="noopener noreferrer"
                className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-zinc-300 bg-zinc-900 border border-zinc-800 hover:border-zinc-700 hover:text-zinc-100 rounded-lg transition-colors whitespace-nowrap shrink-0"
              >
                YouTube
                <ExternalLink className="w-3.5 h-3.5" />
              </a>
            </div>
          </div>

          {activeVideo.description && (
            <div className="mt-3 pt-3 border-t border-zinc-800/70">
              <button
                type="button"
                onClick={() => setShowDescription((prev) => !prev)}
                className="inline-flex items-center gap-1 text-xs font-medium text-zinc-400 hover:text-zinc-200 transition-colors cursor-pointer"
              >
                {showDescription ? (
                  <>
                    Hide description <ChevronUp className="w-3.5 h-3.5" />
                  </>
                ) : (
                  <>
                    Show description <ChevronDown className="w-3.5 h-3.5" />
                  </>
                )}
              </button>
              {showDescription && (
                <p className="mt-2 text-sm text-zinc-400 whitespace-pre-line leading-relaxed max-w-3xl">
                  {activeVideo.description}
                </p>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
