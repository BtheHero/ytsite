export type SourceType = 'channel' | 'playlist';

export interface VideoItem {
  videoId: string;
  title: string;
  author: string;
  published?: string;
  thumbnail: string;
  description?: string;
  views?: number;
}

export interface VideoSource {
  id: string;
  type: SourceType;
  title: string;
  category: string;
  channelId?: string;
  playlistId?: string;
  url: string;
  videos?: VideoItem[];
  lastFetched?: number;
  error?: string;
}

export interface ParsedYouTubeInput {
  type: SourceType;
  channelId?: string;
  playlistId?: string;
  handleUrl?: string;
  canonicalUrl: string;
}

export interface FeedResponse {
  sourceTitle: string;
  channelId?: string;
  playlistId?: string;
  videos: VideoItem[];
  resolvedVia: 'api' | 'rss2json' | 'iframe-only';
}
