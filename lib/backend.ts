// Sur Android, le backend Kotlin sert aussi l'interface : même origine, donc URL relative.
// NEXT_PUBLIC_BACKEND_URL reste utilisable pour le développement (ex: http://192.168.1.10:3001).
export const BACKEND_URL = process.env.NEXT_PUBLIC_BACKEND_URL?.replace(/\/$/, "") ?? "";
