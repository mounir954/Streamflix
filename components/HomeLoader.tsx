"use client";

import { useEffect, useRef, useState } from "react";
import { HomeView } from "@/components/HomeView";
import { PageLoader } from "@/components/PageLoader";
import { getHomeRows, getProviders, type HomeRow } from "@/lib/streamflix";
import { getSelectedProviderClient, PROVIDER_CHANGED_EVENT } from "@/lib/provider";

interface HomeState {
  provider: string;
  rows: HomeRow[];
  error: string | null;
  isIptv: boolean;
}

// Ancien app/[locale]/page.tsx : le chargement se faisait côté serveur Next, il se fait maintenant
// dans la WebView (le site est un export statique). Recharge si l'utilisateur change de provider.
async function loadHome(provider: string): Promise<HomeState> {
  let isIptv = false;
  try {
    const providers = await getProviders();
    isIptv = providers.find((p) => p.name === provider)?.iptv ?? false;
  } catch {
    // la liste des providers est indépendante de l'accueil, on retombe sur des cartes portrait
  }
  try {
    const rows = await getHomeRows(provider, isIptv);
    return { provider, rows, error: null, isIptv };
  } catch (e) {
    console.error(`getHomeRows failed for provider "${provider}":`, e);
    return { provider, rows: [], error: e instanceof Error ? e.message : String(e), isIptv };
  }
}

export function HomeLoader() {
  const [state, setState] = useState<HomeState | null>(null);
  const requestId = useRef(0);

  useEffect(() => {
    const run = (provider: string) => {
      const id = ++requestId.current;
      setState(null);
      loadHome(provider).then((result) => {
        // ignore une réponse périmée si le provider a changé entre-temps
        if (id === requestId.current) setState(result);
      });
    };

    run(getSelectedProviderClient());

    const onChange = (event: Event) => {
      const detail = (event as CustomEvent<string>).detail;
      run(detail || getSelectedProviderClient());
    };
    window.addEventListener(PROVIDER_CHANGED_EVENT, onChange);
    return () => {
      requestId.current++;
      window.removeEventListener(PROVIDER_CHANGED_EVENT, onChange);
    };
  }, []);

  if (!state) return <PageLoader />;
  return (
    <HomeView
      key={state.provider}
      rows={state.rows}
      error={state.error}
      provider={state.provider}
      isIptv={state.isIptv}
    />
  );
}
