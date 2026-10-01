import { useCallback, useRef, useState } from "react";
import { IMAGE_SIZES, imageUrl } from "@/lib/constants";
import { usesAniListArtwork } from "@/lib/anime-providers";
import { cleanTitle, searchTmdbArtwork } from "@/lib/tmdb";
import { searchAniList } from "@/lib/anilist-artwork";

// tries tmdb/anilist once a provider's art 404s, call from <img onError>, no-ops after the first try
export function useArtworkFallback(
  title: string,
  year: string | undefined,
  mediaType: "movie" | "tv",
  provider?: string
) {
  const [fallbackPoster, setFallbackPoster] = useState<string | null>(null);
  const [fallbackBackdrop, setFallbackBackdrop] = useState<string | null>(null);
  const triedRef = useRef(false);

  const triggerFallback = useCallback(() => {
    if (triedRef.current || !title) return;
    triedRef.current = true;
    const clean = cleanTitle(title);
    const anime = usesAniListArtwork(provider);
    const lookup = async () => {
      let result = anime ? await searchAniList(clean) : await searchTmdbArtwork(clean, year ?? null, mediaType);
      // anilist peut tomber, tmdb en second essai vaut mieux qu'une carte vide
      if (anime && !result.poster && !result.backdrop) {
        result = await searchTmdbArtwork(clean, year ?? null, mediaType);
      }
      return result;
    };
    lookup()
      .then((d: { poster: string | null; backdrop: string | null }) => {
        if (d.poster) setFallbackPoster(imageUrl(d.poster, IMAGE_SIZES.poster.medium));
        if (d.backdrop) setFallbackBackdrop(imageUrl(d.backdrop, IMAGE_SIZES.backdrop.large));
      })
      .catch(() => {});
  }, [title, year, mediaType, provider]);

  return { fallbackPoster, fallbackBackdrop, triggerFallback };
}
