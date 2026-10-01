import type { Metadata } from "next";
import { HomeLoader } from "@/components/HomeLoader";
import { SITE_NAME } from "@/lib/site";

export const metadata: Metadata = {
  title: `${SITE_NAME}`,
};

export default function HomePage() {
  return <HomeLoader />;
}
