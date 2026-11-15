import { useState } from 'react';
import { Film } from 'lucide-react';

interface ThumbnailImageProps {
  src: string;
  alt: string;
  className?: string;
  fallbackLabel?: string;
}

export function ThumbnailImage({ src, alt, className = '', fallbackLabel }: ThumbnailImageProps) {
  const [hasError, setHasError] = useState(false);

  if (hasError || !src) {
    return (
      <div
        className={`flex flex-col items-center justify-center bg-zinc-900 border border-zinc-800 text-zinc-500 p-2 text-center ${className}`}
        aria-label={alt}
      >
        <Film className="w-5 h-5 mb-1 text-zinc-600 shrink-0" />
        {fallbackLabel && (
          <span className="text-[11px] leading-tight text-zinc-500 line-clamp-2">
            {fallbackLabel}
          </span>
        )}
      </div>
    );
  }

  return (
    <img
      src={src}
      alt={alt}
      referrerPolicy="no-referrer"
      loading="lazy"
      onError={() => setHasError(true)}
      className={`object-cover bg-zinc-900 ${className}`}
    />
  );
}
