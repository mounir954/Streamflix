"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { DetailView } from "@/components/DetailView";
import { PageLoader } from "@/components/PageLoader";
import { Button } from "@/components/ui/button";
import {
  getMovieDetails,
  getMovieRecommendations,
  getTVDetails,
  getTVRecommendations,
} from "@/lib/streamflix";
import { parseProviderIdFromSlug } from "@/lib/slug";
import { defaultLocale, isLocale } from "@/lib/i18n-config";
import type { MovieDetails, TVShowDetails, MediaItem } from "@/lib/types";

type Kind = "film" | "serie";

type Loaded =
  | { status: "loading" }
  | { status: "error" }
  | {
      status: "ready";
      kind: Kind;
      provider: string;
      realId: string;
      data: MovieDetails | TVShowDetails;
      recommendations: MediaItem[];
    };

// Le site est un export statique : une seule page "placeholder" (/fr/film/_/) est générée et le
// backend Kotlin la sert pour n'importe quel /fr/film/<slug>/. Le vrai slug vient donc de l'URL
// du navigateur et non des params Next (qui valent "_").
function readSlug(kind: Kind): { locale: string; slug: string } {
  const parts = window.location.pathname.split("/").filter(Boolean);
  const locale = isLocale(parts[0]) ? parts[0] : defaultLocale;
  const idx = parts.indexOf(kind);
  const slug = idx >= 0 && parts[idx + 1] ? decodeURIComponent(parts[idx + 1]) : "";
  return { locale, slug };
}

export function ContentLoader({ kind }: { kind: Kind }) {
  const [loaded, setLoaded] = useState<Loaded>({ status: "loading" });
  const [locale, setLocale] = useState<string>(defaultLocale);

  useEffect(() => {
    let cancelled = false;
    const { locale: loc, slug } = readSlug(kind);
    setLocale(loc);

    const parsed = parseProviderIdFromSlug(slug);
    if (!parsed) {
      setLoaded({ status: "error" });
      return;
    }

    (async () => {
      try {
        if (kind === "film") {
          const [data, recs] = await Promise.all([
            getMovieDetails(parsed.provider, parsed.id),
            getMovieRecommendations(parsed.provider, parsed.id).catch(() => null),
          ]);
          if (!cancelled)
            setLoaded({
              status: "ready", kind, provider: parsed.provider, realId: parsed.id, data,
              recommendations: (recs?.results?.slice(0, 18) ?? []) as MediaItem[],
            });
        } else {
          const [data, recs] = await Promise.all([
            getTVDetails(parsed.provider, parsed.id),
            getTVRecommendations(parsed.provider, parsed.id).catch(() => null),
          ]);
          if (!cancelled)
            setLoaded({
              status: "ready", kind, provider: parsed.provider, realId: parsed.id, data,
              recommendations: (recs?.results?.slice(0, 18) ?? []) as MediaItem[],
            });
        }
      } catch {
        if (!cancelled) setLoaded({ status: "error" });
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [kind]);

  if (loaded.status === "loading") return <PageLoader />;

  if (loaded.status === "error") {
    return (
      <div className="min-h-screen flex flex-col items-center justify-center bg-background text-center px-4">
        <h1 className="text-2xl font-semibold mb-2">Contenu introuvable</h1>
        <p className="text-muted-foreground mb-6">
          Impossible de charger cette fiche. Le provider ne répond peut-être pas.
        </p>
        <Button asChild>
          <Link href={`/${locale}/`}>Retour à l&apos;accueil</Link>
        </Button>
      </div>
    );
  }

  return (
    <DetailView
      // key : remonte le composant pour ne pas garder d'état d'un film à l'autre
      key={`${loaded.provider}:${loaded.realId}`}
      data={loaded.data as any}
      mediaType={loaded.kind === "film" ? "movie" : "tv"}
      provider={loaded.provider}
      realId={loaded.realId}
      recommendations={loaded.recommendations as any}
    />
  );
}
