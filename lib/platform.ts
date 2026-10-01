// Défini à la compilation par build-web.sh (NEXT_PUBLIC_PLATFORM=android).
// Sur Android, les téléchargements (qui s'appuient sur ffmpeg côté PC) ne sont pas disponibles.
export const IS_ANDROID = process.env.NEXT_PUBLIC_PLATFORM === "android";
export const DOWNLOADS_ENABLED = !IS_ANDROID;
