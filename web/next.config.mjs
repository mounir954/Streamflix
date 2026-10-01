/** @type {import('next').NextConfig} */
const nextConfig = {
  // Export statique : le site est embarqué dans l'APK (assets) et servi par le serveur local Kotlin.
  output: "export",
  // /fr/film/_/index.html plutôt que /fr/film/_.html : plus simple à servir depuis le backend
  trailingSlash: true,
  reactStrictMode: true,
  eslint: { ignoreDuringBuilds: true },
  typescript: { ignoreBuildErrors: true },
  // pas de serveur d'optimisation d'images en export statique
  images: { unoptimized: true },
};

export default nextConfig;
