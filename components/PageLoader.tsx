"use client";

import { Navigation } from "@/components/Navigation";

// même visuel que app/[locale]/loading.tsx, réutilisé pendant les chargements côté client
export function PageLoader() {
  return (
    <div className="min-h-screen bg-background">
      <Navigation />
      <div className="flex items-center justify-center min-h-screen">
        <div className="page-loader">
          <span className="page-loader-bar" />
          <span className="page-loader-bar" />
          <span className="page-loader-bar" />
        </div>
      </div>
    </div>
  );
}
