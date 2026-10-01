import type { Metadata } from "next";
import { locales } from "@/lib/i18n-config";
import { ContentLoader } from "@/components/ContentLoader";

// Export statique : on ne génère que la page placeholder "_", le backend Kotlin la sert pour
// tous les slugs (voir StaticSite.kt) et ContentLoader lit le vrai slug dans l'URL.
export const dynamicParams = false;

export function generateStaticParams() {
  return locales.map((locale) => ({ locale, slug: "_" }));
}

export const metadata: Metadata = { title: "StreamFlix" };

export default function FilmPage() {
  return <ContentLoader kind="film" />;
}
