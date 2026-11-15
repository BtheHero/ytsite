interface TopBarProps {
  activeSection: 'player' | 'sources' | 'deploy';
  onNavigate: (section: 'player' | 'sources' | 'deploy' | 'queue') => void;
  onOpenAddModal: () => void;
}

export function TopBar({ activeSection, onNavigate, onOpenAddModal }: TopBarProps) {
  const linkClass = (active: boolean) =>
    `text-sm font-medium transition-colors whitespace-nowrap shrink-0 py-1 border-b-2 ${
      active
        ? 'text-zinc-100 border-amber-500'
        : 'text-zinc-400 border-transparent hover:text-zinc-100'
    }`;

  return (
    <header className="sticky top-0 z-30 bg-[#09090B]/95 backdrop-blur-md border-b border-zinc-800/80">
      <div className="max-w-[1440px] mx-auto px-4 sm:px-6 lg:px-8 h-16 flex items-center justify-between gap-8">
        <a
          href="#player"
          onClick={(e) => {
            e.preventDefault();
            onNavigate('player');
          }}
          className="font-display text-lg font-bold tracking-tight text-zinc-100 whitespace-nowrap shrink-0 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-500 rounded"
        >
          FrameStack
        </a>

        <nav aria-label="Primary" className="hidden md:flex items-center gap-6">
          <button
            type="button"
            onClick={() => onNavigate('player')}
            className={linkClass(activeSection === 'player')}
          >
            Player
          </button>
          <button
            type="button"
            onClick={() => onNavigate('queue')}
            className={linkClass(false)}
          >
            Queue
          </button>
          <button
            type="button"
            onClick={() => onNavigate('sources')}
            className={linkClass(activeSection === 'sources')}
          >
            Sources
          </button>
          <button
            type="button"
            onClick={() => onNavigate('deploy')}
            className={linkClass(activeSection === 'deploy')}
          >
            Deploy
          </button>
        </nav>

        <div className="flex items-center shrink-0">
          <button
            type="button"
            onClick={onOpenAddModal}
            className="px-4 py-2 text-sm font-semibold text-zinc-950 bg-amber-500 hover:bg-amber-400 active:bg-amber-600 rounded-lg transition-colors whitespace-nowrap shrink-0 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-400 focus-visible:ring-offset-2 focus-visible:ring-offset-[#09090B] cursor-pointer"
          >
            Add Source
          </button>
        </div>
      </div>
    </header>
  );
}
