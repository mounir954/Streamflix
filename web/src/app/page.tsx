"use client";

import { useEffect } from "react";
import { defaultLocale, isLocale } from "@/lib/i18n-config";

// Remplace l'ancien middleware Next (non supporté en export statique) : cookie NEXT_LOCALE,
// sinon langue du téléphone si supportée, sinon anglais.
export default function RootRedirect() {
  useEffect(() => {
    const cookie = document.cookie.match(/(?:^|; )NEXT_LOCALE=([^;]*)/)?.[1];
    const phone = (navigator.language || "").slice(0, 2).toLowerCase();
    const locale = isLocale(cookie) ? cookie : isLocale(phone) ? phone : defaultLocale;
    window.location.replace(`/${locale}/`);
  }, []);
  return null;
}
